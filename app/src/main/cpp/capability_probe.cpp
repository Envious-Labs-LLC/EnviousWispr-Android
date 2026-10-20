#include <dlfcn.h>
#include <jni.h>
#include <sys/auxv.h>

// #449: GenieX's llama plugin links libggml-opencl.so, which links libOpenCL.so, so GenieX cannot start (not even
// on the processor) on a phone whose vendor does not offer that library to apps. This opens it in the app's own
// linker namespace, the one GenieX's loader uses, and closes it again. It touches nothing of GenieX's.
// The processor's feature words straight from the kernel's auxiliary vector (#449). A release build cannot read its
// own /proc/self/auxv: the process is not dumpable, so the kernel gives that file to root. getauxval reads the copy
// the process already holds in memory, which is what the Android NDK recommends. Returns {AT_HWCAP, AT_HWCAP2};
// AT_HWCAP is 0 only when the kernel did not supply it.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_envi_wispr_polish_CpuFeatures_00024Companion_nativeHwcaps(JNIEnv* env, jobject) {
    jlong values[2] = {static_cast<jlong>(getauxval(AT_HWCAP)), static_cast<jlong>(getauxval(AT_HWCAP2))};
    jlongArray result = env->NewLongArray(2);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

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
