#include <dlfcn.h>
#include <jni.h>

// #449: GenieX's llama plugin links libggml-opencl.so, which links libOpenCL.so, so GenieX cannot start (not even
// on the processor) on a phone whose vendor does not offer that library to apps. This opens it in the app's own
// linker namespace, the one GenieX's loader uses, and closes it again. It touches nothing of GenieX's.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_envi_wispr_polish_OpenClProbe_nativeAvailable(JNIEnv*, jobject) {
    void* handle = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) return JNI_FALSE;
    dlclose(handle);
    return JNI_TRUE;
}
