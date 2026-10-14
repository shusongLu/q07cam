package com.incall.camedia;

/**
 * 与厂商 com.incall.camedia.CaMediaPlayer 同包同名的兼容实现。
 *
 * 背景：车机侧摄像头是 H.265 的 RTSP 流（rtsp://172.16.6.26:8888/lt|rt），
 * 系统 MediaPlayer 的 RTSP 源不支持 H.265（logcat: "Unsupported format. Ignoring track #1"），
 * 只有厂商的 libffmpeg.so 能解。
 *
 * libffmpeg.so 用 JNI_OnLoad + RegisterNatives 动态注册原生方法，
 * 注册目标是 FindClass("com/incall/camedia/CaMediaPlayer") —— 所以只要我们自己
 * 写一个同包同名的类、方法名与签名完全一致，原生方法就会绑到这个类上。
 *
 * .so 从 CaEvsAppService.apk 里提取（只依赖系统库，FFmpeg 静态编在里面）。
 */
public class CaMediaPlayer {

    /** .so 放在这里，由 tools/build-overlay.ps1 推送。 */
    private static final String LIB_PATH = "/data/local/tmp/q07lib/libffmpeg.so";

    private long mNativeObject;

    static {
        System.load(LIB_PATH);
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
