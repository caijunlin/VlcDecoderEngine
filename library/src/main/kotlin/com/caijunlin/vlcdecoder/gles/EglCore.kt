package com.caijunlin.vlcdecoder.gles

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.view.Surface
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * @author caijunlin
 * @date   2026/2/28
 * @description   底层的 EGL14 渲染核心引擎负责管理硬件环境上下文着色器程序的编译执行以及 FBO 内存资源的分配调度
 */
class EglCore {

    // 硬件设备的 EGL 显示链接句柄
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY

    // 全局唯一的主渲染上下文环境
    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private set

    // 描述图形表面像素格式与深度的配置对象
    private var eglConfig: EGLConfig? = null

    // 用于维持 OpenGL 状态机的隐藏离屏虚拟表面对象
    var dummySurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private set

    // 第一阶段 OES 转码着色器程序的唯一标识符
    private var oesProgramId = 0

    // 第一阶段着色器中顶点变换矩阵变量的显存地址位置
    private var uOesTransformMatrixLoc = -1

    // 第一阶段着色器中投影观察矩阵变量的显存地址位置
    private var uOesMvpMatrixLoc = -1

    // 第一阶段着色器中外部 OES 纹理采样器变量的显存地址位置
    private var texOESLoc = -1

    // 第二阶段二维贴图着色器程序的唯一标识符
    private var tex2DProgramId = 0

    // 第二阶段着色器中投影观察矩阵变量的显存地址位置
    private var uTex2DMvpMatrixLoc = -1

    // 第二阶段着色器中标准二维纹理采样器变量的显存地址位置
    private var tex2DLoc = -1

    // 存储标准矩形渲染图元四个角坐标的客户端内存缓冲区
    private val vertexBuffer: FloatBuffer

    // 全局通用的单位矩阵数组用于无缩放的原始映射渲染
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    init {
        // 构建能够覆盖整个渲染视口的标准化顶点坐标序列
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f
        )
        // 将浮点数组转化为硬件可以直接高速读取的直接内存字节缓冲
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).apply { position(0) }
    }

    /**
     * 启动硬件图形引擎环境选择像素配置并编译挂载流水线所需的两套着色器程序
     */
    fun initEGL() {
        // 获取默认屏幕显卡的驱动链接
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        // 设定色彩位深参数要求选用支持 OpenGL ES3 版本的硬件配置
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        // 实例化具有 ES3 能力的全局图形上下文
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext =
            EGL14.eglCreateContext(
                eglDisplay,
                eglConfig,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
            )

        // 构建一个微小的一像素虚拟面板用于激活渲染管线后台运行
        val bufferAttributes = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        dummySurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, bufferAttributes, 0)

        // 将当前线程挂载至主环境
        makeCurrentMain()

        // 编译外部纹理解码程序并捕获所有输入变量入口
        oesProgramId = createOESProgram()
        uOesTransformMatrixLoc = GLES30.glGetUniformLocation(oesProgramId, "uTransformMatrix")
        uOesMvpMatrixLoc = GLES30.glGetUniformLocation(oesProgramId, "uMVPMatrix")
        texOESLoc = GLES30.glGetUniformLocation(oesProgramId, "texOES")

        // 编译内部二维制图程序并捕获所有输入变量入口
        tex2DProgramId = createTex2DProgram()
        uTex2DMvpMatrixLoc = GLES30.glGetUniformLocation(tex2DProgramId, "uMVPMatrix")
        tex2DLoc = GLES30.glGetUniformLocation(tex2DProgramId, "tex2D")
    }

    /**
     * 将上层应用提供的原生视图包装成硬件图形管线可识别的渲染表面
     * @param surface 上层提供的原生画布对象
     * @return 构建成功的 EGL 渲染表面句柄
     */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val surfaceAttributes = intArrayOf(EGL14.EGL_NONE)
        return EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttributes, 0)
    }

    /**
     * 释放废弃的底层图形渲染表面显存资源
     * @param eglSurface 需要被销毁的表面句柄
     */
    fun destroySurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    /**
     * 将硬件环境切换绑定至内部安全的主虚拟面上用于进行无显示的后台数据运算
     * @return 切换操作是否执行成功
     */
    fun makeCurrentMain(): Boolean {
        return EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)
    }

    /**
     * 将硬件环境和上下文绑定至指定的目标渲染面上用于进行上屏绘图操作
     * @param eglSurface 目标输出画布表面
     * @param eglCtx 绑定的硬件上下文对象
     * @return 切换操作是否执行成功
     */
    fun makeCurrent(eglSurface: EGLSurface, eglCtx: EGLContext): Boolean {
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglCtx)
    }

    /**
     * 命令图形底层驱动将后台缓冲区的图像数据推送交换至前台屏幕进行显示
     * @param eglSurface 执行缓冲交换的目标表面
     */
    fun swapBuffers(eglSurface: EGLSurface) {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * 设定硬件级别缓冲区交换操作的垂直同步等待模式以释放并发性能
     * @param interval 等待的刷新周期数传入零即可完全解除阻塞实现异步排队
     */
    fun setSwapInterval(interval: Int) {
        EGL14.eglSwapInterval(eglDisplay, interval)
    }

    /**
     * 在显存中开辟一块指定尺寸的离屏帧缓冲区并挂载配套的二维图形纹理接收数据
     * @param width 缓冲区的像素宽度
     * @param height 缓冲区的像素高度
     * @return 包含生成的 FBO 标识符和挂载纹理标识符的整数数组
     */
    fun createFBO(width: Int, height: Int): IntArray {
        val fbo = IntArray(1)
        val tex = IntArray(1)

        // 激活并申请帧缓冲与普通纹理名
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenTextures(1, tex, 0)

        // 配置目标纹理的内容格式尺寸及边缘防锯齿采样策略
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )

        // 将预设好的纹理嵌入帧缓冲挂载点使其具备离屏作画的能力
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tex[0],
            0
        )

        // 处理完毕后恢复默认通道状态
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return intArrayOf(fbo[0], tex[0])
    }

    /**
     * 释放清理指定的底层离屏帧缓冲区以及配套挂载的二维图形纹理显存
     * @param fboId 目标帧缓冲对象的硬件标识符
     * @param tex2DId 目标二维纹理的硬件标识符
     */
    fun deleteFBO(fboId: Int, tex2DId: Int) {
        if (fboId != -1) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        if (tex2DId != -1) GLES30.glDeleteTextures(1, intArrayOf(tex2DId), 0)
    }

    /**
     * 生成一个专门用于接收外部硬件解码组件数据流的外部扩展纹理句柄
     * @return 构建成功的外部 OES 纹理标识符
     */
    fun generateOESTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val oesTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR.toFloat()
        )
        GLES30.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR.toFloat()
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        return oesTextureId
    }

    /**
     * 清理并销毁指定的图形纹理内存占用空间
     * @param textureId 需要删除的图形纹理硬件标识符
     */
    fun deleteTexture(textureId: Int) {
        if (textureId != -1) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    /**
     * 第一阶段渲染流程执行极其繁重的空间色彩转换将外部 OES 数据压扁描绘至内部二维缓冲画板上
     * @param fboId 承接图像画面的目标帧缓冲区标识符
     * @param oesTextureId 提供原始画面源数据的外部纹理标识符
     * @param transformMatrix 画面原始状态携带的纹理空间姿态纠正矩阵
     * @param width 渲染画板操作视口的像素宽度
     * @param height 渲染画板操作视口的像素高度
     */
    fun drawOESToFBO(
        fboId: Int,
        oesTextureId: Int,
        transformMatrix: FloatArray,
        width: Int,
        height: Int
    ) {
        // 定位并激活专用画板
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, width, height)

        // 唤醒解码着色引擎提供顶点参数
        GLES30.glUseProgram(oesProgramId)
        bindVertexData()

        // 下发变换映射规则参数
        GLES30.glUniformMatrix4fv(uOesTransformMatrixLoc, 1, false, transformMatrix, 0)
        GLES30.glUniformMatrix4fv(uOesMvpMatrixLoc, 1, false, identityMatrix, 0)

        // 指定采样通道喂入外部多媒体材质
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(texOESLoc, 0)

        // 发令执行绘制并即刻清除绑定
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * 第二阶段渲染流程利用极高的性能将准备好的二维图像快速拷贝盖章到指定的屏幕位置区域
     * @param tex2DId 已完成颜色转码处理包含最终静态画面的二维纹理标识符
     * @param mvpMatrix 应用于图元顶点的空间变换及投影投射组合矩阵
     * @param width 最终显像目标视口的物理像素宽度
     * @param height 最终显像目标视口的物理像素高度
     */
    fun drawTex2DScreen(tex2DId: Int, mvpMatrix: FloatArray, width: Int, height: Int) {
        // 定制最终落子的映射画面范围
        GLES30.glViewport(0, 0, width, height)

        // 唤醒盖章着色引擎提供顶点参数
        GLES30.glUseProgram(tex2DProgramId)
        bindVertexData()

        // 依据提供的投影矩阵确定位置和尺寸
        GLES30.glUniformMatrix4fv(uTex2DMvpMatrixLoc, 1, false, mvpMatrix, 0)

        // 喂入预先准备好的静止二维贴图
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex2DId)
        GLES30.glUniform1i(tex2DLoc, 0)

        // 发令执行绘制盖章并断开连接
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * 将指定帧缓冲区内的像素数据抽取到系统内存并翻转为标准格式的安卓位图对象
     * @param fboId 目标帧缓冲对象的硬件标识符
     * @param width 截取画面的像素宽度
     * @param height 截取画面的像素高度
     * @return 包含抽取画面内容的位图对象如果抽取异常则返回空值
     */
    fun readPixelsFromFBO(fboId: Int, width: Int, height: Int): Bitmap {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        buffer.rewind()
        val rawBitmap = createBitmap(width, height)
        rawBitmap.copyPixelsFromBuffer(buffer)

        // 显存原点在左下角而安卓原点在左上角故需在此处将图片做垂直翻转映射处理
        val flipMatrix = android.graphics.Matrix().apply { postScale(1f, -1f) }
        val finalBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, width, height, flipMatrix, true)

        rawBitmap.recycle()
        return finalBitmap
    }

    /**
     * 向显卡管线的各个通道提交激活所需的顶点坐标值与材质纹理映射坐标数据
     */
    private fun bindVertexData() {
        // 提供矩形的绝对位置信息
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)

        // 提供矩形内部对应的材质抓取信息
        vertexBuffer.position(3)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)
    }

    /**
     * 组装编译适用于硬件外部纹理流并支持色彩转换的专用着色器执行工厂代码
     * @return 编译链接完成的专属着色器程序引擎标识符
     */
    private fun createOESProgram(): Int {
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            GLES30.glShaderSource(
                it, """#version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec4 aTexCoord;
            uniform mat4 uTransformMatrix;
            uniform mat4 uMVPMatrix; 
            out vec2 vTexCoord;
            void main() { 
                gl_Position = uMVPMatrix * aPosition; 
                vTexCoord = (uTransformMatrix * aTexCoord).xy; 
            }
        """
            ); GLES30.glCompileShader(it)
        }
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            GLES30.glShaderSource(
                it, """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES texOES;
            layout(location = 0) out vec4 fragColor;
            void main() { fragColor = texture(texOES, vTexCoord); }
        """
            ); GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v); GLES30.glAttachShader(it, f); GLES30.glLinkProgram(it)
        }
    }

    /**
     * 组装编译适用于轻量级平面二维贴图数据直接拷贝输出的标准化着色器执行工厂代码
     * @return 编译链接完成的专属着色器程序引擎标识符
     */
    private fun createTex2DProgram(): Int {
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            GLES30.glShaderSource(
                it, """#version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec4 aTexCoord;
            uniform mat4 uMVPMatrix; 
            out vec2 vTexCoord;
            void main() { 
                gl_Position = uMVPMatrix * aPosition; 
                vTexCoord = aTexCoord.xy; 
            }
        """
            ); GLES30.glCompileShader(it)
        }
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            GLES30.glShaderSource(
                it, """#version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D tex2D;
            layout(location = 0) out vec4 fragColor;
            void main() { fragColor = texture(tex2D, vTexCoord); }
        """
            ); GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v); GLES30.glAttachShader(it, f); GLES30.glLinkProgram(it)
        }
    }

    /**
     * 彻底解绑硬件图形环境并摧毁引擎核心中占用的底层资源池空间防范系统内存泄露风险
     */
    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, dummySurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        dummySurface = EGL14.EGL_NO_SURFACE
        oesProgramId = 0
        tex2DProgramId = 0
    }

}