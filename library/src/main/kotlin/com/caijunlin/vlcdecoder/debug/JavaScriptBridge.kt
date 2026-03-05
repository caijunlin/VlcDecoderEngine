package com.caijunlin.vlcdecoder.debug

import android.webkit.JavascriptInterface
import com.caijunlin.vlcdecoder.gles.VlcRenderPool

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   提供给前端 X5 网页环境调用的 JS 接口桥梁
 */
class JavaScriptBridge {

    /**
     * 诊断探针：命令底层渲染池在日志控制台中打印出当前的内存挂载树与解码器工作状态。
     */
    @JavascriptInterface
    fun printVLCDiagnostics() {
        // 直接调用我们之前写好的底层全息状态透视方法
        VlcRenderPool.printDiagnostics()
    }

}