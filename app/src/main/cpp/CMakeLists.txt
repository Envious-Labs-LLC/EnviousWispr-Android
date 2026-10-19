cmake_minimum_required(VERSION 3.31.6)
project(enviouswispr_geniex_log LANGUAGES CXX)

add_library(enviouswispr-geniex-log SHARED geniex_log_silencer.cpp)
target_link_libraries(enviouswispr-geniex-log PRIVATE dl)
target_link_options(enviouswispr-geniex-log PRIVATE "-Wl,-z,max-page-size=16384")

# #449: answers "can this app open the vendor OpenCL library" without loading anything of GenieX's, so the runtime
# choice can be made before GenieX's own plugin (which needs libOpenCL.so) is ever loaded.
add_library(enviouswispr-capability SHARED capability_probe.cpp)
target_link_libraries(enviouswispr-capability PRIVATE dl)
target_link_options(enviouswispr-capability PRIVATE "-Wl,-z,max-page-size=16384")
