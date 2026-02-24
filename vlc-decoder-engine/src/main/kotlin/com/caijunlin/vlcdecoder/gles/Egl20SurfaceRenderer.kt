package com.caijunlin.vlcdecoder.gles

import android.opengl.GLES20
import android.view.Surface
import com.caijunlin.vlcdecoder.core.IRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.*

/**
 *
 * 高稳定版 OpenGL ES 2.0 渲染器
 *
 * 设计目标：
 * 提供稳定、低延迟、低抖动的 VLC 软解码渲染方案
 *
 * 设计特点：
 * 使用真正双缓冲引用交换
 * 渲染线程自驱动循环
 * 只在新帧到达时进行纹理上传和交换缓冲
 * 避免 Handler 消息调度开销
 * 避免 AtomicReference 带来的潜在竞争问题
 * 仅在分辨率变化时重新分配显存
 */
class Egl20SurfaceRenderer(
    private val surface: Surface
) : IRenderer {

    private var running = true

    private val renderThread = Thread {
        initEGL()
        renderLoop()
        releaseInternal()
    }

    private val lock = Any()

    private var writeBuffer: ByteBuffer? = null
    private var readBuffer: ByteBuffer? = null

    private var frameAvailable = false

    private var videoWidth = 0
    private var videoHeight = 0

    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
    private val egl: EGL10 = EGLContext.getEGL() as EGL10

    private var textureId = 0
    private var programId = 0

    private var textureAllocated = false
    private var lastWidth = 0
    private var lastHeight = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(VERTICES)

    companion object {

        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
            1f,  1f, 1f, 0f
        )

        private const val VERTEX_SHADER =
            "attribute vec4 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}"

        private const val FRAGMENT_SHADER =
            "precision mediump float;varying vec2 vTexCoord;uniform sampler2D sTexture;void main(){vec4 c=texture2D(sTexture,vTexCoord);gl_FragColor=vec4(c.b,c.g,c.r,c.a);}"
    }

    init {
        vertexBuffer.position(0)

        renderThread.start()
    }

    override fun updateFrame(data: ByteBuffer, width: Int, height: Int) {

        val size = width * height * 2

        synchronized(lock) {

            if (writeBuffer == null || writeBuffer!!.capacity() != size) {
                writeBuffer = ByteBuffer.allocateDirect(size)
                readBuffer = ByteBuffer.allocateDirect(size)
                textureAllocated = false
            }

            videoWidth = width
            videoHeight = height

            data.position(0)
            writeBuffer!!.position(0)
            writeBuffer!!.put(data)
            writeBuffer!!.position(0)

            val tmp = readBuffer
            readBuffer = writeBuffer
            writeBuffer = tmp

            frameAvailable = true
        }
    }

    private fun renderLoop() {

        while (running) {

            var buffer: ByteBuffer? = null
            var w = 0
            var h = 0

            synchronized(lock) {
                if (frameAvailable) {
                    buffer = readBuffer
                    w = videoWidth
                    h = videoHeight
                    frameAvailable = false
                }
            }

            if (buffer != null) {
                uploadAndDraw(buffer, w, h)
            } else {
                Thread.sleep(2)
            }
        }
    }

    private fun initEGL() {

        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        egl.eglInitialize(eglDisplay, IntArray(2))

        val configAttributes = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, 4,
            EGL10.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        egl.eglChooseConfig(eglDisplay, configAttributes, configs, 1, IntArray(1))

        val contextAttributes = intArrayOf(0x3098, 2, EGL10.EGL_NONE)

        eglContext = egl.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL10.EGL_NO_CONTEXT,
            contextAttributes
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
        GLES20.glUseProgram(programId)

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val aPos = GLES20.glGetAttribLocation(programId, "aPosition")
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPos)

        val aTex = GLES20.glGetAttribLocation(programId, "aTexCoord")
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(programId, "sTexture"),
            0
        )
    }

    private fun uploadAndDraw(buffer: ByteBuffer, w: Int, h: Int) {

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        buffer.position(0)

        if (!textureAllocated || w != lastWidth || h != lastHeight) {

            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGB,
                w,
                h,
                0,
                GLES20.GL_RGB,
                GLES20.GL_UNSIGNED_SHORT_5_6_5,
                buffer
            )

            textureAllocated = true
            lastWidth = w
            lastHeight = h

        } else {

            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                w,
                h,
                GLES20.GL_RGB,
                GLES20.GL_UNSIGNED_SHORT_5_6_5,
                buffer
            )
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        egl.eglSwapBuffers(eglDisplay, eglSurface)
    }

    override fun release() {
        running = false
        renderThread.join()
    }

    private fun releaseInternal() {

        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }

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
        val v = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun loadShader(type: Int, code: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, code)
        GLES20.glCompileShader(s)
        return s
    }
}