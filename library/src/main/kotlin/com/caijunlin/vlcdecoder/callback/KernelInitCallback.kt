package com.caijunlin.vlcdecoder.callback

/**
 * @author : caijunlin
 * @date   : 2026/2/25
 * @description : 外部统一的内核初始化状态回调抽象类
 */
abstract class KernelInitCallback {

    abstract fun onSuccess(isX5Core: Boolean)

    abstract fun onFailed(code: Int, msg: String?)

}