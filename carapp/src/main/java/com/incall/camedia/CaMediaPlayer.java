package com.incall.camedia;

/**
 * 与厂商 com.incall.camedia.CaMediaPlayer 同包同名的兼容实现。
 *
 * 车机侧摄像头是 H.265 的 RTSP 流（rtsp://172.16.6.26:8888/lt|rt），
 * 系统 MediaPlayer 的 RTSP 源不支持 H.265，只有厂商的 libffmpeg.so 能解。
 *
 * libffmpeg.so 用 JNI_OnLoad + RegisterNatives 动态注册原生方法，注册目标是
 * FindClass("com/incall/camedia/CaMediaPlayer") —— 所以自己写一个同包同名、
 * 方法名与签名完全一致的类即可把原生方法绑上来。
 *
 * .so 由 carapp 的 Gradle 任务从 CaEvsAppService.apk 抽出放进 jniLibs。
 */
public class CaMediaPlayer {

    private long mNativeObject;

    static {
        try {
            // 正式 App：.so 打进 jniLibs，走正常加载
            System.loadLibrary("ffmpeg");
        } catch (Throwable t) {
            // 兜底：root 版 app_process 用推送的 .so
            System.load("/data/local/tmp/q07lib/libffmpeg.so");
        }
    }

    private static native long nativeCreate();

    private static native int nativeClose(long handle);

    private static native int nativeRelease(long handle);

    private static native int nativeGetStates(long handle);

    private static native int nativeSetDataSource(long handle, String url);

    private static native int nativeSetSurface(long handle, android.view.Surface surface);

    private static native int nativePlay(long handle, android.view.Surface surface);

    private static native void nativeSetOption(long handle, int type, String key, String value);

    private static native void nativeSetOption(long handle, int type, String key, long value);

    private static native void nativeRegisterMediaInfoCallback(long handle, MediaInfoCallback callback);

    private static native void nativeUnRegisterMediaInfoCallback(long handle);

    public CaMediaPlayer() {
        mNativeObject = nativeCreate();
    }

    public int setDataSource(String url) {
        return nativeSetDataSource(mNativeObject, url);
    }

    public int setSurface(android.view.Surface surface) {
        return nativeSetSurface(mNativeObject, surface);
    }

    public int play(android.view.Surface surface) {
        return nativePlay(mNativeObject, surface);
    }

    public int getStates() {
        return nativeGetStates(mNativeObject);
    }

    public int close() {
        return nativeClose(mNativeObject);
    }

    public void release() {
        nativeRelease(mNativeObject);
    }

    public void setOption(int type, String key, String value) {
        nativeSetOption(mNativeObject, type, key, value);
    }

    public void setOption(int type, String key, long value) {
        nativeSetOption(mNativeObject, type, key, value);
    }

    public void registerMediaInfoCallback(MediaInfoCallback callback) {
        nativeRegisterMediaInfoCallback(mNativeObject, callback);
    }

    public void unRegisterMediaInfoCallback() {
        nativeUnRegisterMediaInfoCallback(mNativeObject);
    }
}
