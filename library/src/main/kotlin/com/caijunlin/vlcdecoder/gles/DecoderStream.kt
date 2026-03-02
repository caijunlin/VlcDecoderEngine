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
 * @description   单个视频流的解码核心封装类引入双队列机制实现生命周期与渲染任务的绝对隔离
 */
class DecoderStream(
    val url: String,
    private val eglCore: EglCore,
    private val libVLC: LibVLC?,
    private val renderHandler: Handler,
    private val mediaOptions: ArrayList<String>,
    private val onStreamDead: (String) -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    var oesTextureId = -1
        private set
    var surfaceTexture: SurfaceTexture? = null
        private set
    var fboId = -1
        private set
    var tex2DId = -1
        private set

    private var decodeSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    val transformMatrix = FloatArray(16)

    @Volatile
    var frameAvailable = false
    var hasFirstFrame = false

    var videoWidth = 640
    var videoHeight = 360

    @Volatile
    private var retryCount = 0
    private val maxRetryLimit = 5

    // 维持全量挂载的组件登记册作为内存存活与释放的裁判依据
    val boundWindows = CopyOnWriteArrayList<DisplayWindow>()

    // 仅存放处于活跃态渴望画面的组件作为图形管线无脑分发指令的唯一渠道
    val activeWindows = CopyOnWriteArrayList<DisplayWindow>()

    /**
     * 启动视频解码流申请内部 FBO 和 OES 纹理内存并驱动 VLC 引擎开始解码播放
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
            mediaPlayer?.scale = 0f
            mediaPlayer?.vlcVout?.setWindowSize(videoWidth, videoHeight)
            mediaPlayer?.aspectRatio = "$videoWidth:$videoHeight"
            mediaPlayer?.vlcVout?.setVideoSurface(decodeSurface, null)
            mediaPlayer?.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EndReached -> Log.i("VLCDecoder", "Playback reached end")
                    MediaPlayer.Event.Playing -> {
                        Log.i("VLCDecoder", "Playback started")
                        retryCount = 0
                    }

                    MediaPlayer.Event.EncounteredError -> {
                        Log.e("VLCDecoder", "Playback encountered error")
                        retryCount++
                        if (retryCount <= maxRetryLimit) {
                            Log.w("VLCDecoder", "Preparing to retry connection")
                            renderHandler.postDelayed({ retryPlay() }, 2000L)
                        } else {
                            Log.e("VLCDecoder", "Max retries reached stream declared dead")
                            renderHandler.post { onStreamDead(url) }
                        }
                    }

                    MediaPlayer.Event.Stopped -> Log.i("VLCDecoder", "Playback stopped")
                }
            }
            mediaPlayer?.vlcVout?.attachViews()
            mediaPlayer?.play()
        }
    }

    private fun retryPlay() {
        mediaPlayer?.stop()
        libVLC?.let { vlc ->
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            mediaPlayer?.play()
        }
    }

    fun pause() {
        mediaPlayer?.stop()
    }

    fun resume() {
        libVLC?.let { vlc ->
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            mediaPlayer?.play()
        }
    }

    private fun createMedia(vlc: LibVLC): Media {
        val media = Media(vlc, url.toUri())
        mediaOptions.forEach { option ->
            media.addOption(option)
        }
        return media
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        frameAvailable = true
    }

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