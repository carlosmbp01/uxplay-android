package com.codelphi.airplay;

import android.view.Surface;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/** 视频解码驱动：C 侧 NALU → AvCC→AnnexB 转换 → MediaCodec（csd 惰性配置）→ Surface */
public class VideoSink {

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

    public synchronized void start(boolean h265, Surface surface) {
        stop();
        this.h265 = h265;
        this.surface = surface;
        try {
            createCodec();
            running = true;
            thread = new Thread(this::loop, "videosink");
            thread.start();
        } catch (Exception e) {
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
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, surface, null, 0);
        codec.start();
    }

    private void reconfigureWithCsd() {
        csdConfigured = true;
        running = false;
        queue.clear();
        if (thread != null) { thread.interrupt(); try { thread.join(500); } catch (InterruptedException ignored) { } thread = null; }
        if (codec != null) { try { codec.stop(); codec.release(); } catch (Exception ignored) { } codec = null; }
        try {
            running = true;
            createCodec();
            thread = new Thread(this::loop);
            thread.start();
            android.util.Log.i("VideoSink", "已用 csd 重配置解码器");
        } catch (Exception e) {
            android.util.Log.e("VideoSink", "csd 重配置失败", e);
        }
    }

    public void push(byte[] nal) {
        if (!running) {
            /* 会话结束后已停机；新会话首帧到达即自动重启（csd 已缓存，Surface 仍有效时可立即出画） */
            if (surface == null || !surface.isValid()) return;
            start(h265, surface);
            if (!running) return;
        }
        byte[] annexb = avccToAnnexB(nal);
        extractSpsPps(annexb);
        if (!csdConfigured && spsNal != null && ppsNal != null) {
            reconfigureWithCsd();   /* MTK 等硬解器需要 configure 时携带 csd */
        }
        if (running) queue.offer(annexb);
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
        /* spsNal/ppsNal/csdConfigured 刻意保留：AirPlay 只在关键帧重发 SPS/PPS，
           Surface 重建后带缓存 csd 启动可立即出画 */
        if (thread != null) { thread.interrupt(); thread = null; }
        if (codec != null) { codec.stop(); codec.release(); codec = null; }
        queue.clear();
    }

    /** 热循环：立即吞下全部输入并榨干全部输出（60fps 镜像流不能按 50ms 轮询慢慢来） */
    private void loop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception ignored) { }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long ts = 0;
        while (running) {
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
                        android.util.Log.i("VideoSink", "格式: " + codec.getOutputFormat());
                    } else break;
                }
                if (!fed && !got) Thread.sleep(10);   /* 空闲降温 */
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (running) android.util.Log.e("VideoSink", "解码异常", e);
            }
        }
    }
}
