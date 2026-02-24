package com.caijunlin.vlcdecoder.gles

import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.caijunlin.vlcdecoder.core.IRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * EGL 独立线程渲染器
 *
 * **主要功能：**
 * 该类负责管理一个独立的后台渲染线程，在该线程中构建 OpenGL ES 环境 (EGL Context)，
 * 并将外部解码器 (如 VLC) 产生的原始像素数据 (ByteBuffer) 渲染到指定的 Android Surface 上。
 *
 * **核心职责：**
 * - **生命周期管理**：负责 EGL 显示设备、上下文、绘图表面的创建与销毁。
 * - **数据同步**：处理解码线程 (生产者) 与渲染线程 (消费者) 之间的高效数据传递。
 * - **纹理上传**：将 CPU 内存中的像素数据上传至 GPU 纹理。
 * - **画面绘制**：使用 Shader 将纹理绘制到全屏矩形上，并处理视口缩放。
 *
 * **关键优化策略：**
 * - **无锁并发**：使用 `AtomicReference`确保解码线程在提交数据时永不阻塞，避免因渲染耗时导致解码卡顿。
 * - **状态机缓存**：将 Shader 绑定、顶点属性指针设置等静态 GL 指令移至初始化阶段，减少每帧绘制时的 JNI 调用开销。
 * - **显存复用**：优先使用 `glTexSubImage2D` 进行局部更新，仅在分辨率变更时才重新分配显存 (`glTexImage2D`)。
 * - **内存对齐**：强制设置 `GL_UNPACK_ALIGNMENT` 为 1，防止非 4 字节对齐的视频宽度导致 GPU 读取越界崩溃。
 * - **按需重绘**：通过 `isTextureReady` 标志位防止初始黑屏，并支持在 Surface 尺寸变化时重绘最后一帧。
 */
class Egl20SurfaceRenderer(private val surface: Surface) : IRenderer {

    // 渲染线程句柄，所有 OpenGL 操作必须在此线程执行
    private val renderThread = HandlerThread("Egl20RenderThread")
    private val renderHandler: Handler

    // 原子引用，用于跨线程传递最新的视频帧数据
    // 解码线程负责写入 (Set)，渲染线程负责读取并置空 (GetAndSet)
    private val nextFrameBuffer = AtomicReference<ByteBuffer?>(null)

    // 当前视频源数据的宽和高
    private var dataWidth = 0
    private var dataHeight = 0

    // 纹理就绪标志位，用于防止在第一帧数据到达前进行绘制导致黑屏
    // 也用于在 Surface 尺寸变化时重绘当前持有的最后一帧画面
    private var isTextureReady = false

    // EGL 核心对象，用于管理 OpenGL 上下文和窗口表面
    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
    private val egl: EGL10 = EGLContext.getEGL() as EGL10

    // OpenGL 纹理 ID
    private var textureId = 0

    // 纹理状态追踪，用于判断是需要重新分配显存还是仅更新数据
    private var textureAllocated = false
    private var lastTexWidth = 0
    private var lastTexHeight = 0

    // 视口尺寸缓存，避免在尺寸未变动时重复调用耗时的 glViewport 指令
    private var currentSurfaceW = 0
    private var currentSurfaceH = 0

    // 用于 EGL 查询的复用数组，避免频繁创建对象
    private val sizeCache = IntArray(1)

    // 顶点数据缓冲区 (全屏矩形坐标 + 纹理坐标)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_RECTANGLE_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(FULL_RECTANGLE_COORDS)
        .apply { position(0) }

    companion object {
        // 内部消息类型定义
        private const val MSG_INIT = 1    // 初始化 EGL 环境
        private const val MSG_DRAW = 2    // 执行绘制
        private const val MSG_RELEASE = 3 // 释放资源
        private const val MSG_RESIZE = 4  // 窗口尺寸变更

        // 顶点坐标 (x, y) 和 纹理坐标 (u, v)
        // 使用 Triangle Strip 绘制矩形
        private val FULL_RECTANGLE_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f, // 左下
            1.0f, -1.0f, 1.0f, 1.0f,  // 右下
            -1.0f, 1.0f, 0.0f, 0.0f,  // 左上
            1.0f, 1.0f, 1.0f, 0.0f    // 右上
        )

        // 顶点着色器：负责顶点位置变换和纹理坐标传递
        private const val VERTEX_SHADER =
            "attribute vec4 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}"

        // 片元着色器：负责纹理采样和颜色输出 (RV16/RGB565 格式处理)
        // 注意：这里手动交换了 R 和 B 通道 (color.b, ..., color.r) 以适配某些解码格式
        private const val FRAGMENT_SHADER =
            "precision mediump float;varying vec2 vTexCoord;uniform sampler2D sTexture;void main(){vec4 color=texture2D(sTexture,vTexCoord);gl_FragColor=vec4(color.b,color.g,color.r,color.a);}"
    }

    init {
        // 启动后台渲染线程
        renderThread.start()
        // 初始化 Handler，处理来自 UI 线程或解码线程的消息
        renderHandler = Handler(renderThread.looper) { msg ->
            when (msg.what) {
                MSG_INIT -> initEGL()
                MSG_DRAW -> drawFrame()
                MSG_RESIZE -> resizeInternal()
                MSG_RELEASE -> releaseInternal()
            }
            true
        }
        // 发送初始化消息，尽早建立 GL 环境
        renderHandler.sendEmptyMessage(MSG_INIT)
    }

    /**
     * 通知渲染器 Surface 尺寸发生变化
     * 通常由 X5 Widget 的 onRectChanged 回调触发
     */
    fun updateSurfaceSize() {
        // 移除队列中可能积压的旧 Resize 消息，只处理最新的
        if (!renderHandler.hasMessages(MSG_RESIZE)) {
            renderHandler.sendEmptyMessage(MSG_RESIZE)
        }
    }

    /**
     * 接收来自解码器的新视频帧
     * 此方法在 VLC 解码线程中被调用，要求极高的响应速度
     *
     * @param data 包含像素数据的 ByteBuffer
     * @param width 视频源宽度
     * @param height 视频源高度
     */
    override fun updateFrame(data: ByteBuffer, width: Int, height: Int) {
        // 检查视频源分辨率是否发生变化
        if (dataWidth != width || dataHeight != height) {
            dataWidth = width
            dataHeight = height
            // 标记纹理需要重新分配内存，不能仅做子图更新
            textureAllocated = false
        }

        // 使用原子操作设置数据引用，无锁且线程安全
        nextFrameBuffer.set(data)

        // 如果渲染队列中没有待处理的绘制请求，则发送一个新的绘制信号
        // 这种机制起到了“帧率控制”的作用，避免生产过快导致消息队列爆满
        if (!renderHandler.hasMessages(MSG_DRAW)) {
            renderHandler.sendEmptyMessage(MSG_DRAW)
        }
    }

    /**
     * 初始化 EGL 环境
     * 包括 Display 连接、Config 选择、Context 创建和 Surface 绑定
     */
    private fun initEGL() {
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        egl.eglInitialize(eglDisplay, version)

        // 配置 EGL 属性：RGB 888 格式，请求 OpenGL ES 2.0 兼容环境
        val configAttributes = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        egl.eglChooseConfig(eglDisplay, configAttributes, configs, 1, numConfigs)

        // 创建上下文，指定版本为 2
        val contextAttributes = intArrayOf(0x3098, 2, EGL10.EGL_NONE)
        eglContext =
            egl.eglCreateContext(eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, contextAttributes)

        // 将 Android 原生 Surface 绑定到 EGL
        eglSurface = egl.eglCreateWindowSurface(eglDisplay, configs[0], surface, null)

        // 校验创建结果
        if (eglSurface == EGL10.EGL_NO_SURFACE || eglContext == EGL10.EGL_NO_CONTEXT) return

        // 绑定当前线程的 OpenGL 上下文
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        // 上下文建立成功后，初始化 OpenGL 资源
        initGL()
        // 初始设置一次视口大小
        resizeInternal()
    }

    /**
     * 初始化 OpenGL 资源与全局状态
     * 将静态配置移至此处执行，减少 drawFrame 中的冗余指令
     */
    private fun initGL() {
        val program = createProgram()
        GLES20.glUseProgram(program)
        // 绑定纹理采样器到纹理单元 0
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

        // 核心修正：设置像素解包对齐为 1 字节
        // 这是为了兼容宽度为奇数或非 4 倍数的视频源 (如 RGB565)，防止数据读取错位导致崩溃
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        // 设置纹理过滤参数 (线性插值) 和 环绕模式 (边缘截断)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        // 绑定顶点数据并启用属性数组
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)

        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
    }

    /**
     * 更新 OpenGL 视口大小
     * 只有在 EGL Surface 实际尺寸发生改变时才执行 glViewport，节省 GPU 开销
     */
    private fun resizeInternal() {
        // 确保上下文有效
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        // 查询底层 Surface 的真实物理宽高
        egl.eglQuerySurface(eglDisplay, eglSurface, EGL10.EGL_WIDTH, sizeCache)
        currentSurfaceW = sizeCache[0]
        egl.eglQuerySurface(eglDisplay, eglSurface, EGL10.EGL_HEIGHT, sizeCache)
        currentSurfaceH = sizeCache[0]

        GLES20.glViewport(0, 0, currentSurfaceW, currentSurfaceH)

        // 如果此时已经有纹理数据 (比如窗口缩放时)，立即重绘当前帧，防止画面拉伸或黑屏
        if (isTextureReady) {
            drawInternal(false)
        }
    }

    /**
     * 核心绘制流程
     * 负责将最新的 ByteBuffer 数据上传并渲染
     */
    private fun drawFrame() {
        // 原子获取最新数据并置空，保证只处理新帧
        val newData = nextFrameBuffer.getAndSet(null)

        // 容错检查：上下文丢失则不绘制
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        // 如果有新数据且尺寸有效，执行纹理上传
        if (newData != null && dataWidth > 0 && dataHeight > 0) {
            uploadTexture(newData)
            isTextureReady = true
        }

        // 仅当纹理准备就绪后才执行绘制指令，避免绘制空数据
        if (isTextureReady) {
            drawInternal(true)
        }
    }

    /**
     * 纹理上传逻辑
     * 包含显存分配优化
     */
    private fun uploadTexture(data: ByteBuffer) {
        // 防御性绑定：确保当前操作的是正确的纹理对象
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        data.position(0)

        // 显存分配策略优化：
        // 如果视频尺寸变更或首次渲染，使用 glTexImage2D 分配/重分配显存
        if (!textureAllocated || lastTexWidth != dataWidth || lastTexHeight != dataHeight) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                dataWidth, dataHeight, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, data
            )
            textureAllocated = true
            lastTexWidth = dataWidth
            lastTexHeight = dataHeight
        } else {
            // 如果尺寸未变，使用 glTexSubImage2D 仅更新数据内容，性能更高
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0,
                dataWidth, dataHeight,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, data
            )
        }
    }

    /**
     * 执行底层的 GL 绘制指令和缓冲区交换
     * @param doSwap 是否执行 eglSwapBuffers (交换前后端缓冲以显示画面)
     */
    private fun drawInternal(doSwap: Boolean) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (doSwap) {
            egl.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    /**
     * 释放资源的公开接口
     * 清空消息队列并退出线程
     */
    override fun release() {
        renderHandler.removeCallbacksAndMessages(null)
        renderHandler.sendEmptyMessage(MSG_RELEASE)
        renderThread.quitSafely()
    }

    /**
     * 内部资源销毁逻辑
     * 销毁纹理、上下文和显示连接
     */
    private fun releaseInternal() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }

        if (eglDisplay != EGL10.EGL_NO_DISPLAY) {
            egl.eglMakeCurrent(
                eglDisplay,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_CONTEXT
            )
            egl.eglDestroySurface(eglDisplay, eglSurface)
            egl.eglDestroyContext(eglDisplay, eglContext)
            egl.eglTerminate(eglDisplay)
        }
        surface.release()
    }

    /**
     * 编译并链接 Shader 程序
     */
    private fun createProgram(): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        return program
    }

    /**
     * 加载并编译单个 Shader
     */
    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }
}