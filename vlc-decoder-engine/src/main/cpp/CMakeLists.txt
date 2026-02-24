cmake_minimum_required(VERSION 3.22.1)
project(vlc_hyper_engine)

# CMAKE_CURRENT_SOURCE_DIR 就是 src/main/cpp 目录
set(ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# 引入头文件目录，确保编译时能找到 vlc/*.h 等声明文件
include_directories(${ROOT_DIR})

# 查找 Android 系统自带的日志库，用于在 Native 层打印 Logcat
find_library(log-lib log)

# Egl30 必须链入 EGL 和 GLESv3
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)

# 定义导入库 libvlc
# 这里声明我们要使用一个外部的共享库，而不是从源码编译它
add_library(libvlc SHARED IMPORTED)

# 设置 libvlc 的实际文件路径
# ANDROID_ABI 是 CMake 内置变量（如 arm64-v8a），用于自动适配不同架构
set_target_properties(libvlc PROPERTIES
        IMPORTED_LOCATION "${ROOT_DIR}/libs/${ANDROID_ABI}/libvlc.so"
)

# 编译我们将要编写的 JNI 桥接库 vlc_bridge
# 这是我们自己写的 C++ 代码，编译为 libvlc_bridge.so
add_library(vlc_bridge SHARED vlc_bridge.cpp)

# 链接库依赖
# 将 Android 日志库和 VLC 核心库链接到我们的 vlc_bridge 中
# 这样我们的代码才能调用 libvlc 的函数和 __android_log_print
target_link_libraries(
        vlc_bridge
        libvlc
        ${log-lib}
)

target_link_options(vlc_bridge PRIVATE "-Wl,-z,max-page-size=16384")