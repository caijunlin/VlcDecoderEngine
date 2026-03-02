package com.caijunlin.vlcdecoder.gles

import android.graphics.Bitmap
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import org.videolan.libvlc.LibVLC
import java.util.concurrent.ConcurrentHashMap

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   极速单线程核心调度池统筹管理所有播放流通过直接拔插渲染队列取代状态轮询机制
 */
object VlcRenderPool {

    private val thread = HandlerThread("VlcRenderPool").apply { start() }
    val handler = Handler(thread.looper)

    private val streams = ConcurrentHashMap<String, DecoderStream>()
    private val displayMap = ConcurrentHashMap<Surface, DisplayWindow>()
    private lateinit var eglCore: EglCore
    private var libVLC: LibVLC? = null
    private var isTicking = false
    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null

    @Volatile
    private var maxStreamLimit = 16

    init {
        handler.post {
            eglCore = EglCore()
            eglCore.initEGL()
            choreographer = Choreographer.getInstance()
            frameCallback = Choreographer.FrameCallback { doTick() }
        }
    }

    fun setLibVLC(vlc: LibVLC) {
        this.libVLC = vlc
    }

    fun setMaxStreamCount(maxCount: Int) {
        this.maxStreamLimit = maxCount
    }

    fun bindSurface(
        url: String,
        x5Surface: Surface,
        width: Int,
        height: Int,
        mediaOptions: ArrayList<String>
    ) {
        handler.post {
            handleBind(url, x5Surface, width, height, mediaOptions)
            startTicking()
        }
    }

    fun unbindSurface(url: String, x5Surface: Surface) {
        handler.post { handleUnbind(url, x5Surface) }
    }

    fun resizeSurface(x5Surface: Surface, width: Int, height: Int) {
        handler.post {
            displayMap[x5Surface]?.let {
                it.physicalW = width
                it.physicalH = height
            }
        }
    }

    /**
     * 将画布剥离出底层绘图队列中止算力消耗
     * @param url 关联的数据源
     * @param x5Surface 执行挂起的画布
     */
    fun suspendRender(url: String, x5Surface: Surface) {
        handler.post { handleSuspendRender(url, x5Surface) }
    }

    /**
     * 将画布重新插回底层绘图队列恢复视觉呈现
     * @param url 关联的数据源
     * @param x5Surface 唤醒投递的画布
     */
    fun resumeRender(url: String, x5Surface: Surface) {
        handler.post { handleResumeRender(url, x5Surface) }
    }

    fun pauseStream(url: String) {
        handler.post { streams[url]?.pause() }
    }

    fun resumeStream(url: String) {
        handler.post { streams[url]?.resume() }
    }

    fun captureFrame(x5Surface: Surface, callback: (Bitmap?) -> Unit) {
        handler.post { handleCaptureFrame(x5Surface, callback) }
    }

    fun releaseAll() {
        handler.post {
            streams.values.forEach { it.release() }
            displayMap.values.forEach { it.release(eglCore) }
            streams.clear()
            displayMap.clear()
            frameCallback?.let { choreographer?.removeFrameCallback(it) }
            eglCore.release()
        }
    }

    private fun handleBind(
        url: String,
        x5Surface: Surface,
        w: Int,
        h: Int,
        mediaOptions: ArrayList<String>
    ) {
        var stream = streams[url]
        if (stream == null) {
            if (streams.size >= maxStreamLimit) {
                Log.w("VLCDecoder", "Maximum stream limit reached new source rejected")
                return
            }
            stream = DecoderStream(url, eglCore, libVLC, handler, mediaOptions) { deadUrl ->
                handleStreamDead(deadUrl)
            }
            stream.start()
            streams[url] = stream
        }

        val window = DisplayWindow(x5Surface)
        window.initEGLSurface(eglCore)
        window.physicalW = w
        window.physicalH = h

        // 默认情况下新插入的组件既占据生命周期席位又直接投身工作队列
        stream.boundWindows.add(window)
        stream.activeWindows.add(window)
        displayMap[x5Surface] = window
    }

    private fun handleUnbind(url: String, x5Surface: Surface) {
        val window = displayMap.remove(x5Surface)
        window?.release(eglCore)

        val stream = streams[url]
        if (stream != null && window != null) {
            stream.boundWindows.remove(window)
            stream.activeWindows.remove(window)

            // 完美的生命周期判决
            if (stream.boundWindows.isEmpty()) {
                // 没有人牵挂它了直接超度销毁
                stream.release()
                streams.remove(url)
            } else if (stream.activeWindows.isEmpty()) {
                // 虽然还有人羁绊但没人睁眼看暂停节流
                stream.pause()
            }
        }
    }

    private fun handleSuspendRender(url: String, surface: Surface) {
        val window = displayMap[surface] ?: return
        val stream = streams[url] ?: return
        // 物理剥离该画板拔除工作队列
        if (stream.activeWindows.remove(window)) {
            if (stream.activeWindows.isEmpty()) {
                stream.pause()
            }
        }
    }

    private fun handleResumeRender(url: String, surface: Surface) {
        val window = displayMap[surface] ?: return
        val stream = streams[url] ?: return
        // 物理插入该画板接入工作队列
        if (!stream.activeWindows.contains(window)) {
            stream.activeWindows.add(window)
            if (stream.activeWindows.size == 1) {
                stream.resume()
            }
        }
    }

    private fun handleStreamDead(url: String) {
        val deadStream = streams.remove(url)
        if (deadStream != null) {
            deadStream.boundWindows.forEach { window ->
                val surfaceKey = displayMap.entries.find { it.value == window }?.key
                if (surfaceKey != null) {
                    displayMap.remove(surfaceKey)
                }
                window.release(eglCore)
            }
            deadStream.release()
        }
    }

    private fun handleCaptureFrame(surface: Surface, callback: (Bitmap?) -> Unit) {
        val window = displayMap[surface]
        val mainHandler = Handler(Looper.getMainLooper())
        if (window == null) {
            mainHandler.post { callback(null) }
            return
        }
        val targetStream = streams.values.find { it.boundWindows.contains(window) }
        if (targetStream == null || targetStream.fboId == -1) {
            mainHandler.post { callback(null) }
            return
        }
        eglCore.makeCurrentMain()
        val bmp = eglCore.readPixelsFromFBO(
            targetStream.fboId,
            targetStream.videoWidth,
            targetStream.videoHeight
        )
        mainHandler.post { callback(bmp) }
    }

    private fun startTicking() {
        if (!isTicking) {
            isTicking = true
            frameCallback?.let { choreographer?.postFrameCallback(it) }
        }
    }

    private fun doTick() {
        var hasActiveDraws = false
        var isDummyCurrent = false
        val streamsToRender = ArrayList<DecoderStream>()

        for (stream in streams.values) {
            // 热循环内没有任何逻辑判断纯粹检阅物理列队长度
            if (stream.activeWindows.isEmpty()) continue

            hasActiveDraws = true

            if (stream.frameAvailable) {
                if (!isDummyCurrent) {
                    eglCore.makeCurrentMain()
                    isDummyCurrent = true
                }
                try {
                    stream.surfaceTexture?.updateTexImage()
                    stream.surfaceTexture?.getTransformMatrix(stream.transformMatrix)
                    stream.frameAvailable = false
                    stream.hasFirstFrame = true

                    eglCore.drawOESToFBO(
                        stream.fboId,
                        stream.oesTextureId,
                        stream.transformMatrix,
                        stream.videoWidth,
                        stream.videoHeight
                    )
                    streamsToRender.add(stream)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 无脑分发没有 if (!window.isRenderable) 的污染
        for (stream in streamsToRender) {
            val windowCount = stream.activeWindows.size
            for (j in 0 until windowCount) {
                val window = stream.activeWindows[j]

                if (!eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) continue

                eglCore.setSwapInterval(0)

                val pw = window.physicalW
                val ph = window.physicalH
                if (pw > 0 && ph > 0) {
                    Matrix.setIdentityM(window.mvpMatrix, 0)
                    eglCore.drawTex2DScreen(stream.tex2DId, window.mvpMatrix, pw, ph)
                    eglCore.swapBuffers(window.eglSurface)
                }
            }
        }

        if (hasActiveDraws) {
            frameCallback?.let {
                choreographer?.removeFrameCallback(it)
                choreographer?.postFrameCallback(it)
            }
        } else {
            isTicking = false
            eglCore.makeCurrentMain()
        }
    }
}