package com.caijunlin.vlcdecoder.gles

import android.graphics.Bitmap
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import java.util.concurrent.ConcurrentHashMap

/**
 * @author caijunlin
 * @date   2026/3/4
 * @description   独立的渲染管线节点，拥有私有的工作线程、EGL上下文和图形显存池。
 * 通过在全局调度池中构建多个节点并行工作，有效分摊高并发路数下的 OpenGL 渲染与上下文切换压力。
 */
class RenderNode(
    val nodeName: String,
    private val onStreamDeadCleanup: (String, List<Surface>) -> Unit
) {
    // 提升至显示级别的私有工作线程，防止多路并发时被 CPU 频繁切走时间片导致掉帧
    val thread = object : HandlerThread(nodeName) {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            super.run()
        }
    }.apply { start() }

    // 绑定到私有线程的通讯管道，负责接收外部指令并在正确的线程上下文中执行
    val handler = Handler(thread.looper)

    // 当前节点独占的底层的 EGL14 渲染核心引擎
    lateinit var eglCore: EglCore

    // 当前节点管控的所有解码流集合，采用并发字典保障跨线程读取安全
    val streams = ConcurrentHashMap<String, DecoderStream>()

    // 当前节点管控的所有物理画布显示窗口映射表
    val displayMap = ConcurrentHashMap<Surface, DisplayWindow>()

    // 延迟释放任务队列，用于实现解绑时的防抖机制
    val pendingReleaseTasks = ConcurrentHashMap<String, Runnable>()

    // 拥堵状态记录字典：记录各个目标画布上一帧是否发生渲染超时，用于触发防雪崩丢帧机制
    private val congestedWindows = ConcurrentHashMap<Surface, Boolean>()

    // 标记当前渲染循环是否正在持续心跳跳动
    private var isTicking = false

    // 取代系统 Choreographer 的智能轮询任务，实现纯异步的硬核锁帧调度
    private val tickRunnable = Runnable { doTick() }

    // 每帧渲染时复用的集合，用于记录哪些流产生了新画面，避免高频创建对象导致 GC 抖动
    private val streamsToRender = ArrayList<DecoderStream>(8)

    // 目标最高渲染帧率限制为 25 FPS（每帧平均分配 40ms 时间片）TARGET_FRAME_TIME_MS
    private val farmerMs = 40L

    init {
        // 在节点专属线程中初始化图形硬件环境
        handler.post {
            eglCore = EglCore().apply { initEGL() }
        }
    }

    /**
     * 处理外部的画布绑定请求。
     * 将视频流与物理表面绑定，如果流尚未启动则拉起解码器，并为其创建底层的 EGL 表面。
     *
     * @param url 视频流的网络地址
     * @param x5Surface 外部提供的目标渲染画布
     * @param w 画布物理宽度
     * @param h 画布物理高度
     * @param opts 针对当前流的自定义播放参数
     * @param globalVlc 全局 VLC 底层引擎工厂实例
     * @param limit 当前节点允许管控的最大视频流数量上限
     */
    fun handleBind(url: String, x5Surface: Surface, w: Int, h: Int, opts: ArrayList<String>, globalVlc: LibVLC?, limit: Int) {
        // 避免重复绑定同一个画布
        if (displayMap.containsKey(x5Surface)) return

        // 撤销可能存在的针对该流的延迟销毁任务
        pendingReleaseTasks.remove(url)?.let { handler.removeCallbacks(it) }

        var stream = streams[url]
        if (stream == null) {
            // 节点内并发软限制保护
            if (streams.size >= limit) return
            stream = DecoderStream(url, eglCore, globalVlc, handler, opts) { deadUrl ->
                handleStreamDead(deadUrl)
            }
            stream.start()
            streams[url] = stream
        }

        // 为传入的外部物理表面包装一层 EGL 环境
        val window = DisplayWindow(x5Surface)
        window.initEGLSurface(eglCore)
        window.physicalW = w
        window.physicalH = h
        window.isDirty = true

        // 首次绑定时，强行解除该 EGL 表面的垂直同步等待，释放底层性能
        if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
            eglCore.setSwapInterval(0)
            eglCore.makeCurrentMain()
        }

        // 建立羁绊关系并将其推入渲染循环
        stream.displayWindows.add(window)
        displayMap[x5Surface] = window

        startTicking()
    }

    /**
     * 处理外部的画布解绑请求。
     * 从渲染管线中剥离指定的显示表面，若该流已无任何依附的画布，将触发防抖回收。
     */
    fun handleUnbind(url: String, x5Surface: Surface) {
        val window = displayMap.remove(x5Surface)
        if (window != null) {
            // 解绑前清空画面，避免残留最后一帧死图
            if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                eglCore.clearCurrentSurface()
                eglCore.swapBuffers(window.eglSurface)
            }
            window.release(eglCore)
        }

        val stream = streams[url]
        if (stream != null && window != null) {
            stream.displayWindows.remove(window)
            // 防抖机制：如果该流已经没有接收画面的画布，延迟 500ms 后销毁解码器释放内存
            if (stream.displayWindows.isEmpty() && !pendingReleaseTasks.containsKey(url)) {
                val task = Runnable {
                    val s = streams[url]
                    if (s != null && s.displayWindows.isEmpty()) {
                        s.release()
                        streams.remove(url)
                    }
                    pendingReleaseTasks.remove(url)
                }
                pendingReleaseTasks[url] = task
                handler.postDelayed(task, 500L)
            }
        }
    }

    /**
     * 同步处理前端画布发生的物理尺寸异动，并标记脏数据以触发管线重绘纠正形变。
     */
    fun handleResize(x5Surface: Surface, width: Int, height: Int) {
        displayMap[x5Surface]?.let {
            if (it.physicalW != width || it.physicalH != height) {
                it.physicalW = width
                it.physicalH = height
                it.isDirty = true
                startTicking()
            }
        }
    }

    /**
     * 处理异步截帧请求。利用 PBO (Pixel Buffer Object) 零拷贝技术从底层的降维 FBO 中提取干净画面。
     */
    fun handleCapture(x5Surface: Surface, callback: (Bitmap?) -> Unit) {
        val window = displayMap[x5Surface]
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

        eglCore.makeCurrentMain()
        eglCore.readPixelsFromFBOAsync(
            targetStream.fboId,
            targetStream.videoWidth,
            targetStream.videoHeight,
            handler,
            callback
        )
    }

    /**
     * 强行清空指定画布上残留的渲染内容，将其恢复为全透明底色。
     */
    fun handleClearSurface(x5Surface: Surface) {
        val window = displayMap[x5Surface]
        if (window != null) {
            if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                eglCore.clearCurrentSurface()
                eglCore.swapBuffers(window.eglSurface)
            }
        } else {
            // 如果画布已经从字典中剥离，临时为其构建 EGL 环境执行清空再销毁
            val tempEgl = eglCore.createWindowSurface(x5Surface)
            if (tempEgl != android.opengl.EGL14.EGL_NO_SURFACE) {
                if (eglCore.makeCurrent(tempEgl, eglCore.eglContext)) {
                    eglCore.clearCurrentSurface()
                    eglCore.swapBuffers(tempEgl)
                }
                eglCore.destroySurface(tempEgl)
            }
        }
    }

    /**
     * 内部回调：当某个流经过多次重试依然失败时被触发。
     * 清理所有关联资源，并向上呼叫中枢调度池清理全局路由表。
     */
    private fun handleStreamDead(url: String) {
        val dead = streams.remove(url) ?: return
        pendingReleaseTasks.remove(url)?.let { handler.removeCallbacks(it) }

        val deadSurfaces = mutableListOf<Surface>()
        dead.displayWindows.forEach { window ->
            val key = displayMap.entries.find { it.value == window }?.key
            if (key != null) {
                displayMap.remove(key)
                deadSurfaces.add(key)
            }
            window.release(eglCore)
        }
        dead.release()

        onStreamDeadCleanup(url, deadSurfaces)
    }

    /**
     * 启动渲染管线的心跳任务。
     */
    private fun startTicking() {
        if (!isTicking) {
            isTicking = true
            handler.post(tickRunnable)
        }
    }

    /**
     * 渲染节点最核心的管线心跳函数。
     * 负责将 OES 纹理降维，分发拷贝至目标屏幕，并进行防拥堵干预与帧率限制。
     */
    private fun doTick() {
        // 记录单次 Tick 的总起步时间，用于最后的硬核锁帧计算
        val tickStartNs = System.nanoTime()

        var hasActiveDraws = false
        var isDummyCurrent = false
        streamsToRender.clear()

        // ================= 阶段一：纯离线 FBO 降维准备 =================
        // 遍历流，将底层解码完毕的新帧（OES格式）读取并压扁至标准的二维内部纹理缓冲中
        for (stream in streams.values) {
            if (stream.displayWindows.isEmpty()) continue
            hasActiveDraws = true

            if (stream.frameAvailable) {
                if (!isDummyCurrent) {
                    eglCore.makeCurrentMain()
                    isDummyCurrent = true
                }
                try {
                    // 接收底层硬件解码推流
                    stream.surfaceTexture?.updateTexImage()

                    stream.lastPts = stream.surfaceTexture?.timestamp ?: 0L
                    stream.surfaceTexture?.getTransformMatrix(stream.transformMatrix)
                    if (!stream.hasFirstFrame) {
                        stream.checkAndUpdateResolution()
                        stream.hasFirstFrame = true
                    }
                    stream.frameAvailable = false

                    // 执行空间转码，写入私有 FBO 画板
                    eglCore.drawOESToFBO(
                        stream.fboId, stream.oesTextureId, stream.transformMatrix,
                        stream.videoWidth, stream.videoHeight
                    )

                    streamsToRender.add(stream)
                } catch (e: Exception) {
                    Log.e("VLCDecoder", "OES mapping failed: ${e.message}")
                }
            }
        }

        // 斩断同步链条：强制底层驱动立刻将阶段一的指令推入 GPU 队列执行，防止与后续 SwapBuffers 发生死锁级资源抢占
        if (hasActiveDraws && isDummyCurrent) {
            android.opengl.GLES30.glFlush()
        }

        // ================= 阶段二：上屏渲染 =================
        // 将准备好的 FBO 二维画面极速盖章拷贝至绑定的所有物理画布上
        for (stream in streams.values) {
            val windows = stream.displayWindows
            if (windows.isEmpty()) continue

            val hasNewFrame = streamsToRender.contains(stream)

            for (j in 0 until windows.size) {
                val window = windows[j]
                val pw = window.physicalW
                val ph = window.physicalH
                if (pw == 0 || ph == 0) continue

                if (hasNewFrame || window.isDirty) {

                    // 防雪崩机制核心：如果该画布在上一帧导致了管线严重阻塞，主动丢弃本帧请求，给外部容器（如 X5）喘息空间
                    val isCongested = congestedWindows[window.x5Surface] ?: false
                    if (isCongested) {
                        congestedWindows[window.x5Surface] = false
                        window.isDirty = false
                        continue
                    }

                    try {
                        val makeCurrentSuccess = eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)

                        if (makeCurrentSuccess) {
                            // 每次切换上下文后，再次强制解除垂直同步，防范某些魔改 ROM 的显卡驱动暗中重置
                            eglCore.setSwapInterval(0)

                            if (stream.lastPts > 0L) {
                                eglCore.setPresentationTime(window.eglSurface, stream.lastPts)
                            }

                            Matrix.setIdentityM(window.mvpMatrix, 0)
                            eglCore.drawTex2DScreen(stream.tex2DId, window.mvpMatrix, pw, ph)

                            // 测量向外部缓冲队列推送画面的耗时
                            val swapStartNs = System.nanoTime()
                            eglCore.swapBuffers(window.eglSurface)
                            val swapCostMs = (System.nanoTime() - swapStartNs) / 1_000_000f

                            // 动态拥堵判定：推送耗时超过 25ms，说明外部队列已满。打上标记以惩罚并丢弃针对该窗口的下一帧
                            if (swapCostMs > 25f) {
                                congestedWindows[window.x5Surface] = true
                            } else {
                                congestedWindows[window.x5Surface] = false
                            }

                            window.isDirty = false
                        }
                    } catch (e: Exception) {
                        Log.e("VLCDecoder", "Swap failed: ${e.message}")
                    }
                }
            }
        }

        // ================= 终极杀手锏：硬核锁帧计算 =================
        // 脱离物理屏幕 VSYNC，依据目标 FPS 进行智能休眠降频调度
        if (hasActiveDraws) {
            val costMs = (System.nanoTime() - tickStartNs) / 1_000_000L

            // 削峰填谷：若当前管线跑得太快，通过休眠补齐时间片；避免多管线疯狂并发塞爆外层系统合成器
            val delayMs = if (costMs < farmerMs) {
                farmerMs - costMs
            } else {
                5L // 严重超时兜底，强制释放至少 5ms 的 CPU 控制权，防范系统 ANR
            }
            handler.postDelayed(tickRunnable, delayMs)
        } else {
            isTicking = false
            eglCore.makeCurrentMain()
        }
    }

    /**
     * 节点诊断探针，用于日志分析当前节点负载与内存挂载树。
     */
    fun printNodeDiagnostics(nodeIndex: Int) {
        handler.post {
            if (streams.isEmpty()) return@post
            Log.w("VLCDecoder", "------ Node-$nodeIndex ($nodeName) ------")
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
        }
    }

    /**
     * 终极清理指令，清空内部容器，销毁所有 EGL 及流资源，并停止轮询。
     */
    fun releaseAll() {
        pendingReleaseTasks.values.forEach { handler.removeCallbacks(it) }
        pendingReleaseTasks.clear()
        streams.values.forEach { it.release() }
        displayMap.values.forEach { it.release(eglCore) }
        streams.clear()
        displayMap.clear()
        // 销毁时停止心跳
        handler.removeCallbacks(tickRunnable)
        eglCore.release()
    }
}