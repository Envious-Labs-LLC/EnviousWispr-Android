cmake_minimum_required(VERSION 3.31.6)
project(enviouswispr_geniex_log LANGUAGES CXX)

add_library(enviouswispr-geniex-log SHARED geniex_log_silencer.cpp)
target_link_libraries(enviouswispr-geniex-log PRIVATE dl)
