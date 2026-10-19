cmake_minimum_required(VERSION 3.31.6)

project("s1-polish" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    # OFF since #449: the app also packages Qualcomm GenieX, which ships its own libomp.so. ggml's own thread pool
    # replaces OpenMP, so no second libomp.so lands in the APK.
    set(GGML_OPENMP OFF)
else()
    message(FATAL_ERROR "S1 polish supports arm64-v8a only")
endif()

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_SRC}" build-llama)

# #449: the app APK also carries GenieX, which ships libllama.so, libllama-common.so, libggml.so and libggml-base.so
# under the same names. Rename ours (the targets keep their names, so every link follows the new SONAMEs). The CPU
# variant modules (libggml-cpu-android_*.so) do not clash and keep their names; s1_jni.cpp loads only those.
set_target_properties(llama PROPERTIES OUTPUT_NAME "ew-llama")
set_target_properties(llama-common PROPERTIES OUTPUT_NAME "ew-llama-common")
set_target_properties(ggml PROPERTIES OUTPUT_NAME "ew-ggml")
set_target_properties(ggml-base PROPERTIES OUTPUT_NAME "ew-ggml-base")

add_library(${CMAKE_PROJECT_NAME} SHARED s1_jni.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src"
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    llama
    llama-common
    android
    log
)
