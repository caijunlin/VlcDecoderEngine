package com.caijunlin.vlcdecoder.gles

import android.view.Surface
import com.caijunlin.vlcdecoder.core.RenderApi
import com.caijunlin.vlcdecoder.core.IRenderer

internal object RendererFactory {

    fun createRenderer(surface: Surface, api: RenderApi): IRenderer {
        return when (api) {
            RenderApi.GLES_30 -> Egl30SurfaceRenderer(surface)
            RenderApi.GLES_20 -> Egl20SurfaceRenderer(surface)
            RenderApi.AUTO -> {
                if (isGles30Supported()) {
                    Egl30SurfaceRenderer(surface)
                } else {
                    Egl20SurfaceRenderer(surface)
                }
            }
        }
    }

    private fun isGles30Supported(): Boolean {
        return false
    }
}