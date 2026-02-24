package com.caijunlin.vlcdecoder.core

import android.view.Surface
import com.caijunlin.vlcdecoder.VlcBridge
import com.caijunlin.vlcdecoder.gles.RendererFactory
import java.util.concurrent.ConcurrentHashMap

class StreamSession(
    vlcHandle: Long,
    url: String,
    initialW: Int,
    initialH: Int,
    args: Array<String>
) {
    // 每一个 URL 对应一个 FrameHub
    val frameHub = FrameHub()

    private val renderers = ConcurrentHashMap<Surface, IRenderer>()

    init {
        VlcBridge.nativeStart(vlcHandle, url, frameHub, initialW, initialH, args)
    }

    fun attachSurface(surface: Surface, renderApi: RenderApi) {
        if (renderers.containsKey(surface)) return
        val renderer = RendererFactory.createRenderer(surface, renderApi)
        renderers[surface] = renderer
        frameHub.addRenderer(renderer)
    }

    fun detachSurface(surface: Surface): Boolean {
        val renderer = renderers.remove(surface) ?: return renderers.isEmpty()
        frameHub.removeRenderer(renderer)
        renderer.release()
        return renderers.isEmpty()
    }
}