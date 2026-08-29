cmake_minimum_required(VERSION 3.31.6)

project("s1-polish" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
else()
    message(FATAL_ERROR "S1 polish supports arm64-v8a only")
endif()

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_SRC}" build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED s1_jni.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
    GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
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
