# Specifies the minimum version of CMake required.
cmake_minimum_required(VERSION 3.18.1)

# Declares the project name.
project("super_video_downloader")

# --- FIX: Add the include directory for header files ---
# This tells the C compiler where to find "libproxychain.h".
# ${ANDROID_ABI} ensures it looks in the correct architecture folder (e.g., x86_64, arm64-v8a).
include_directories(${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# Add the directory containing your pre-built libraries to the linker's search path.
# This tells the linker where to find "libproxychain.so".
link_directories(${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# Find the standard Android log library so we can use __android_log_print, etc.
find_library(
        log-lib
        log)

# Defines a shared library target named 'proxychain_jni' from the specified C source file.
add_library(
        proxychain_jni
        SHARED
        proxychain_jni.c)

# Link all the necessary libraries to our 'proxychain_jni' target.
target_link_libraries(
        proxychain_jni
        # Link against the Android log library we found.
        ${log-lib}
        # Link against your pre-built Go library. This corresponds to libproxychain.so.
        proxychain)

# Add linker flag for 16 KB page size support on 64-bit architectures.
# This is required for Android 15 (API 35) and newer.
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    target_link_options(proxychain_jni PRIVATE "-Wl,-z,max-page-size=16384")
endif()
