package com.caijunlin.vlcdecoder.core

import java.nio.ByteBuffer

/**
 * @author : caijunlin
 * @date   : 2026/2/11
 * @description   :
 */
interface IRenderer {
    // 核心方法：接收一帧数据
    fun updateFrame(data: ByteBuffer, width: Int, height: Int)

    // 释放资源
    fun release()
}