package com.caijunlin.vlcdecoder

import android.app.Application
import android.content.Context
import com.caijunlin.vlcdecoder.callback.KernelInitCallback
import com.caijunlin.vlcdecoder.core.KernelManager
import com.caijunlin.vlcdecoder.gles.VlcRenderPool
import com.caijunlin.vlcdecoder.widget.WidgetManager

/**
 * @author : caijunlin
 * @date   : 2026/3/5
 * @description   : 对外暴露的函数
 */
object StreamKit {

    /**
     * 初始化
     * @param application 应用上下文对象，用于引擎获取系统硬件信息
     * @param authCode X5浏览器内核授权码
     */
    @JvmStatic
    fun init(application: Application, authCode: String) {
        KernelManager.init(application, authCode)
    }

    @JvmStatic
    fun registerCallback(callback: KernelInitCallback) {
        KernelManager.registerCallback(callback)
    }

    /**
     * 设置渲染引擎允许同时解析的最大视频流数量，超出此限制的新流将被拒绝，防止榨干硬件算力。
     * @param maxCount 允许并发解析的最大流数量（默认 16）
     */
    @JvmStatic
    fun setMaxStreamCount(maxCount: Int) {
        VlcRenderPool.setMaxStreamCount(maxCount)
    }

    /**
     * 终极核平指令：释放所有的渲染资源、销毁 EGL 环境以及底层的 LibVLC 引擎实例。通常在 App 退出时调用。
     */
    @JvmStatic
    fun releaseAll(context: Context) {
        KernelManager.release(context)
        WidgetManager.clearAll()
        VlcRenderPool.releaseAll()
    }

}