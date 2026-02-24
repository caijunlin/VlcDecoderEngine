package com.caijunlin.vlcdecoder.core

/**
 * @author : caijunlin
 * @date   : 2026/2/24
 * @description   :
 */
enum class RenderApi {
    /** 强制使用 OpenGL ES 2.0 (兼容性最好) */
    GLES_20,

    /** 强制使用 OpenGL ES 3.0 (性能最好，支持 PBO/Orphaning) */
    GLES_30,

    /** 自动检测：优先尝试 2.0，如果设备不支持则回退到 3.0 */
    AUTO
}