cmake_minimum_required(VERSION 3.22.1)
project(nativedata)

add_library(nativedata SHARED
  native-lib.cpp
)

target_link_libraries(nativedata
  android
  log
)