package com.caijunlin.vlcdecoder.core

import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.caijunlin.vlcdecoder.VlcBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局流管理器
 * 持有 C++ 全局句柄，管理 Java 层 Session
 */
object VlcStreamManager {

    private val sessions = ConcurrentHashMap<String, StreamSession>()

    // 全局 LibVLC 句柄
    private var globalVlcHandle: Long = 0

    private val startupThread = HandlerThread("VCLStartupThread").apply { start() }
    private val startupHandler = Handler(startupThread.looper)

    // 延时启动
    private const val STARTUP_DELAY_MS = 500L

    // 全局参数 (传给 nativeCreateVLC)
    private val vlcArgs = listOf(
        "--no-audio",           // 关音频
        "--vout=none",          // 关默认窗口
        "--aout=none",          // 关音频输出模块
        "--rtsp-tcp",           // 强制 TCP：防止 UDP 丢包导致 PCR 丢失 (这是时间戳错误的根源)
        "--network-caching=600", // 增加缓存到 600ms，给数据包一点迟到的宽容度
        "--clock-jitter=0",     // 禁用抖动控制：即使包来晚了，也别丢，赶紧播
        "--clock-synchro=0",    // 禁用时钟同步：不尝试对齐系统时间，有数据就解
        "--avcodec-hw=none",    // 强制软解 (20路必须软解，硬解也会导致时间戳同步问题)
//        "--avcodec-threads=1",  // 限制每个解码器只用 1 个线程 (20路就是20线程)，防止 CPU 抢占导致延迟
        // 只有当积压严重时才丢帧，平时尽量保留
        "--drop-late-frames",
        "--skip-frames",
//        "-vvv"
    )

    // 媒体参数 (传给 nativeStart)
    private val mediaArgs = arrayOf(
        ":network-caching=300",
        ":clock-jitter=0",
        ":clock-synchro=0",
        ":input-repeat=65535", // 循环播放
    )

    fun startStream() {
        // 只需要调用一次初始化
        globalVlcHandle = VlcBridge.nativeCreateVLC(vlcArgs.toTypedArray())
    }

    @Synchronized
    fun bind(
        url: String, surface: Surface,
        renderApi: RenderApi = RenderApi.AUTO // 默认提供 AUTO
        , width: Int = 0, height: Int = 0
    ) {
        if (url.isEmpty() || globalVlcHandle == 0L) return
        startupHandler.postDelayed({
            executeBind(url, surface, renderApi, width, height)
        }, STARTUP_DELAY_MS)
    }

    // 真正的绑定逻辑 (原 bind 方法的内容移动到这里)
    private fun executeBind(
        url: String, surface: Surface,
        renderApi: RenderApi, width: Int, height: Int
    ) {
        // 双重检查，防止排队期间页面已经销毁了
        if (!surface.isValid) return
        val session = sessions.getOrPut(url) {
            StreamSession(globalVlcHandle, url, width, height, mediaArgs)
        }
        session.attachSurface(surface, renderApi)
    }

    @Synchronized
    fun unbind(url: String, surface: Surface) {
        val session = sessions[url] ?: return
        // 每关闭一个 X5 Surface，减去计数器
        val isNoSurfaceLeft = session.detachSurface(surface)
        // 当列表中的 Surface 为 0 时
        if (isNoSurfaceLeft) {
            // 调用 nativeReleaseMedia
            val success = VlcBridge.nativeReleaseMedia(globalVlcHandle, url)
            if (success) {
                sessions.remove(url)
            }
        }
    }

    // App 退出时调用
    fun releaseAll() {
        startupHandler.removeCallbacksAndMessages(null) // 清空排队任务
        startupThread.quitSafely()
        if (globalVlcHandle != 0L) {
            VlcBridge.nativeReleaseVLC(globalVlcHandle)
            globalVlcHandle = 0
            sessions.clear()
        }
    }
}