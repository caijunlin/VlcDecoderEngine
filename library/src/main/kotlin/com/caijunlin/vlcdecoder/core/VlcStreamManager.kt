package com.caijunlin.vlcdecoder.core

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import com.caijunlin.vlcdecoder.gles.VlcRenderPool
import org.videolan.libvlc.LibVLC

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   全局的 VLC 流媒体管理器负责统一初始化 LibVLC 引擎代理底层渲染池的绑定解绑以及提供动态参数配置能力
 */
object VlcStreamManager {

    // 全局唯一的 LibVLC 实例用于创建媒体播放器
    private var libVLC: LibVLC? = null

    // VLC 引擎的底层缺省初始化参数列表包含网络缓存硬件解码等配置
    private val defaultVlcArgs = arrayListOf(
        "--no-audio",
        "--aout=dummy",
        "--rtsp-tcp",
        "--network-caching=600",
        "--drop-late-frames",
        "--skip-frames",
        "--codec=mediacodec,all"
    )

    // 单条视频流缺省的媒体控制参数
    private val defaultMediaArgs = arrayListOf(
        ":network-caching=300",
        ":input-repeat=65535"
    )

    /**
     * 初始化全局的 LibVLC 引擎并将其注入到底层渲染池中支持自定义配置参数
     * @param context 应用上下文对象
     * @param args 自定义的VLC底层初始化参数列表若为空则使用内置缺省参数
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
     * 设置渲染引擎允许同时解析的最大视频流数量超出此限制的新流将被直接拒绝
     * @param maxCount 允许并发解析的最大流数量
     */
    @JvmStatic
    fun setMaxStreamCount(maxCount: Int) {
        VlcRenderPool.setMaxStreamCount(maxCount)
    }

    /**
     * 将指定的视频流地址绑定到外部传入的显示画布上进行渲染支持自定义媒体播放参数
     * @param url 目标视频流的网络地址
     * @param x5Surface 外部组件提供的目标渲染画布
     * @param width 画布的初始物理宽度
     * @param height 画布的初始物理高度
     * @param mediaOptions 针对当前视频流的自定义配置参数若未传则使用缺省网络配置
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun bind(url: String, x5Surface: Surface, width: Int, height: Int, mediaOptions: ArrayList<String> = defaultMediaArgs) {
        if (url.isEmpty() || libVLC == null) return
        VlcRenderPool.bindSurface(url, x5Surface, width, height, mediaOptions)
    }

    /**
     * 更新指定画布的物理尺寸参数以适配外部组件的大小变化
     * @param x5Surface 需要更新尺寸的目标画布
     * @param width 新的物理宽度
     * @param height 新的物理高度
     */
    @JvmStatic
    fun resizeSurface(x5Surface: Surface, width: Int, height: Int) {
        VlcRenderPool.resizeSurface(x5Surface, width, height)
    }

    /**
     * 设置指定画布的渲染许可标记并触发智能联动休眠与唤醒所属解码引擎
     * @param url 目标视频流的网络地址
     * @param x5Surface 发生状态变更的目标画布
     */
    @JvmStatic
    fun suspendRender(url: String, x5Surface: Surface) {
        VlcRenderPool.suspendRender(url, x5Surface)
    }

    @JvmStatic
    fun resumeRender(url: String, x5Surface: Surface) {
        VlcRenderPool.resumeRender(url, x5Surface)
    }

    /**
     * 解除指定视频流与目标画布的绑定关系停止在该画布上的渲染如果流对象空载则销毁
     * @param url 正在播放的视频流地址
     * @param x5Surface 需要解绑的目标画布
     */
    @JvmStatic
    @Synchronized
    fun unbind(url: String, x5Surface: Surface) {
        VlcRenderPool.unbindSurface(url, x5Surface)
    }

    /**
     * 暂停指定视频流的解码与渲染工作保留内存和表面对象以便后续快速恢复
     * @param url 需要暂停的目标视频流地址
     */
    @JvmStatic
    fun pauseStream(url: String) {
        VlcRenderPool.pauseStream(url)
    }

    /**
     * 恢复指定视频流的解码与渲染工作
     * @param url 需要恢复的目标视频流地址
     */
    @JvmStatic
    fun resumeStream(url: String) {
        VlcRenderPool.resumeStream(url)
    }

    /**
     * 截取指定显示表面上当前渲染的最新一帧画面并转化为安卓位图供界面使用
     * @param x5Surface 需要提取画面的目标显示表面
     * @param callback 接收提取位图的异步回调函数若提取失败将返回空值
     */
    @JvmStatic
    fun captureFrame(x5Surface: Surface, callback: (Bitmap?) -> Unit) {
        VlcRenderPool.captureFrame(x5Surface, callback)
    }

    /**
     * 释放所有的渲染资源以及底层的 LibVLC 引擎实例
     */
    @JvmStatic
    fun releaseAll() {
        VlcRenderPool.releaseAll()
        libVLC?.release()
        libVLC = null
    }
}