package com.caijunlin.vlcdecoder.gles

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * @author caijunlin
 * @date   2026/3/4
 * @description   极速多线程核心调度池作为全局对外唯一的应用接口门面。
 * 通过内部路由算法将高强度的渲染压力均匀分发至底层的多个私有渲染节点
 */
object VlcRenderPool {

    // 维持底层解码的全局唯一工厂引擎入口
    private var libVLC: LibVLC? = null

    // 设定允许并发解析的系统全局最大流数量上限防范榨干算力
    @Volatile
    private var maxStreamLimit = 16

    // 预先建立独立的底层渲染节点负责承接拆分后的并发重压
    private const val NODE_COUNT = 4

    // 初始化节点数组并在节点内部流意外死亡时接收回调清理总路由表
    private val renderNodes = Array(NODE_COUNT) { index ->
        RenderNode("VlcRenderNode-$index") { _, deadSurfaces ->
            deadSurfaces.forEach { surfaceRouteMap.remove(it) }
        }
    }

    // 全局中枢路由字典记录物理画布被分配到了哪个具体视频流以实现跨线程追踪
    private val surfaceRouteMap = ConcurrentHashMap<Surface, String>()

    // 初始化全局解析引擎实体并装载缺省底层优化参数
    fun initLibVLC(context: Context, args: ArrayList<String>) {
        if (libVLC == null) {
            libVLC = LibVLC(context.applicationContext, args)
        }
    }

    // 动态下达修改并发阈值的指令更新安全限制
    fun setMaxStreamCount(maxCount: Int) {
        this.maxStreamLimit = maxCount
    }

    // 核心路由算法执行体根据资源地址的哈希数值取模均匀散列分配至对应节点
    private fun getNodeByUrl(url: String): RenderNode {
        return renderNodes[abs(url.hashCode()) % NODE_COUNT]
    }

    // 接收外部绑定请求通过路由表匹配节点后投递渲染任务至目标线程
    fun bindSurface(
        url: String,
        x5Surface: Surface,
        width: Int,
        height: Int,
        mediaOptions: ArrayList<String>
    ) {
        if (url.isEmpty() || libVLC == null) return

        // 登写入全局路由册并寻址目标线程
        surfaceRouteMap[x5Surface] = url
        val node = getNodeByUrl(url)
        node.handler.post {
            node.handleBind(url, x5Surface, width, height, mediaOptions, libVLC, maxStreamLimit)
        }
    }

    // 接收外部解绑请求循迹找出归属节点投递物理剥离指令
    fun unbindSurface(url: String, x5Surface: Surface) {
        surfaceRouteMap.remove(x5Surface)
        val node = getNodeByUrl(url)
        node.handler.post { node.handleUnbind(url, x5Surface) }
    }

    // 安全执行跨节点的底层视频源切换仪式防范图形表面挂载崩溃
    fun switchUrl(
        oldUrl: String,
        newUrl: String,
        x5Surface: Surface,
        width: Int,
        height: Int,
        mediaOptions: ArrayList<String>
    ) {
        if (newUrl.isEmpty() || libVLC == null) return

        // 第一时间更新中枢路由表保证后续诸如尺寸改变等操作去往崭新节点
        surfaceRouteMap[x5Surface] = newUrl

        val oldNode = if (oldUrl.isNotEmpty()) getNodeByUrl(oldUrl) else null
        val newNode = getNodeByUrl(newUrl)

        if (oldNode != null && oldNode !== newNode) {
            // 触发跨节点切换必须在旧节点彻底解绑退让后方可将绑定任务委派给新节点
            oldNode.handler.post {
                oldNode.handleUnbind(oldUrl, x5Surface)
                newNode.handler.post {
                    newNode.handleBind(
                        newUrl,
                        x5Surface,
                        width,
                        height,
                        mediaOptions,
                        libVLC,
                        maxStreamLimit
                    )
                }
            }
        } else if (oldNode === newNode) {
            // 源目标与新目标同属一个节点在单一线程队列里先后排队投递即可保障串行安全
            oldNode.handler.post {
                oldNode.handleUnbind(oldUrl, x5Surface)
                oldNode.handleBind(
                    newUrl,
                    x5Surface,
                    width,
                    height,
                    mediaOptions,
                    libVLC,
                    maxStreamLimit
                )
            }
        } else {
            // 不存在旧源的单纯新增情况直接寻址绑定
            newNode.handler.post {
                newNode.handleBind(
                    newUrl,
                    x5Surface,
                    width,
                    height,
                    mediaOptions,
                    libVLC,
                    maxStreamLimit
                )
            }
        }
    }

    // 透传前端画布的尺寸异动数据至对应掌控的渲染节点
    fun resizeSurface(x5Surface: Surface, width: Int, height: Int) {
        val url = surfaceRouteMap[x5Surface] ?: return
        val node = getNodeByUrl(url)
        node.handler.post { node.handleResize(x5Surface, width, height) }
    }

    // 提供非阻塞提取净显像数据服务供外部处理
    fun captureFrame(x5Surface: Surface, callback: (Bitmap?) -> Unit) {
        val url = surfaceRouteMap[x5Surface]
        if (url == null) {
            Handler(Looper.getMainLooper()).post { callback(null) }
            return
        }
        val node = getNodeByUrl(url)
        node.handler.post { node.handleCapture(x5Surface, callback) }
    }

    // 接收指令强行清空残留画面恢复底层原本形态
    fun clearSurface(x5Surface: Surface) {
        val url = surfaceRouteMap[x5Surface]

        // 鉴于清屏操作可能在完全剥离后触发若无路由记录则交由首位节点代劳提供临时环境
        val node = if (url != null) getNodeByUrl(url) else renderNodes[0]
        node.handler.post { node.handleClearSurface(x5Surface) }
    }

    // 统筹下达命令要求所有节点上报诊断树结构
    fun printDiagnostics() {
        Log.w("VLCDecoder", "ENGINE DIAGNOSTICS")
        var totalStreams = 0
        renderNodes.forEach { totalStreams += it.streams.size }
        Log.w("VLCDecoder", "Total Active Decoders $totalStreams Limit $maxStreamLimit")

        // 委派给各个独立分支去打印自己名下管控的流及画面数据阵列
        renderNodes.forEachIndexed { index, node ->
            node.printNodeDiagnostics(index)
        }
    }

    // 终极清理指令排空路由矩阵并通知麾下全员自毁
    fun releaseAll() {
        surfaceRouteMap.clear()
        renderNodes.forEach { node ->
            node.handler.post { node.releaseAll() }
        }
        libVLC?.release()
        libVLC = null
    }
}