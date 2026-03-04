package com.caijunlin.vlcdecoder.gesture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.view.View

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   原生拖拽阴影构建器
 */
class BitmapDragShadowBuilder(
    private val bitmap: Bitmap,
    private val touchPointX: Int,
    private val touchPointY: Int
) : View.DragShadowBuilder() {

    override fun onProvideShadowMetrics(outShadowSize: Point, outTouchPoint: Point) {
        outShadowSize.set(bitmap.width, bitmap.height)
        outTouchPoint.set(touchPointX, touchPointY)
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

}