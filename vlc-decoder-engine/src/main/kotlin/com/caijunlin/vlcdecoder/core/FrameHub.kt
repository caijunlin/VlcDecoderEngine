package com.caijunlin.vlcdecoder.core

import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 数据中转站 (升级版)
 * 支持 1 对 N 分发：一个 VLC 数据源 -> 多个 GLRenderer
 */
class FrameHub {
    // 改为持有接口列表
    private val renderers = CopyOnWriteArrayList<IRenderer>()

    fun addRenderer(renderer: IRenderer) {
        if (!renderers.contains(renderer)) {
            renderers.add(renderer)
        }
    }

    fun removeRenderer(renderer: IRenderer) {
        renderers.remove(renderer)
    }

    fun onRawFrame(data: ByteBuffer, width: Int, height: Int) {
        for (renderer in renderers) {
            renderer.updateFrame(data, width, height)
        }
    }
}