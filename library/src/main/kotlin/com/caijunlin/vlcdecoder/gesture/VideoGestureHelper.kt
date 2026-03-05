package com.caijunlin.vlcdecoder.gesture

import android.graphics.Bitmap
import android.view.DragEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.core.graphics.scale
import com.caijunlin.vlcdecoder.core.VlcStreamManager

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   系统级拖拽手势处理
 */
class VideoGestureHelper(
    private val surfaceProvider: () -> Surface?,
    private val dragHostView: View,
    private val onDropAction: (centerX: Float, centerY: Float) -> Unit
) {

    private class DragSessionState(
        val touchOffsetX: Float,
        val touchOffsetY: Float,
        val width: Int,
        val height: Int,
        var lastVisualX: Float,
        var lastVisualY: Float
    )

    fun onTouchEvent(event: MotionEvent, width: Int, height: Int): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val touchOffsetX = event.x
            val touchOffsetY = event.y
            val surface = surfaceProvider()
            if (surface != null && surface.isValid) {
                VlcStreamManager.captureFrame(surface) { bitmap ->
                    if (bitmap != null) {
                        startNativeDrag(bitmap, touchOffsetX, touchOffsetY, width, height)
                    }
                }
            }
        }
        return true
    }

    private fun startNativeDrag(
        bitmap: Bitmap,
        touchX: Float,
        touchY: Float,
        width: Int,
        height: Int
    ) {
        val scaledBitmap = bitmap.scale(width, height)
        bitmap.recycle()
        val shadowBuilder = BitmapDragShadowBuilder(scaledBitmap, touchX.toInt(), touchY.toInt())
        val sessionState = DragSessionState(
            touchOffsetX = touchX,
            touchOffsetY = touchY,
            width = width,
            height = height,
            lastVisualX = touchX,
            lastVisualY = touchY
        )
        dragHostView.post {
            setupDragListener()
            dragHostView.startDragAndDrop(
                null,
                shadowBuilder,
                sessionState,
                View.DRAG_FLAG_GLOBAL
            )
        }
    }

    private fun setupDragListener() {
        dragHostView.setOnDragListener { view, event ->
            val state = event.localState as? DragSessionState ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    true
                }

                DragEvent.ACTION_DRAG_LOCATION -> {
                    state.lastVisualX = event.x
                    state.lastVisualY = event.y
                    true
                }

                DragEvent.ACTION_DROP -> {
                    val finalX = state.lastVisualX
                    val finalY = state.lastVisualY
                    val imgTopLeftX = finalX - state.touchOffsetX
                    val imgTopLeftY = finalY - state.touchOffsetY
                    val centerX = imgTopLeftX + (state.width / 2f)
                    val centerY = imgTopLeftY + (state.height / 2f)
                    onDropAction(centerX, centerY)
                    true
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    view.setOnDragListener(null)
                    true
                }

                else -> true
            }
        }
    }
}