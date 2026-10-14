# 侧摄像头画面（RTSP + 厂商 FFmpeg）逆向笔记

> 目标：在悬浮窗里显示**打转向灯时对应侧的摄像头画面**。
> 结论：**已跑通** —— 车机自带的侧视 RTSP 流（H.265），用**厂商自己的 FFmpeg 播放器**渲染进我们的 SurfaceView。

---

## 1. 侧摄像头是 RTSP 流

在 EVS 应用（`com.changan.evs.appservice`，车机路径 `/system/app/CaEvsAppService/CaEvsAppService.apk`）
的 `com.incall.apps.avmservice.utils.ViewAssistConstant` 里找到：

```
RTSP_URL_LT = rtsp://172.16.6.26:8888/lt     ← 左
RTSP_URL_RT = rtsp://172.16.6.26:8888/rt     ← 右
```

车机有 `vlan6 = 172.16.6.50/24`，和 `172.16.6.26` 同网段，**ping 0.5ms**，
`OPTIONS` 返回 `RTSP/1.0 200 OK / Public: OPTIONS, DESCRIBE, SETUP, PLAY`。

**SDP**：

```
m=video 0 RTP/AVP 127
a=rtpmap:127 H265/90000      ← ★ H.265
a=framerate:30
```

## 2. 为什么系统 MediaPlayer 播不了

```
RTSPSource: DESCRIBE completed with result 0 (Success)
RTSPSource: Unsupported format. Ignoring track #1.        ← ★ 就这一句
RTSPSource: SETUP(1) completed with result -1010
MediaPlayerNative: error (1, -2147483648)
```

Android 的 `RTSPSource` **不认 RTSP 里的 H.265**，直接把 track 丢掉 → 没有轨道 → 报错。
（`MediaExtractor` 也一样，走同一套 NuPlayer。）

## 3. 正解：复用厂商的 FFmpeg 播放器

### 3.1 厂商的播放器是什么

`com.incall.camedia.CaMediaPlayer`（在 EVS APK 的 **classes2.dex**）是 **JNI 包的原生播放器**：

```java
static { System.loadLibrary("ffmpeg"); }     // ← 关键
private static native long nativeCreate();
private static native int  nativeSetDataSource(long, String);
private static native int  nativeSetSurface(long, android.view.Surface);
private static native int  nativePlay(long, android.view.Surface);
private static native int  nativeGetStates(long);
private static native int  nativeClose(long);
private static native int  nativeRelease(long);
private static native void nativeSetOption(long, int, String, String);
private static native void nativeSetOption(long, int, String, long);
private static native void nativeRegisterMediaInfoCallback(long, MediaInfoCallback);
private static native void nativeUnRegisterMediaInfoCallback(long);
```

`.so` 就在同一个 APK 里：`lib/arm64-v8a/libffmpeg.so`（**14.8 MB，只依赖系统库** ——
FFmpeg 是静态编进去的，所以不用带 libavcodec/libavformat）。

### 3.2 关键技巧：同包同名类

`libffmpeg.so` 里**没有** `Java_com_incall_camedia_*` 符号 —— 它用
`JNI_OnLoad` + **`RegisterNatives`** 动态注册。而 `RegisterNatives` 的目标是
`FindClass("com/incall/camedia/CaMediaPlayer")` 找到的那个类。

**所以我们自己写一个同包同名的类**（`tools/overlay/com/incall/camedia/CaMediaPlayer.java`），
方法名与签名和厂商完全一致，`System.load("/data/local/tmp/q07lib/libffmpeg.so")`
之后原生方法就绑到我们自己的类上了 —— **完全不需要他们的 dex**。

> 注意：`BaseDexClassLoader.findLibrary` 是 `final`，不能靠重写 `findLibrary`
> 让 `System.loadLibrary("ffmpeg")` 找到我们推送的 .so，所以要走"同名类"这条路。

### 3.3 实测

```
[cam] new CaMediaPlayer() ...
[cam] created
[cam] setDataSource(rtsp://172.16.6.26:8888/lt) -> 0
[cam] setSurface -> 0
[cam] play(surface) -> 0
[cam] t+1s getStates=1      ← 在播
```

截图确认：中控屏上出现**实时摄像头画面** ✓

## 4. 顺带记录：Camera2 走不通

车机有 6 个摄像头，但连一个都开不了：

```
REJECT device 0 client for package android (PID 9155),
  reason: '6: connectHelper:1747: Camera "0" disabled by policy'
```

`libcameraservice.so` 里被厂商加了策略：

```
%s:%d: Camera "%s" disabled by policy
com.incall.apps.facecollect
com.incall.apps.wisdomid
```

（"disabled by policy" 的格式串就在 `libcameraservice.so` 偏移 0x788B4 附近，
两个包名紧挨着。）

所以：**走 RTSP，不要碰 Camera2。**

---

## 5. 交付物

| 文件 | 作用 |
|---|---|
| `tools/overlay/com/incall/camedia/CaMediaPlayer.java` | 与厂商同名的 JNI 兼容类 |
| `tools/overlay/com/incall/camedia/MediaInfoCallback.java` | 占位接口（配签名） |
| `tools/overlay/Main.java` | 转向灯联动：左→`/lt`，右→`/rt`，灭→关 |
| `tools/build-overlay.ps1` | 自动从 APK 提取 `libffmpeg.so` 并推送 |
| `tools/run-overlay.ps1` | 缺 `libffmpeg.so` 时自动补推 |

摄像头窗口贴着文字面板下方：`(470, 745)`，`720×405`。
