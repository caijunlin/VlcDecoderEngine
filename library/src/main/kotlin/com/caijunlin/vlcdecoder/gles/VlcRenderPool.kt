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
 * @description   极速单线程核心调度池。采用指令投递模式保障线程安全与类型纯净；独创“500ms 延时销毁防抖机制”完美化解前端 DOM 高频重排与 HTTP 流解析慢导致的 EGL_BAD_SURFACE 异步冲突。
 */
object VlcRenderPool {

    // 维持底层 OpenGL 引擎生命周期与渲染脉冲的守护线程实例
    private val thread = HandlerThread("VlcRenderPool").apply { start() }

    // 指令投递通道，所有外部行为必须封装入 post 闭包内由渲染线程接管执行
    val handler = Handler(thread.looper)

    // 活跃解码器字典，以视频流 URL 为键，集中管理当前正在运作的流
    private val streams = ConcurrentHashMap<String, DecoderStream>()

    // 显示画布字典，以安卓原生 Surface 为键，记录其物理尺寸及矩阵信息
    private val displayMap = ConcurrentHashMap<Surface, DisplayWindow>()

    // 延时销毁任务字典。记录那些刚失去最后一块画板、正处于“留队查看”死缓期的流对象
    private val pendingReleaseTasks = ConcurrentHashMap<String, Runnable>()

    // 统领全盘执行所有底层硬件调用和数据操作计算的唯一图形引擎环境核心
    private lateinit var eglCore: EglCore

    // 从管理器透传进来的解码器工厂实例
    private var libVLC: LibVLC? = null

    // 脉冲运转指示器，防止对系统 VSYNC 钩子进行重复注册
    private var isTicking = false

    // 获取并锚定操作硬件级真实刷新帧周期的编排器钩子句柄
    private var choreographer: Choreographer? = null

    // 向系统注册 VSYNC 回调以用于承接每次跳动画图任务的触发目标
    private var frameCallback: Choreographer.FrameCallback? = null

    // 系统并发安全上限，防止同时拉起过多解码器导致 GPU 显存溢出或宕机
    @Volatile
    private var maxStreamLimit = 16

    // 定义防抖缓冲期（单位：毫秒）。强力对抗 HTTP/M3U8 等复杂流的解析高延迟，以及防御前端可见性样式的高频抽风切换
    private const val RELEASE_DELAY_MS = 500L

    init {
        // 在专属的渲染线程内建立 OpenGL 根环境上下文
        handler.post {
            eglCore = EglCore()
            eglCore.initEGL()
            choreographer = Choreographer.getInstance()
            frameCallback = Choreographer.FrameCallback { doTick() }
        }
    }

    /** 装载全局工厂实例 */
    fun setLibVLC(vlc: LibVLC) {
        this.libVLC = vlc
    }

    /** 动态修改流并发安全上限阈值 */
    fun setMaxStreamCount(maxCount: Int) {
        this.maxStreamLimit = maxCount
    }

    /** 将前端画布压入执行队列与网络源构成羁绊呈现关系 */
    fun bindSurface(
        url: String, x5Surface: Surface, width: Int, height: Int, mediaOptions: ArrayList<String>?
    ) {
        handler.post {
            handleBind(url, x5Surface, width, height, mediaOptions)
            startTicking()
        }
    }

    /** 拔除指定显示画布，剥离引用链接 */
    fun unbindSurface(url: String, x5Surface: Surface) {
        handler.post { handleUnbind(url, x5Surface) }
    }

    /** 响应外部尺寸变化，重新标定上屏矩阵界限 */
    fun resizeSurface(x5Surface: Surface, width: Int, height: Int) {
        handler.post {
            displayMap[x5Surface]?.let {
                it.physicalW = width
                it.physicalH = height
            }
        }
    }

    /** 异步非阻塞截取指定活动表面的净显像数据供前端使用 */
    fun captureFrame(x5Surface: Surface, callback: (Bitmap?) -> Unit) {
        handler.post { handleCaptureFrame(x5Surface, callback) }
    }

    /** 打印全息诊断树，用于排查内存与调度关系 */
    fun printDiagnostics() {
        handler.post {
            Log.w("VLCDecoder", "================ ENGINE DIAGNOSTICS ================")
            Log.w("VLCDecoder", "Total Active Decoders: ${streams.size} / $maxStreamLimit")
            var index = 1
            streams.forEach { (url, stream) ->
                Log.i("VLCDecoder", "[$index] Stream URL: $url")
                Log.i("VLCDecoder", "    |- Is Decoding : ${stream.isDecoding}")
                Log.i("VLCDecoder", "    |- Active Surfaces: ${stream.displayWindows.size}")
                val isPending = pendingReleaseTasks.containsKey(url)
                Log.i("VLCDecoder", "    |- Is Pending Release: $isPending")
                stream.displayWindows.forEachIndexed { winIndex, window ->
                    val surfaceHex = Integer.toHexString(window.x5Surface.hashCode())
                    Log.i(
                        "VLCDecoder",
                        "       |- Surface_$winIndex @$surfaceHex -> Size: ${window.physicalW}x${window.physicalH}"
                    )
                }
                index++
            }
            Log.w("VLCDecoder", "====================================================")
        }
    }

    /** 执行终极核平，排空系统释放一切内存 */
    fun releaseAll() {
        handler.post {
            // 清理所有待定任务，拦截未爆的延时炸弹
            pendingReleaseTasks.values.forEach { handler.removeCallbacks(it) }
            pendingReleaseTasks.clear()

            streams.values.forEach { it.release() }
            displayMap.values.forEach { it.release(eglCore) }
            streams.clear()
            displayMap.clear()
            frameCallback?.let { choreographer?.removeFrameCallback(it) }
            eglCore.release()
        }
    }

    /**
     * 内部绑定逻辑：拦截重复绑定、执行防抖复活、并完成画板的 EGL 上下文组装
     */
    private fun handleBind(
        url: String, x5Surface: Surface, w: Int, h: Int, mediaOptions: ArrayList<String>?
    ) {
        // 第一道防线：拦截前端组件狂躁触发时的冗余绑定
        if (displayMap.containsKey(x5Surface)) return

        // 如果在延时处决期内，该 URL 对应的画板又被新建插入，立刻撤销死刑令，满血复活！
        pendingReleaseTasks.remove(url)?.let { task ->
            handler.removeCallbacks(task)
            Log.i("VLCDecoder", "Stream recovered from pending release: $url")
        }

        var stream = streams[url]
        if (stream == null) {
            // 触碰并发红线，直接拒绝受理以保护底层稳定
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

        stream.displayWindows.add(window)
        displayMap[x5Surface] = window
    }

    /**
     * 内部解绑逻辑：剥离目标视窗，判断流对象的孤立状态，并精准派发延时销毁指令
     */
    private fun handleUnbind(url: String, x5Surface: Surface) {
        val window = displayMap.remove(x5Surface)
        window?.release(eglCore)

        val stream = streams[url]
        if (stream != null && window != null) {
            stream.displayWindows.remove(window)

            // 所有视窗均断开时并不立刻斩杀，而是进入设定的延时观察期
            if (stream.displayWindows.isEmpty() && !pendingReleaseTasks.containsKey(url)) {
                val releaseTask = Runnable {
                    // 闭包执行时：再次严谨确认是否真的没有任何画板需要它
                    val finalStream = streams[url]
                    if (finalStream != null && finalStream.displayWindows.isEmpty()) {
                        Log.i("VLCDecoder", "Stream completely abandoned executing release: $url")
                        finalStream.release()
                        streams.remove(url)
                    }
                    pendingReleaseTasks.remove(url)
                }
                pendingReleaseTasks[url] = releaseTask
                handler.postDelayed(releaseTask, RELEASE_DELAY_MS)
            }
        }
    }

    /**
     * 处理经过多次抢救依然未能复活的废弃流，将其物理清退
     */
    private fun handleStreamDead(url: String) {
        val deadStream = streams.remove(url)
        if (deadStream != null) {
            // 拔除死缓倒计时
            pendingReleaseTasks.remove(url)?.let { handler.removeCallbacks(it) }

            deadStream.displayWindows.forEach { window ->
                val surfaceKey = displayMap.entries.find { it.value == window }?.key
                if (surfaceKey != null) displayMap.remove(surfaceKey)
                window.release(eglCore)
            }
            deadStream.release()
        }
    }

    /**
     * 截获特定内部 FBO 的纯净画面数据，通过线程安全通道推至主 UI
     */
    private fun handleCaptureFrame(surface: Surface, callback: (Bitmap?) -> Unit) {
        val window = displayMap[surface]
        val mainHandler = Handler(Looper.getMainLooper())
        if (window == null) {
            mainHandler.post { callback(null) }
            return
        }
        val targetStream = streams.values.find { it.displayWindows.contains(window) }
        if (targetStream == null || targetStream.fboId == -1) {
            mainHandler.post { callback(null) }
            return
        }

        // 挂载总环境作画
        eglCore.makeCurrentMain()
        val bmp = eglCore.readPixelsFromFBO(
            targetStream.fboId, targetStream.videoWidth, targetStream.videoHeight
        )
        mainHandler.post { callback(bmp) }
    }

    /**
     * 发出并启用硬件脉冲监听，激活作画主循环
     */
    private fun startTicking() {
        if (!isTicking) {
            isTicking = true
            frameCallback?.let { choreographer?.postFrameCallback(it) }
        }
    }

    /**
     * 核心渲染轴脉冲：基于硬件 VSYNC 触发。
     * 分为降维预处理（阶段一）与光速上屏（阶段二），内部无任何杂余业务状态判断，追求极致性能。
     */
    private fun doTick() {
        var hasActiveDraws = false
        var isDummyCurrent = false
        val streamsToRender = ArrayList<DecoderStream>()

        // 阶段一：提取所有产生了新图形信号的流，逐一完成离线 FBO 降维准备
        for (stream in streams.values) {
            if (stream.displayWindows.isEmpty()) continue

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
                    // 底层表面已被物理损坏或撕裂时，安全捕获异常保障循环主轴不断裂
                    Log.e("VLCDecoder", "OES to FBO texture mapping failed: ${e.message}")
                }
            }
        }

        // 阶段二：光速推流分发，将已备好的 2D 纹理铺在所有需要它的端口上
        for (stream in streamsToRender) {
            val windowCount = stream.displayWindows.size
            for (j in 0 until windowCount) {
                val window = stream.displayWindows[j]

                // 双重验证保障绝不向失效的底层组件推画，彻底斩断 EGL_BAD_SURFACE 红字报错
                if (!window.x5Surface.isValid || !eglCore.makeCurrent(
                        window.eglSurface, eglCore.eglContext
                    )
                ) continue

                eglCore.setSwapInterval(0)

                val pw = window.physicalW
                val ph = window.physicalH
                if (pw > 0 && ph > 0) {
                    Matrix.setIdentityM(window.mvpMatrix, 0)
                    eglCore.drawTex2DScreen(stream.tex2DId, window.mvpMatrix, pw, ph)
                    try {
                        eglCore.swapBuffers(window.eglSurface)
                    } catch (e: Exception) {
                        Log.e(
                            "VLCDecoder",
                            "EGL SwapBuffer failed surface might be invalidated: ${e.message}"
                        )
                    }
                }
            }
        }

        // 生命续航裁决：如还有对象活跃则接力注册下一帧脉冲，否则自然休眠让出 CPU 算力
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