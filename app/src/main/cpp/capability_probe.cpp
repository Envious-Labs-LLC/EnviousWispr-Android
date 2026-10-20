#include <dlfcn.h>
#include <jni.h>

// #449: GenieX's llama plugin links libggml-opencl.so, which links libOpenCL.so, so GenieX cannot start (not even
// on the processor) on a phone whose vendor does not offer that library to apps. This opens it in the app's own
// linker namespace, the one GenieX's loader uses, and closes it again. It touches nothing of GenieX's.
// Returns null when the library opened, otherwise the linker's own error text (library and symbol names only, never
// user content) so the runtime choice can say why in the local log.
extern "C" JNIEXPORT jstring JNICALL
Java_com_envi_wispr_polish_OpenClProbe_nativeOpenError(JNIEnv* env, jobject) {
    void* handle = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        return env->NewStringUTF(error != nullptr ? error : "dlopen failed");
    }
    dlclose(handle);
    return nullptr;
}
