package com.codelphi.airplay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MirrorActivity extends Activity implements NativeBridge.Listener {

    private SurfaceView surfaceView;
    private TextView status;
    private final Handler uiHandler = new Handler();
    private long lastBack;

    /* 镜像中每秒检查一次：有帧 → 隐藏状态文字；无帧 → 显示等待提示 */
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            boolean mirroring = NativeBridge.videoIdleMs() < 3000;
            status.setVisibility(mirroring ? View.GONE : View.VISIBLE);
            if (!mirroring) status.setText("等待 iPhone/Mac 连接…");
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.mirror);

        surfaceView = findViewById(R.id.surface);
        status = findViewById(R.id.status);

        NativeBridge.setListener(this);
        /* 解码器报出真实分辨率后按比例重排 SurfaceView：保持宽高比居中，多余部分留黑边，绝不拉伸 */
        VideoSink.setVideoSizeListener((w, h) -> runOnUiThread(() -> applyLetterbox(w, h)));

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                NativeBridge.setSurface(holder.getSurface());
                NativeBridge.nativeConnectSurface(holder.getSurface());
                VideoSink sink = NativeBridge.getVideoSink();
                if (!sink.isRunning()) sink.start(false, holder.getSurface());
                AudioSink sink2 = NativeBridge.getAudioSink();
                if (!sink2.isRunning()) sink2.start();          /* 音频解码驱动（此前漏启动） */
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                NativeBridge.nativeDisconnectSurface();
                NativeBridge.getVideoSink().stop();
                NativeBridge.getAudioSink().stop();
            }
        });

        uiHandler.postDelayed(uiTick, 1200);
        startForegroundService(new Intent(this, AirPlayService.class));
    }

    @Override
    public void onBackPressed() {
        if (System.currentTimeMillis() - lastBack < 2000) {
            stopService(new Intent(this, AirPlayService.class));
            finish();
        } else {
            lastBack = System.currentTimeMillis();
            Toast.makeText(this, "再按一次返回键退出投屏", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        VideoSink.setVideoSizeListener(null);   /* Activity 可重建，避免持着旧实例泄漏 */
        super.onDestroy();
    }

    /** letterbox 等比缩放：视频比例与屏幕一致时恢复满屏；不一致时居中 + 黑边（更宽→上下黑边，更窄→左右黑边） */
    private void applyLetterbox(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || surfaceView == null) return;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int sw = dm.widthPixels, sh = dm.heightPixels;
        if (sw <= 0 || sh <= 0) return;
        double videoAr = (double) vw / vh;
        double screenAr = (double) sw / sh;
        int w, h;
        if (videoAr > screenAr) {
            w = sw;
            h = (int) Math.round(sw / videoAr);
        } else {
            h = sh;
            w = (int) Math.round(sh * videoAr);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(lp);
        android.util.Log.i("MirrorActivity", "letterbox: 视频 " + vw + "x" + vh + " → 显示 " + w + "x" + h + " (屏 " + sw + "x" + sh + ")");
    }

    @Override
    public void onAirplayRegister(String name, int port, String deviceId, String pk) {
        runOnUiThread(() -> status.setText("AirPlay 已注册: " + name + ":" + port));
    }

    @Override
    public void onRaopRegister(String name, int port, String deviceId, String pk) {
        runOnUiThread(() -> status.setText("RAOP 已注册: " + name + ":" + port));
    }

    @Override
    public void onLog(String msg) {
        runOnUiThread(() -> status.setText(msg));
    }
}
