package io.bambosan.mbloader;

/** Thin JNI wrapper around dlopen() (see jni/preload.cpp). */
final class NativePreload {
    static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("mblpreload");
            ok = true;
        } catch (Throwable t) {
            android.util.Log.e("MBL", "libmblpreload.so not loadable", t);
            ok = false;
        }
        AVAILABLE = ok;
    }

    private NativePreload() {}

    /** @return null on success, otherwise the dlerror() message */
    static native String dlopenGlobal(String absolutePath);
}
