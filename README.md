# uxplay-android — AirPlay 镜像接收端 / AirPlay mirroring receiver for Android TV

[中文](#中文) | [English](#english)

<a name="中文"></a>

## 这是什么

把一台安卓电视 / 电视盒变成 **AirPlay 接收端**：iPhone、iPad、Mac 可以直接"屏幕镜像"到它上面（画面 + 声音）。

基于 [UxPlay](https://github.com/FDH2/UxPlay)（GPL-3.0）的协议栈移植：C 层跑完整 AirPlay/Mirror 协议（mDNS 发现、FairPlay 握手、RTSP、镜像 RTP），Android 层用系统 **MediaCodec 硬解**渲染视频、解码播放 AAC-ELD 音频。没有自绘解码器，老盒子也能流畅跑。

**已在真机验证**：Android 10 索尼 BRAVIA 电视（MTK 方案，armeabi-v7a）Mac/iPhone 镜像视频 + 音频全通，音量同步正常。

## 功能

- 屏幕**镜像 + 音频**（AirPlay Mirror，H.264/H.265 + AAC-ELD）
- 系统硬解：视频 MediaCodec → SurfaceView，音频 MediaCodec → AudioTrack
- 音频抗抖动缓冲（预缓冲 + 大队列），WiFi 突发抖动不出爆音；内建 underrun / 丢帧统计日志
- **音量同步**：Mac/iPhone 上调节音量，电视端跟随（AirPlay dB 值 → 线性增益正确换算）
- mDNS 广播（NsdManager），开机自启（BootReceiver），有会话接入自动唤起画面
- Java 层零第三方依赖 → 天然单 dex，兼容"只加载主 dex"的电视 ROM
- 前台服务常驻，状态在通知栏

## 系统要求

- Android 7.0+（API 24），armeabi-v7a（arm64 设备兼容运行 v7a）
- 构建侧：Android Studio（含 NDK + CMake 3.22+），JDK 17

## 构建

```bash
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
```

- 仓库内嵌 UxPlay 源码（`uxplay/`）与预编译依赖（`deps/openssl-build/` OpenSSL 3.3.2 armv7 静态库、`deps/libplist/` 2.4.0 源码），克隆即可构建，无需额外准备。
- 如需重编 OpenSSL：运行 `deps/build_openssl.sh`（自动探测/下载，需 NDK）。
- 命令行构建注意 JAVA_HOME 指向 JDK 17（如 Android Studio 自带 JBR）；Android Studio 打开则无需处理。

## 安装与使用

```bash
adb install -r app-debug.apk
adb shell am start -n com.codelphi.airplay/.MirrorActivity   # 启动后服务常驻
```

之后在 iPhone/Mac 的"屏幕镜像"列表里选择这台接收端即可。

**Android 10+ 建议执行一次**（让"应用不在前台时也能自动弹出镜像画面"生效，后台启 Activity 的豁免权限）：

```bash
adb shell appops set com.codelphi.airplay SYSTEM_ALERT_WINDOW allow
```

**改接收端显示名**（默认 `AirPlay-Receiver`）：

```bash
adb shell run-as com.codelphi.airplay sh -c 'echo -n 我家的接收端 > files/airplay_name'
adb shell am force-stop com.codelphi.airplay   # 重启生效
```

## 排查技巧

- 音频质量日志：`adb logcat -s AudioSink`（每 5 秒输出队列深度/丢帧/underrun/播放头速率；underrun 应长期为 0）
- 解码 PCM 落盘：`/sdcard/Android/data/com.codelphi.airplay/files/airplay_pcm.raw`（前 30 秒，16bit 交错立体声 44100Hz），可用 numpy/audacity 分析是解码侧还是播放侧的问题
- 协议栈日志：C 层走 `raop_set_log_callback` → logcat tag `AirPlayStack`

## 已知边界

- 纯音频投放的 ALAC 编码（ct=1）暂未解码（镜像音频 AAC-ELD 不受影响）
- 仅实现了 NsdManager 版 mDNS 注册；未做 Bonjour 浏览回环等桌面特性

## 许可证

本项目继承 UxPlay 的 **GPL-3.0** 许可（见 [LICENSE](LICENSE)）。第三方组件：

| 组件 | 许可证 | 说明 |
|---|---|---|
| [UxPlay](https://github.com/FDH2/UxPlay)（`uxplay/`） | GPL-3.0 | AirPlay 协议栈（含 playfair、llhttp MIT） |
| [libplist](https://github.com/libimobiledevice/libplist) 2.4.0（`deps/libplist/`） | LGPL-2.1 | Apple 二进制 plist 解析 |
| OpenSSL 3.3.2（`deps/openssl-build/`，Apache-2.0） | Apache-2.0 | FairPlay/加密所需 |

---

<a name="english"></a>

## What is this

Turn an Android TV / TV box into an **AirPlay receiver**: mirror your iPhone, iPad or Mac screen (video + audio) to it.

This is a port of the [UxPlay](https://github.com/FDH2/UxPlay) (GPL-3.0) protocol stack to Android. The C layer runs the full AirPlay/Mirror protocol (mDNS discovery, FairPlay handshake, RTSP, mirror RTP); the Android side renders video with system **MediaCodec hardware decoding** and plays AAC-ELD audio — no bundled decoders, so even modest boxes run it smoothly.

**Verified on real hardware**: Android 10 Sony BRAVIA TV (MTK, armeabi-v7a) — Mac/iPhone mirroring with video and audio fully working, volume sync included.

## Features

- Screen **mirroring + audio** (AirPlay Mirror, H.264/H.265 + AAC-ELD)
- Hardware decode: MediaCodec → SurfaceView (video), MediaCodec → AudioTrack (audio)
- Jitter-buffered audio playback (prebuffer + deep queue) — no crackle under Wi-Fi burstiness; built-in underrun/drop statistics
- **Volume sync**: receiver volume follows Mac/iPhone volume (AirPlay dB values correctly mapped to linear gain)
- mDNS advertisement via NsdManager, boot autostart, auto-opens the screen when a session starts
- Zero third-party Java dependencies → naturally single-dex, compatible with TV ROMs that only load the primary dex
- Foreground service, status in the notification shade

## Requirements

- Android 7.0+ (API 24), armeabi-v7a (arm64 devices run v7a fine)
- Build: Android Studio (NDK + CMake 3.22+), JDK 17

## Build

```bash
./gradlew assembleDebug        # output: app/build/outputs/apk/debug/app-debug.apk
```

The repo vendors the UxPlay sources (`uxplay/`) and prebuilt dependencies (`deps/openssl-build/` — OpenSSL 3.3.2 armv7 static libs, `deps/libplist/` — 2.4.0 sources), so it builds out of the box. To rebuild OpenSSL: `deps/build_openssl.sh` (auto-detects NDK, downloads sources).

## Install & use

```bash
adb install -r app-debug.apk
adb shell am start -n com.codelphi.airplay/.MirrorActivity   # service stays resident
```

Then pick this receiver in the iPhone/Mac "Screen Mirroring" list.

**Recommended on Android 10+** (allows the receiver to bring its screen up automatically when a session starts, while the app is in the background):

```bash
adb shell appops set com.codelphi.airplay SYSTEM_ALERT_WINDOW allow
```

**Rename the receiver** (default `AirPlay-Receiver`):

```bash
adb shell run-as com.codelphi.airplay sh -c 'echo -n MyReceiver > files/airplay_name'
adb shell am force-stop com.codelphi.airplay   # takes effect after restart
```

## Troubleshooting

- Audio quality stats: `adb logcat -s AudioSink` (queue depth / dropped frames / underruns every 5s; underruns should stay 0)
- Decoded PCM dump: `/sdcard/Android/data/com.codelphi.airplay/files/airplay_pcm.raw` (first 30s, 16-bit interleaved stereo 44100 Hz) — analyze with numpy/Audacity to tell decoder-side from playback-side issues

## Known limitations

- ALAC (ct=1) audio-only AirPlay sessions are not decoded yet (mirror audio uses AAC-ELD and is unaffected)
- mDNS advertisement only (NsdManager); no browsing features

## License

This project inherits UxPlay's **GPL-3.0** license (see [LICENSE](LICENSE)). Third-party components: UxPlay (GPL-3.0, incl. playfair; llhttp MIT), libplist 2.4.0 (LGPL-2.1), OpenSSL 3.3.2 (Apache-2.0).
