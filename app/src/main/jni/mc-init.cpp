#include <jni.h>
#include <string>
#include <dlfcn.h>

#include <android/log.h>
#include <android/native_activity.h>

#define LOG_TAG "MBL"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void (*android_main_minecraft)(struct android_app *app) = nullptr;
static void (*ANativeActivity_onCreate_minecraft)(ANativeActivity *activity, void *savedState, size_t savedStateSize) = nullptr;

extern "C" void android_main(struct android_app *app) {
    if (android_main_minecraft) {
        android_main_minecraft(app);
    } else {
        LOGE("android_main: libminecraftpe.so symbol not resolved");
    }
}

extern "C" void ANativeActivity_onCreate(ANativeActivity *activity, void *savedState, size_t savedStateSize) {
    if (ANativeActivity_onCreate_minecraft) {
        ANativeActivity_onCreate_minecraft(activity, savedState, savedStateSize);
    } else {
        LOGE("ANativeActivity_onCreate: libminecraftpe.so symbol not resolved");
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    void *handle = dlopen("libminecraftpe.so", RTLD_LAZY);
    if (!handle) {
        // Previously this fell through to dlsym(NULL, ...) and crashed silently.
        LOGE("dlopen(libminecraftpe.so) failed: %s", dlerror());
        return JNI_VERSION_1_6;
    }
    android_main_minecraft = (void (*)(struct android_app *)) (dlsym(handle, "android_main"));
    ANativeActivity_onCreate_minecraft = (void (*)(ANativeActivity *, void *, size_t)) (dlsym(handle, "ANativeActivity_onCreate"));
    if (!android_main_minecraft) LOGE("dlsym(android_main) failed: %s", dlerror());
    if (!ANativeActivity_onCreate_minecraft) LOGE("dlsym(ANativeActivity_onCreate) failed: %s", dlerror());
    return JNI_VERSION_1_6;
}
