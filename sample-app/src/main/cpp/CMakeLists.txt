cmake_minimum_required(VERSION 3.22.1)
project(sample CXX)
add_library(sample SHARED sample_native.cpp)
target_link_libraries(sample log)
