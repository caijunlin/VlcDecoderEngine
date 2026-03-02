package com.caijunlin.vlcdecoder.gles

import android.graphics.SurfaceTexture
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.core.net.toUri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   单个视频流的解码核心封装类。负责维护单路 VLC 播放器实例、管理内部 OES 纹理、生命周期监控，并利用高阶函数向外部反馈异常熔断事件。
 * @param url 绑定的目标视频网络地址
 * @param eglCore 底层图形渲染引擎核心实例，用于生成纹理
 * @param libVLC VLC 引擎底层工厂实例
 * @param renderHandler 渲染主线程的通讯管道
 * @param mediaOptions 外围透传的个性化媒体装载参数
 * @param onStreamDead 当流经历多次重试依然失败时，向渲染池呼救的闭包回调
 */
class DecoderStream(
    val url: String,
    private val eglCore: EglCore,
    private val libVLC: LibVLC?,
    private val renderHandler: Handler,
    private val mediaOptions: ArrayList<String>?,
    private val onStreamDead: (String) -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    // 接收 VLC 硬件解码吐出图形数据的底层 OES 纹理标识符
    var oesTextureId = -1
        private set

    // 包装 OES 纹理的表面对象，负责接收硬件缓冲区推流并监听新帧到达
    var surfaceTexture: SurfaceTexture? = null
        private set

    // 当前视频流私有的帧缓冲对象(Frame Buffer Object)标识符，用于离屏降维渲染
    var fboId = -1
        private set

    // 挂载在 FBO 上的标准二维图形纹理标识符，供后续多路分发上屏使用
    var tex2DId = -1
        private set

    // 供给 VLC 引擎用于视频硬解输出的安卓原生物理表面
    private var decodeSurface: Surface? = null

    // 负责解码与播放当前网络流的真实播放器引擎实例
    private var mediaPlayer: MediaPlayer? = null

    // 存储从 SurfaceTexture 获取的 OES 纹理坐标变换矩阵
    val transformMatrix = FloatArray(16)

    // 标记底层图形队列是否已经有新的视频帧解码完毕等待 OpenGL 更新
    @Volatile
    var frameAvailable = false

    // 标记当前视频流是否已经成功完成了首帧画面的提取操作
    var hasFirstFrame = false

    // 底层硬解输出与 OES 纹理约定的内部渲染宽度
    var videoWidth = 640

    // 底层硬解输出与 OES 纹理约定的内部渲染高度
    var videoHeight = 360

    // 记录当前流在遇到网络断开或解码异常时已尝试重新连接的次数
    @Volatile
    private var retryCount = 0

    // 定义系统允许该视频流进行连续异常重连的最大容忍次数，超限则判定为死流
    private val maxRetryLimit = 5

    // 暴露给外部诊断系统的状态变量，用于甄别 VLC 引擎是否真正处于运转吐画状态
    @Volatile
    var isDecoding = false
        private set

    // 生命周期羁绊池：订阅了当前视频流画面的所有外部显示窗口集合，使用并发集合保障线程安全
    val displayWindows = CopyOnWriteArrayList<DisplayWindow>()

    /**
     * 启动视频解码流。申请内部 FBO 和 OES 纹理显存，构建硬件解码通道并驱动 VLC 引擎开始拉流。
     */
    fun start() {
        val fboData = eglCore.createFBO(videoWidth, videoHeight)
        fboId = fboData[0]
        tex2DId = fboData[1]

        oesTextureId = eglCore.generateOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener(this@DecoderStream, renderHandler)
        }
        decodeSurface = Surface(surfaceTexture)

        libVLC?.let { vlc ->
            mediaPlayer = MediaPlayer(vlc)
            val media = createMedia(vlc)
            mediaPlayer?.media = media

            // 内存防漏核心，将 Media 对象投喂给播放器后立即释放外层 Java 壳子，防止 GC 触发 C++ 底层断言崩溃
            media.release()

            mediaPlayer?.scale = 0f
            mediaPlayer?.vlcVout?.setWindowSize(videoWidth, videoHeight)
            mediaPlayer?.aspectRatio = "$videoWidth:$videoHeight"

            mediaPlayer?.vlcVout?.setVideoSurface(decodeSurface, null)
            mediaPlayer?.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EndReached -> {
                        isDecoding = false
                        Log.i("VLCDecoder", "Playback reached end")
                    }

                    MediaPlayer.Event.Playing -> {
                        isDecoding = true
                        Log.i("VLCDecoder", "Playback started")
                        retryCount = 0 // 播放成功即重置重试计数器
                    }

                    MediaPlayer.Event.EncounteredError -> {
                        isDecoding = false
                        Log.e("VLCDecoder", "Playback encountered error")
                        retryCount++
                        if (retryCount <= maxRetryLimit) {
                            Log.w("VLCDecoder", "Preparing to retry connection")
                            // 给予硬件喘息时间，2秒后执行重连闭环
                            renderHandler.postDelayed({ retryPlay() }, 2000L)
                        } else {
                            Log.e("VLCDecoder", "Max retries reached stream declared dead")
                            // 重试耗尽，通过闭包通知调度中心彻底抛弃此流
                            renderHandler.post { onStreamDead(url) }
                        }
                    }

                    MediaPlayer.Event.Stopped -> {
                        isDecoding = false
                        Log.i("VLCDecoder", "Playback stopped")
                    }
                }
            }
            mediaPlayer?.vlcVout?.attachViews()
            mediaPlayer?.play()
        }
    }

    /**
     * 执行内部重启媒体源的封装逻辑，供异常重连机制调用。
     */
    private fun retryPlay() {
        mediaPlayer?.stop()
        libVLC?.let { vlc ->
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            media.release() // 再次防漏释放
            mediaPlayer?.play()
        }
    }

    /**
     * 构建统一配置的媒体数据源对象，装载上层下达的专属属性集（如网络缓存、重复模式等）。
     * @param vlc 驱动底层解析的 LibVLC 核心引擎实例
     * @return 携带完整播放配置的底层资源对象
     */
    private fun createMedia(vlc: LibVLC): Media {
        val media = Media(vlc, url.toUri())
        mediaOptions?.forEach { option ->
            media.addOption(option)
        }
        return media
    }

    /**
     * 底层图形缓冲队列接收到新帧数据时的异步回调事件，立起标识位供主时钟轮询拾取。
     * @param st 触发回调的表面纹理对象
     */
    override fun onFrameAvailable(st: SurfaceTexture) {
        frameAvailable = true
    }

    /**
     * 终极清理方法：停止解码、剥离视图，并彻底释放当前流占用的 VLC C++ 内存和 OpenGL 纹理显存。
     */
    fun release() {
        mediaPlayer?.stop()
        mediaPlayer?.vlcVout?.detachViews()
        mediaPlayer?.release()
        mediaPlayer = null
        decodeSurface?.release()
        surfaceTexture?.release()
        eglCore.deleteTexture(oesTextureId)
        eglCore.deleteFBO(fboId, tex2DId)
    }
}