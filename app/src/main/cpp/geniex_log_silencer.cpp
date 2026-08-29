#include <cstdint>
#include <dlfcn.h>
#include <jni.h>

namespace {
using GenieXLogCallback = void (*)(int32_t, const char*);
using GenieXSetLog = int32_t (*)(GenieXLogCallback);

void content_free_log(int32_t, const char*) {
    // Intentionally discard vendor diagnostics because some messages contain the full prompt.
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_envi_wispr_polish_S1NativeLog_installContentFreeLogger(JNIEnv*, jobject) {
    void* handle = dlopen("libgeniex.so", RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen("libgeniex.so", RTLD_NOW);
    }
    if (handle == nullptr) return -1;

    auto set_log = reinterpret_cast<GenieXSetLog>(dlsym(handle, "geniex_set_log"));
    if (set_log == nullptr) return -2;
    return set_log(content_free_log);
}
