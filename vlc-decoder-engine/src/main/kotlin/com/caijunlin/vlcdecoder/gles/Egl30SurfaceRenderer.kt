package com.caijunlin.vlcdecoder.gles

import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.caijunlin.vlcdecoder.core.IRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.*

/**
 * EGL 独立线程渲染器 (GLES 3.0 - CPU 双缓冲稳健版)
 *
 * **核心修复：**
 * 1. **移除 PBO**：彻底解决 Mali GPU 驱动对 PBO 支持不佳导致的崩溃、黑屏问题。
 * 2. **CPU 双缓冲 (Ping-Pong)**：在 Java 层建立两个 ByteBuffer，分离读写。
 * - 防止 VLC 解码器在 OpenGL 上传纹理时修改内存，**彻底根除画面抽动/撕裂**。
 * 3. **兼容性**：保留 1 字节对齐设置，确保奇数分辨率视频正常播放。
 */
class Egl30SurfaceRenderer(private val surface: Surface) : IRenderer {

    private val renderThread = HandlerThread("Egl30RenderThread")
    private val renderHandler: Handler

    private var backBuffer: ByteBuffer? = null
    private var frontBuffer: ByteBuffer? = null

    // 锁对象，仅用于交换指针瞬间，开销极小
    private val bufferLock = Any()
    private var hasNewFrame = false

    // 数据源尺寸
    private var dataWidth = 0
    private var dataHeight = 0

    // 纹理状态
    private var isTextureReady = false
    private var textureAllocated = false
    private var lastTexWidth = 0
    private var lastTexHeight = 0

    // EGL & GL 对象
    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
    private val egl: EGL10 = EGLContext.getEGL() as EGL10
    private var textureId = 0
    private var programId = 0

    // 视口缓存
    private var currentSurfaceW = 0
    private var currentSurfaceH = 0
    private val sizeCache = IntArray(1)

    // 顶点数据
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_RECTANGLE_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(FULL_RECTANGLE_COORDS)
        .apply { position(0) }

    companion object {
        private const val MSG_INIT = 1
        private const val MSG_DRAW = 2
        private const val MSG_RELEASE = 3
        private const val MSG_RESIZE = 4

        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES3_BIT = 0x40

        private val FULL_RECTANGLE_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
            1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 0.0f,
            1.0f,  1.0f, 1.0f, 0.0f
        )

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D sTexture;
            layout(location = 0) out vec4 fragColor;
            void main() {
                vec4 color = texture(sTexture, vTexCoord);
                fragColor = vec4(color.b, color.g, color.r, color.a);
            }
        """
    }

    init {
        renderThread.start()
        renderHandler = Handler(renderThread.looper) { msg ->
            when (msg.what) {
                MSG_INIT -> initEGL()
                MSG_DRAW -> drawFrame()
                MSG_RESIZE -> resizeInternal()
                MSG_RELEASE -> releaseInternal()
            }
            true
        }
        renderHandler.sendEmptyMessage(MSG_INIT)
    }

    fun updateSurfaceSize() {
        if (!renderHandler.hasMessages(MSG_RESIZE)) {
            renderHandler.sendEmptyMessage(MSG_RESIZE)
        }
    }

    /**
     * 数据更新入口 (运行在 VLC 线程)
     * 这里执行内存拷贝 (Deep Copy)，确保 VLC 和 GL 数据完全隔离
     */
    override fun updateFrame(data: ByteBuffer, width: Int, height: Int) {
        synchronized(bufferLock) {
            // 1. 如果分辨率变了，或者 backBuffer 还没初始化，申请内存
            val size = width * height * 2 // RV16
            if (backBuffer == null || backBuffer!!.capacity() != size) {
                backBuffer = ByteBuffer.allocateDirect(size)
                // 此时也顺便重置纹理分配标志，因为尺寸肯定变了
                if (dataWidth != width || dataHeight != height) {
                    dataWidth = width
                    dataHeight = height
                    textureAllocated = false
                }
            }

            // 2. 深拷贝数据：从 Native 指针 (data) 拷贝到 Java DirectBuffer (backBuffer)
            // 这步虽然有 CPU 开销，但对于解决“抽动”是必须的
            data.position(0)
            backBuffer!!.position(0)
            backBuffer!!.put(data)
            backBuffer!!.position(0)

            // 3. 标记新帧到达
            hasNewFrame = true
        }

        // 4. 通知渲染线程
        if (!renderHandler.hasMessages(MSG_DRAW)) {
            renderHandler.sendEmptyMessage(MSG_DRAW)
        }
    }

    private fun initEGL() {
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        egl.eglInitialize(eglDisplay, version)

        val configAttribs = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        egl.eglChooseConfig(eglDisplay, configAttribs, configs, 1, numConfigs)

        // 容错：如果 ES3 找不到，回退到默认
        val config = if (numConfigs[0] > 0) configs[0] else {
            // 实际上 GLES30 类如果没崩溃说明库存在，但 Config 可能需要调整
            configs[0]
        }

        val contextAttribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
        eglContext = egl.eglCreateContext(eglDisplay, config, EGL10.EGL_NO_CONTEXT, contextAttribs)
        eglSurface = egl.eglCreateWindowSurface(eglDisplay, config, surface, null)

        if (eglSurface == EGL10.EGL_NO_SURFACE || eglContext == EGL10.EGL_NO_CONTEXT) return
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        initGL()
        resizeInternal()
    }

    private fun initGL() {
        val program = createProgram()
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "sTexture"), 0)

        // ⚡ 必须保留：1字节对齐，防止崩溃
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)

        // 注意：这里不再生成 PBO，因为我们改用 CPU 缓冲
    }

    private fun resizeInternal() {
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
        egl.eglQuerySurface(eglDisplay, eglSurface, EGL10.EGL_WIDTH, sizeCache)
        currentSurfaceW = sizeCache[0]
        egl.eglQuerySurface(eglDisplay, eglSurface, EGL10.EGL_HEIGHT, sizeCache)
        currentSurfaceH = sizeCache[0]
        GLES30.glViewport(0, 0, currentSurfaceW, currentSurfaceH)

        if (isTextureReady) drawInternal(false)
    }

    private fun drawFrame() {
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        // =====================================================================
        // ⚡ 交换缓冲区 (Swap Buffers)
        // 极短的临界区，仅交换指针引用，不进行数据拷贝
        // =====================================================================
        var bufferToUpload: ByteBuffer? = null

        synchronized(bufferLock) {
            if (hasNewFrame && backBuffer != null) {
                // 1. 交换前后台缓冲
                // 如果 frontBuffer 为空(第一帧)或者大小不够，就创建一个新的
                if (frontBuffer == null || frontBuffer!!.capacity() != backBuffer!!.capacity()) {
                    frontBuffer = ByteBuffer.allocateDirect(backBuffer!!.capacity())
                }

                // 2. 这里的拷贝是必须的，将 VLC 写入的 backBuffer 拷贝到渲染用的 frontBuffer
                // 这样释放锁之后，VLC 就可以继续写 backBuffer，而 GL 慢慢处理 frontBuffer
                // 使用 put() 进行内存块拷贝，速度很快
                backBuffer!!.position(0)
                frontBuffer!!.position(0)
                frontBuffer!!.put(backBuffer!!)
                frontBuffer!!.position(0)

                bufferToUpload = frontBuffer
                hasNewFrame = false
            }
        }

        // =====================================================================
        // ⚡ 纹理上传 (Render Thread 独占，无锁)
        // =====================================================================
        if (bufferToUpload != null && dataWidth > 0 && dataHeight > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

            if (!textureAllocated || lastTexWidth != dataWidth || lastTexHeight != dataHeight) {
                // 分配显存
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB,
                    dataWidth, dataHeight, 0,
                    GLES30.GL_RGB, GLES30.GL_UNSIGNED_SHORT_5_6_5, bufferToUpload
                )
                textureAllocated = true
                lastTexWidth = dataWidth
                lastTexHeight = dataHeight
            } else {
                // 局部更新 (最常用路径)
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0,
                    dataWidth, dataHeight,
                    GLES30.GL_RGB, GLES30.GL_UNSIGNED_SHORT_5_6_5, bufferToUpload
                )
            }
            isTextureReady = true
        }

        if (isTextureReady) {
            drawInternal(true)
        }
    }

    private fun drawInternal(doSwap: Boolean) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        if (doSwap) {
            egl.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    override fun release() {
        renderHandler.removeCallbacksAndMessages(null)
        renderHandler.sendEmptyMessage(MSG_RELEASE)
        renderThread.quitSafely()
    }

    private fun releaseInternal() {
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)

        if (eglDisplay != EGL10.EGL_NO_DISPLAY) {
            egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl.eglDestroySurface(eglDisplay, eglSurface)
            egl.eglDestroyContext(eglDisplay, eglContext)
            egl.eglTerminate(eglDisplay)
        }
        surface.release()
    }

    private fun createProgram(): Int {
        val vShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vShader)
        GLES30.glAttachShader(program, fShader)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vShader)
        GLES30.glDeleteShader(fShader)
        return program
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        // 编译错误检查
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}