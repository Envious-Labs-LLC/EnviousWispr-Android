cmake_minimum_required(VERSION 3.31.6)

project("enviouswispr-mnn" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "EG-1 Micro on MNN supports arm64-v8a only")
endif()

# Google Play's 16 KB page-size rule; `scripts/release/publish.py` checks it at release.
string(APPEND CMAKE_SHARED_LINKER_FLAGS " -Wl,-z,max-page-size=16384")

# The engine EG-1 Micro needs, and nothing else (#531 plan §10). MNN_USE_LOGCAT stays OFF so MNN's prints never reach
# the phone's log; LLM_SUPPORT_HTTP_RESOURCE stays OFF so the library carries no download client (plan P10).
set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM_OMNI OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_ARM82 ON CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DIFFUSION OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_AUDIO OFF CACHE BOOL "" FORCE)
set(MNN_JNI OFF CACHE BOOL "" FORCE)
set(MNN_USE_SSE OFF CACHE BOOL "" FORCE)
# ON: the Gradle build owns output paths and headers; OFF makes MNN copy headers in POST_BUILD steps CMake rejects
# on its OBJECT libraries and redirect library output (third_party/MNN/CMakeLists.txt, transformers/llm/engine/CMakeLists.txt).
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(LLM_SUPPORT_HTTP_RESOURCE OFF CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(LLM_LOG_TO_STRING OFF CACHE BOOL "" FORCE)

set(MNN_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/MNN")
add_subdirectory("${MNN_SRC}" build-mnn)

add_library(${CMAKE_PROJECT_NAME} SHARED mnn_jni.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    "${MNN_SRC}/include"
    "${MNN_SRC}/transformers/llm/engine/include"
)

target_link_libraries(${CMAKE_PROJECT_NAME} MNN android)
