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
 * Egl30SurfaceRenderer
 *
 * 稳定版 GLES3.0 Surface 渲染器
 *
 * 原闪帧原因：
 * 旧代码在无新帧时仍调用 eglSwapBuffers
 * 导致 Surface 复用旧 buffer
 */
class Egl30SurfaceRenderer(private val surface: Surface) : IRenderer {

    private val renderThread = HandlerThread("Egl30RenderThread")
    private var renderHandler: Handler

    // 真正双缓冲
    private var writeBuffer: ByteBuffer? = null
    private var readBuffer: ByteBuffer? = null

    private val lock = Any()
    private var frameAvailable = false

    private var videoWidth = 0
    private var videoHeight = 0

    private var textureId = 0
    private var programId = 0

    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
    private val egl: EGL10 = EGLContext.getEGL() as EGL10

    companion object {

        private const val MSG_INIT = 1
        private const val MSG_DRAW = 2
        private const val MSG_RELEASE = 3

        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES3_BIT = 0x40

        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
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
                vec4 c = texture(sTexture, vTexCoord);
                fragColor = vec4(c.b, c.g, c.r, c.a);
            }
        """
    }

    init {
        renderThread.start()
        renderHandler = Handler(renderThread.looper) {
            when (it.what) {
                MSG_INIT -> initEGL()
                MSG_DRAW -> draw()
                MSG_RELEASE -> releaseInternal()
            }
            true
        }
        renderHandler.sendEmptyMessage(MSG_INIT)
    }

    /**
     * VLC线程调用
     *
     * 真双缓冲逻辑：
     * 1. VLC只写writeBuffer
     * 2. GL线程只读readBuffer
     * 3. 通过交换引用实现零拷贝
     */
    override fun updateFrame(data: ByteBuffer, width: Int, height: Int) {

        val size = width * height * 2

        synchronized(lock) {

            if (writeBuffer == null || writeBuffer!!.capacity() != size) {
                writeBuffer = ByteBuffer.allocateDirect(size)
                readBuffer = ByteBuffer.allocateDirect(size)
            }

            videoWidth = width
            videoHeight = height

            data.position(0)
            writeBuffer!!.position(0)
            writeBuffer!!.put(data)
            writeBuffer!!.position(0)

            frameAvailable = true

            // 🔥 交换缓冲区（核心）
            val tmp = readBuffer
            readBuffer = writeBuffer
            writeBuffer = tmp
        }

        renderHandler.sendEmptyMessage(MSG_DRAW)
    }

    private fun initEGL() {

        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        egl.eglInitialize(eglDisplay, IntArray(2))

        val configAttribs = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL10.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        egl.eglChooseConfig(eglDisplay, configAttribs, configs, 1, IntArray(1))

        val contextAttribs = intArrayOf(
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL10.EGL_NONE
        )

        eglContext = egl.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL10.EGL_NO_CONTEXT,
            contextAttribs
        )

        eglSurface = egl.eglCreateWindowSurface(
            eglDisplay,
            configs[0],
            surface,
            null
        )

        egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        initGL()
    }

    private fun initGL() {

        programId = createProgram()
        GLES30.glUseProgram(programId)

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        textureId = tex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTICES)
            .apply { position(0) }

        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(programId, "sTexture"),
            0
        )
    }

    /**
     * 只有真正有新帧才绘制
     * 没有新帧绝不swap
     */
    private fun draw() {

        var buffer: ByteBuffer? = null
        var w = 0
        var h = 0

        synchronized(lock) {
            if (!frameAvailable) return
            buffer = readBuffer
            w = videoWidth
            h = videoHeight
            frameAvailable = false
        }

        if (buffer == null) return

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGB,
            w,
            h,
            0,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_SHORT_5_6_5,
            buffer
        )

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        egl.eglSwapBuffers(eglDisplay, eglSurface)
    }

    override fun release() {
        renderHandler.sendEmptyMessage(MSG_RELEASE)
        renderThread.quitSafely()
    }

    private fun releaseInternal() {

        if (textureId != 0)
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)

        egl.eglMakeCurrent(
            eglDisplay,
            EGL10.EGL_NO_SURFACE,
            EGL10.EGL_NO_SURFACE,
            EGL10.EGL_NO_CONTEXT
        )

        egl.eglDestroySurface(eglDisplay, eglSurface)
        egl.eglDestroyContext(eglDisplay, eglContext)
        egl.eglTerminate(eglDisplay)

        surface.release()
    }

    private fun createProgram(): Int {
        val v = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val f = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    private fun loadShader(type: Int, code: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, code)
        GLES30.glCompileShader(s)
        return s
    }
}