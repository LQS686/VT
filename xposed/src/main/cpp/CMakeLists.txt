cmake_minimum_required(VERSION 3.28)
project(appcore C CXX)

find_package(dobby REQUIRED CONFIG)

add_library(appcore SHARED
        vt_xposed.cpp
        xz/xz_dec_stream.c
        xz/xz_dec_lzma2.c
        xz/xz_dec_bcj.c
        xz/xz_crc32.c)

target_compile_features(appcore PRIVATE cxx_std_20)
target_include_directories(appcore PRIVATE xz)
target_compile_definitions(appcore PRIVATE
        XZ_DEC_ANY_CHECK
        XZ_DEC_X86
        XZ_DEC_ARM
        XZ_DEC_ARMTHUMB
        XZ_DEC_ARM64
        XZ_DEC_RISCV
        XZ_DEC_POWERPC
        XZ_DEC_IA64
        XZ_DEC_SPARC)
set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/lsplant lsplant-out)

target_link_libraries(appcore PRIVATE lsplant_static dobby::dobby android log dl)
target_link_options(appcore PRIVATE "-Wl,-z,max-page-size=16384")
