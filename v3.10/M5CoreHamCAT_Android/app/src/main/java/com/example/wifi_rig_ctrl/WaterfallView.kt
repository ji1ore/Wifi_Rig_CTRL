package com.ji1ore.wifi_rig_ctrl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class WaterfallView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val BINS = 256
        const val ROWS = 120
    }

    var brightness = 1.0f  // 0.2 - 3.0; scales bin value before color mapping

    private val pixels = IntArray(BINS * ROWS) { Color.BLACK }
    private val bitmap = Bitmap.createBitmap(BINS, ROWS, Bitmap.Config.ARGB_8888)
    private val paint  = Paint().apply { isFilterBitmap = true }
    private val dst    = RectF()

    private data class Marker(var row: Int, val label: String)
    private val markers = mutableListOf<Marker>()

    private val markerLinePaint = Paint().apply {
        color = 0xA0FFFFFF.toInt()
        strokeWidth = 1.5f
    }
    private val markerTextPaint = Paint().apply {
        color = 0xFFFFFF88.toInt()
        textSize = 26f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    private var txFreqHz: Int = 0
    private val txLinePaint = Paint().apply {
        color = 0xC0FF5500.toInt()
        strokeWidth = 2f
    }
    private val txTextPaint = Paint().apply {
        color = 0xFFFF8844.toInt()
        textSize = 24f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    private var filterCenterHz: Int = 0
    private var filterHalfWidthHz: Int = 150
    private val filterBandPaint = Paint().apply {
        color = 0x30FFFF44.toInt()  // 半透明黄
        style = Paint.Style.FILL
    }
    private val filterEdgePaint = Paint().apply {
        color = 0x90FFFF44.toInt()
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val filterCenterLinePaint = Paint().apply {
        color = 0xC0FFFF44.toInt()
        strokeWidth = 1.5f
    }
    private val filterTextPaint = Paint().apply {
        color = 0xFFFFFF88.toInt()
        textSize = 24f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    var onFrequencySelected: ((Int) -> Unit)? = null

    fun setTxFrequency(hz: Int) {
        txFreqHz = hz
        postInvalidate()
    }

    fun setFilterBand(centerHz: Int, halfWidthHz: Int) {
        filterCenterHz = centerHz
        filterHalfWidthHz = halfWidthHz
        postInvalidate()
    }

    fun clearFilterBand() {
        filterCenterHz = 0
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val hz = (event.x / width * 3000).roundToInt().coerceIn(100, 2900)
            setTxFrequency(hz)
            onFrequencySelected?.invoke(hz)
            return true
        }
        return super.onTouchEvent(event)
    }

    fun addLine(bins: IntArray) {
        System.arraycopy(pixels, BINS, pixels, 0, BINS * (ROWS - 1))
        val base = BINS * (ROWS - 1)
        for (i in 0 until minOf(BINS, bins.size)) {
            val v = (bins[i].toFloat() * brightness).toInt().coerceIn(0, 255)
            pixels[base + i] = binToColor(v)
        }
        bitmap.setPixels(pixels, 0, BINS, 0, 0, BINS, ROWS)
        val iter = markers.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            m.row--
            if (m.row < 0) iter.remove()
        }
        postInvalidate()
    }

    fun addPeriodMarker(label: String) {
        markers.removeAll { it.row >= ROWS - 2 }
        markers.add(Marker(ROWS - 1, label))
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bitmap, null, dst, paint)
        // フィルター帯域（半透明オーバーレイ + 中心線 + 周波数）
        if (filterCenterHz > 0) {
            val xLo = ((filterCenterHz - filterHalfWidthHz).coerceAtLeast(0) / 3000f) * width
            val xHi = ((filterCenterHz + filterHalfWidthHz).coerceAtMost(3000) / 3000f) * width
            val xCenter = (filterCenterHz / 3000f) * width
            val r = RectF(xLo, 0f, xHi, height.toFloat())
            canvas.drawRect(r, filterBandPaint)
            canvas.drawRect(r, filterEdgePaint)
            canvas.drawLine(xCenter, 0f, xCenter, height.toFloat(), filterCenterLinePaint)
            canvas.drawText("${filterCenterHz}Hz", (xCenter + 3f).coerceAtMost(width - 90f), 40f, filterTextPaint)
        }
        // TX周波数の垂直線
        if (txFreqHz > 0) {
            val x = (txFreqHz / 3000f) * width
            canvas.drawLine(x, 0f, x, height.toFloat(), txLinePaint)
            canvas.drawText("${txFreqHz}Hz", (x + 3f).coerceAtMost(width - 80f), 24f, txTextPaint)
        }
        // 周期横線
        if (markers.isEmpty()) return
        val scaleY = height.toFloat() / ROWS
        for (m in markers) {
            val y = m.row * scaleY
            canvas.drawLine(0f, y, width.toFloat(), y, markerLinePaint)
            if (y > 14f) canvas.drawText(m.label, 4f, y - 3f, markerTextPaint)
        }
    }

    // black -> blue -> cyan -> yellow -> red
    private fun binToColor(v: Int): Int = when {
        v < 64  -> Color.rgb(0,           0,               v * 4)
        v < 128 -> Color.rgb(0,           (v - 64) * 4,    255)
        v < 192 -> Color.rgb((v-128) * 4, 255,             255 - (v - 128) * 4)
        else    -> Color.rgb(255,         255-(v-192)*4,   0)
    }
}
