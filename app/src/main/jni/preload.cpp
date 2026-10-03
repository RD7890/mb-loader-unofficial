#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define LOG_TAG "MBL"

// Loads a library with dlopen() only. Unlike System.loadLibrary() this does NOT call JNI_OnLoad,
// it just maps the library so the linker can resolve DT_NEEDED entries of libminecraftpe.so by
// soname. This is exactly what the linker would do on its own in the real Minecraft app.
// Returns null on success, otherwise the dlerror() text.
extern "C" JNIEXPORT jstring JNICALL
Java_io_bambosan_mbloader_NativePreload_dlopenGlobal(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    jstring result = nullptr;
    if (!handle) {
        const char *err = dlerror();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dlopen(%s) failed: %s", path, err ? err : "?");
        result = env->NewStringUTF(err ? err : "dlopen failed");
    } else {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dlopen(%s) ok (no JNI_OnLoad)", path);
    }
    env->ReleaseStringUTFChars(jpath, path);
    return result;
}
