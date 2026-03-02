package com.caijunlin.vlcdecoder.core

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import com.caijunlin.vlcdecoder.gles.VlcRenderPool
import org.videolan.libvlc.LibVLC

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   全局的 VLC 流媒体管理器负责统一初始化 LibVLC 引擎、代理底层渲染池的绑定与解绑操作，并提供动态参数配置能力。作为对外的唯一 API 门面。
 */
object VlcStreamManager {

    // 全局唯一的 LibVLC 实例，作为底层 C++ 解码引擎的入口
    private var libVLC: LibVLC? = null

    // VLC 引擎的底层缺省初始化参数列表，包含网络缓存、禁用音频、硬件解码等针对视频流的极速优化配置
    private val defaultVlcArgs = arrayListOf(
        "--no-audio",
        "--aout=dummy",
        "--rtsp-tcp",
        "--network-caching=600",
        "--drop-late-frames",
        "--skip-frames",
        "--codec=mediacodec,all"
    )

    // 单条视频流缺省的媒体控制参数，默认开启循环播放及基础网络缓存
    private val defaultMediaArgs = arrayListOf(
        ":network-caching=300",
        ":input-repeat=65535"
    )

    /**
     * 初始化全局的 LibVLC 引擎并将其注入到底层渲染池中。
     * @param context 应用上下文对象，用于引擎获取系统硬件信息
     * @param args 自定义的 VLC 底层初始化参数列表，若未传则使用内置的极速缺省参数
     */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, args: ArrayList<String> = defaultVlcArgs) {
        if (libVLC == null) {
            libVLC = LibVLC(context.applicationContext, args)
            VlcRenderPool.setLibVLC(libVLC!!)
        }
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
     * 将指定的视频流地址绑定到外部传入的显示画布上进行渲染。如解码器未启动则自动拉起。
     * @param url 目标视频流的网络地址 (支持 RTSP/HTTP/M3U8 等)
     * @param x5Surface 外部组件（如 X5 WebView）提供的目标渲染画布
     * @param width 画布的初始物理宽度
     * @param height 画布的初始物理高度
     * @param mediaOptions 针对当前视频流的自定义配置参数，若未传则使用缺省网络配置
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun bind(
        url: String,
        x5Surface: Surface,
        width: Int,
        height: Int,
        mediaOptions: ArrayList<String> = defaultMediaArgs
    ) {
        if (url.isEmpty() || libVLC == null) return
        VlcRenderPool.bindSurface(url, x5Surface, width, height, mediaOptions)
    }

    /**
     * 更新指定画布的物理尺寸参数，以适配外部组件（网页节点）的缩放或大小变化。
     * @param x5Surface 需要更新尺寸的目标画布
     * @param width 新的物理宽度
     * @param height 新的物理高度
     */
    @JvmStatic
    fun resizeSurface(x5Surface: Surface, width: Int, height: Int) {
        VlcRenderPool.resizeSurface(x5Surface, width, height)
    }

    /**
     * 解除指定视频流与目标画布的绑定关系。若底层判定该流已无任何画布依附，将自动触发防抖回收机制。
     * @param url 需要解绑的视频流地址
     * @param x5Surface 需要剥离的目标画布
     */
    @JvmStatic
    @Synchronized
    fun unbind(url: String, x5Surface: Surface) {
        VlcRenderPool.unbindSurface(url, x5Surface)
    }

    /**
     * 截取指定显示表面上当前渲染的最新一帧画面，并通过 FBO 零拷贝技术转化为安卓位图。
     * @param x5Surface 需要提取画面的目标显示表面
     * @param callback 接收提取位图的异步回调函数，若提取失败将返回 null
     */
    @JvmStatic
    fun captureFrame(x5Surface: Surface, callback: (Bitmap?) -> Unit) {
        VlcRenderPool.captureFrame(x5Surface, callback)
    }

    /**
     * 诊断探针：命令底层渲染池在日志控制台中打印出当前的内存挂载树与解码器工作状态。
     */
    @JvmStatic
    fun printSystemDiagnostics() {
        VlcRenderPool.printDiagnostics()
    }

    /**
     * 终极核平指令：释放所有的渲染资源、销毁 EGL 环境以及底层的 LibVLC 引擎实例。通常在 App 退出时调用。
     */
    @JvmStatic
    fun releaseAll() {
        VlcRenderPool.releaseAll()
        libVLC?.release()
        libVLC = null
    }
}