package com.caijunlin.vlcdecoder.gles

import android.opengl.EGL14
import android.opengl.EGLSurface
import android.view.Surface

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   显示窗口数据模型彻底剥离业务状态仅作为封装物理画布和矩阵参数的纯粹容器
 */
class DisplayWindow(val x5Surface: Surface) {

    // 基于外部物理画布创建的 EGL 渲染目标表面对象
    var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private set

    // 外部画布当前的物理像素宽度
    var physicalW: Int = 0

    // 外部画布当前的物理像素高度
    var physicalH: Int = 0

    // 专属的图形变换矩阵数组用于渲染上屏时的坐标运算
    val mvpMatrix = FloatArray(16)

    /**
     * 根据内部传入的物理画布向底层的 EGL 核心申请创建可渲染的图形表面对象
     * @param eglCore 底层图形渲染引擎核心实例
     */
    fun initEGLSurface(eglCore: EglCore) {
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            eglSurface = eglCore.createWindowSurface(x5Surface)
        }
    }

    /**
     * 销毁当前窗口绑定的 EGL 渲染表面释放相关显存资源
     * @param eglCore 底层图形渲染引擎核心实例
     */
    fun release(eglCore: EglCore) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            eglCore.destroySurface(eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}