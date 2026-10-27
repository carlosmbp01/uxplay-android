package com.codelphi.airplay;

import android.view.Surface;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/** 视频解码驱动：C 侧 NALU → AvCC→AnnexB 转换 → MediaCodec（csd 惰性配置）→ Surface */
public class VideoSink {

    /** 视频真实分辨率回调（UI 层据此做 letterbox 等比缩放，避免拉伸） */
    public interface VideoSizeListener { void onVideoSize(int width, int height); }

    private static volatile VideoSizeListener sizeListener;
    private int lastReportedW, lastReportedH;

    public static void setVideoSizeListener(VideoSizeListener l) { sizeListener = l; }

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(16);
    private MediaCodec codec;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean csdConfigured;
    private Surface surface;
    private boolean h265;

    private byte[] spsNal;  /* 含起始码的 SPS */
    private byte[] ppsNal;  /* 含起始码的 PPS */

    public synchronized boolean isRunning() { return running; }

    private long retryAfter;   /* 启动失败后退避，避免每帧重试刷屏 */

    public synchronized void start(boolean h265, Surface surface) {
        stop();
        this.h265 = h265;
        this.surface = surface;
        try {
            createCodec();
            running = true;
            final MediaCodec c = codec;
            thread = new Thread(() -> loop(c), "videosink");
            thread.start();
        } catch (Exception e) {
            retryAfter = System.currentTimeMillis() + 1000;
            android.util.Log.e("VideoSink", "启动失败", e);
        }
    }

    private void createCodec() throws Exception {
        String mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, 1920, 1080);
        if (csdConfigured && spsNal != null && ppsNal != null) {
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(spsNal));
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(ppsNal));
        }
        MediaCodec c = MediaCodec.createDecoderByType(mime);
        try {
            c.configure(fmt, surface, null, 0);
            c.start();
        } catch (Exception e) {
            try { c.release(); } catch (Exception ignored) { }   /* 失败也必须释放，否则占着 Surface */
            throw e;
        }
        codec = c;
    }

    public synchronized void push(byte[] nal) {
        if (!running) {
            /* 会话结束或解码器故障后停机；新帧到达即自动重启（csd 已缓存） */
            if (surface == null || !surface.isValid()) return;
            if (System.currentTimeMillis() < retryAfter) return;
            start(h265, surface);
            if (!running) return;
        }
        byte[] annexb = avccToAnnexB(nal);
        extractSpsPps(annexb);
        if (!csdConfigured && spsNal != null && ppsNal != null) {
            csdConfigured = true;   /* MTK 等硬解器需要 configure 时携带 csd */
            start(h265, surface);
            if (!running) return;
        }
        if (!queue.offer(annexb)) { queue.poll(); queue.offer(annexb); }   /* 满了丢最旧，不卡 */
    }

    /** 从 Annex-B 流中提取 SPS(type 7)/PPS(type 8) NALU（含起始码） */
    private void extractSpsPps(byte[] b) {
        int i = 0;
        while (i < b.length - 4) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) {
                int type = b[i + 4] & 0x1f;
                int next = findNextStartCode(b, i + 4);
                int nalEnd = next < 0 ? b.length : next;
                if (type == 7 && spsNal == null) spsNal = slice(b, i, nalEnd);
                if (type == 8 && ppsNal == null) ppsNal = slice(b, i, nalEnd);
                i = i + 4;
            } else {
                i++;
            }
        }
    }

    private static int findNextStartCode(byte[] b, int from) {
        for (int i = from; i < b.length - 3; i++)
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) return i;
        return -1;
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }

    /** UxPlay 交来的是 AvCC 格式（4 字节大端长度前缀），转 Annex-B（00 00 00 01 起始码） */
    private static byte[] avccToAnnexB(byte[] avcc) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(avcc.length + 16);
        java.nio.ByteBuffer wrap = java.nio.ByteBuffer.wrap(avcc);
        byte[] sc = {0, 0, 0, 1};
        while (wrap.remaining() >= 4) {
            int len = wrap.getInt();
            if (len <= 0 || len > wrap.remaining()) return avcc;  /* 已是 Annex-B 或格式异常 */
            out.write(sc, 0, 4);
            byte[] nal = new byte[len];
            wrap.get(nal);
            out.write(nal, 0, len);
        }
        return out.toByteArray();
    }

    public synchronized void stop() {
        running = false;
        /* spsNal/ppsNal/csdConfigured 刻意保留：Surface 重建后带缓存 csd 启动可立即出画 */
        if (thread != null) {
            thread.interrupt();
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) { }
            try { codec.release(); } catch (Exception ignored) { }   /* 无论如何都释放 Surface */
            codec = null;
        }
        queue.clear();
    }

    /** 解码线程发现解码器坏掉：停机，下一帧由 push 自动重建 */
    private synchronized void markBroken(MediaCodec c) {
        if (codec != c) return;
        stop();
        retryAfter = System.currentTimeMillis() + 300;
    }

    /** 从输出格式解析实际显示尺寸：优先 crop 裁剪框（硬解常带对齐 padding），否则退回 width/height */
    private static int[] displaySizeOf(MediaFormat f) {
        int w = -1, h = -1;
        try {
            if (f.containsKey("crop-right") && f.containsKey("crop-left"))
                w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
            else if (f.containsKey("width")) w = f.getInteger("width");
            if (f.containsKey("crop-bottom") && f.containsKey("crop-top"))
                h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
            else if (f.containsKey("height")) h = f.getInteger("height");
        } catch (Exception ignored) { }
        return new int[]{w, h};
    }

    /** 分辨率没变化不重复通知；iPhone 横竖屏切换、iPad 接入都会带新分辨率进来 */
    private void reportVideoSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == lastReportedW && h == lastReportedH) return;
        lastReportedW = w;
        lastReportedH = h;
        VideoSizeListener l = sizeListener;
        if (l != null) l.onVideoSize(w, h);
    }

    /** 热循环：立即吞下全部输入并榨干全部输出（60fps 镜像流不能按 50ms 轮询慢慢来） */
    private void loop(MediaCodec codec) {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception ignored) { }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long ts = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                boolean fed = false;
                byte[] nal;
                while (running && (nal = queue.poll()) != null) {
                    int inIdx = codec.dequeueInputBuffer(0);
                    if (inIdx < 0) {   /* 输入缓冲耗尽，下轮再喂剩余的 */
                        queue.offer(nal);
                        break;
                    }
                    ByteBuffer in = codec.getInputBuffer(inIdx);
                    in.clear();
                    in.put(nal);
                    codec.queueInputBuffer(inIdx, 0, nal.length, ts += 33367, 0);
                    fed = true;
                }
                /* 榨干全部输出 */
                boolean got = false;
                while (running) {
                    int outIdx = codec.dequeueOutputBuffer(info, fed ? 0 : 10000);
                    if (outIdx >= 0) {
                        codec.releaseOutputBuffer(outIdx, true);
                        got = true;
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!fed) Thread.sleep(10);
                        break;
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = codec.getOutputFormat();
                        int[] disp = displaySizeOf(f);
                        android.util.Log.i("VideoSink", "格式: " + f + " → 显示 " + disp[0] + "x" + disp[1]);
                        reportVideoSize(disp[0], disp[1]);
                    } else break;
                }
                if (!fed && !got) Thread.sleep(10);   /* 空闲降温 */
            } catch (InterruptedException e) {
                return;
            } catch (IllegalStateException e) {   /* 含 CodecException：解码器已坏，重建 */
                if (running) {
                    android.util.Log.e("VideoSink", "解码器故障，重建", e);
                    new Thread(() -> markBroken(codec)).start();
                }
                return;
            } catch (Exception e) {
                if (running) android.util.Log.e("VideoSink", "解码异常", e);
            }
        }
    }
}
