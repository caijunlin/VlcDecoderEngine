package com.caijunlin.vlcdecoder

import com.caijunlin.vlcdecoder.core.FrameHub

object VlcBridge {
    init {
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("vlc")
            System.loadLibrary("vlc_bridge")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * 初始化全局 LibVLC
     * @param args 全局参数 (如 -vvv)
     * @return LibVLC 实例句柄 (C++ 指针)
     */
    external fun nativeCreateVLC(args: Array<String>): Long

    /**
     * 释放全局资源
     * 停止所有流，销毁 LibVLC 实例
     */
    external fun nativeReleaseVLC(handle: Long)

    /**
     * 开启指定 URL 的播流
     * @param handle 全局 LibVLC 句柄
     * @param url 播放地址
     * @param hub 该 URL 对应的数据回调中心
     * @param width 解码宽度 (用于优化性能)
     * @param height 解码高度
     * @param args 媒体特定参数 (如 :rtsp-tcp)
     * @return 是否开启成功
     */
    external fun nativeStart(
        handle: Long,
        url: String,
        hub: FrameHub,
        width: Int,
        height: Int,
        args: Array<String>
    ): Boolean

    /**
     * 停止并释放指定 URL 的流
     * @param handle 全局 LibVLC 句柄
     * @param url 播放地址 (用于查找对应的 PlayerContext)
     * @return 是否成功
     */
    external fun nativeReleaseMedia(handle: Long, url: String): Boolean
}