package com.ji1ore.wifi_rig_ctrl

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class WaterfallView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var rxFreqHz = 1500
        set(v) { field = v; invalidate() }
    var txFreqHz = 1500
        set(v) { field = v; invalidate() }
    var freqMinHz = 0
        set(v) { field = v; invalidate() }
    var freqMaxHz = 3000

    var onFreqTapped: ((Int) -> Unit)? = null

    @Volatile private var nextRowIsCycleMark = false
    @Volatile private var pendingRows = 0

    /** サイクル境界マーカー（次の addRow で黄色横線を挿入） */
    fun markCycleBoundary() { nextRowIsCycleMark = true }

    private var bmp: Bitmap? = null
    private var pixels = IntArray(0)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint().apply { strokeWidth = 2f; isAntiAlias = false }
    private val labelPaint = Paint().apply {
        color = Color.WHITE; textSize = 24f; isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    private val srcRect = Rect()
    private val dstRectF = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bmp?.recycle()
        if (w > 0 && h > 0) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            pixels = IntArray(w * h)
            bmp!!.eraseColor(Color.BLACK)
        }
    }

    /** FFT magnitude 配列 (0.0〜1.0) を受け取り、上部に新行を追加してスクロール */
    fun addRow(mags: FloatArray) {
        if (pendingRows >= 3) return  // バックログが詰まっていたらスキップ
        pendingRows++
        // All bitmap operations must happen on the main thread to avoid race with onDraw()
        post {
            pendingRows--
            val b = bmp ?: return@post
            val w = b.width
            val h = b.height
            if (pixels.size != w * h) pixels = IntArray(w * h)

            // 全行を1行下にシフト
            System.arraycopy(pixels, 0, pixels, w, w * (h - 1))

            // 新しい行を先頭に書き込む
            if (nextRowIsCycleMark) {
                for (x in 0 until w) pixels[x] = 0xFFFFFF44.toInt()
                nextRowIsCycleMark = false
            } else {
                for (x in 0 until w) {
                    val idx = (x.toFloat() * mags.size / w).toInt().coerceIn(0, mags.size - 1)
                    pixels[x] = toColor(mags[idx])
                }
            }

            b.setPixels(pixels, 0, w, 0, 0, w, h)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        srcRect.set(0, 0, b.width, b.height)
        dstRectF.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(b, srcRect, dstRectF, bmpPaint)

        val vw = width.toFloat()
        val vh = height.toFloat()
        val span = (freqMaxHz - freqMinHz).toFloat().coerceAtLeast(1f)

        // RX カーソル（シアン）
        val rxX = (rxFreqHz - freqMinHz).toFloat() / span * vw
        cursorPaint.color = Color.CYAN
        canvas.drawLine(rxX, 0f, rxX, vh, cursorPaint)
        labelPaint.color = Color.CYAN
        canvas.drawText("RX", rxX + 3f, 28f, labelPaint)

        // TX カーソル（赤）
        val txX = (txFreqHz - freqMinHz).toFloat() / span * vw
        cursorPaint.color = Color.RED
        canvas.drawLine(txX, 0f, txX, vh, cursorPaint)
        labelPaint.color = Color.RED
        canvas.drawText("TX", txX + 3f, 48f, labelPaint)

        // 周波数目盛り（底辺）― 表示範囲内の 500 Hz ステップのみ
        cursorPaint.color = 0x88FFFFFF.toInt()
        labelPaint.color = 0xAAFFFFFF.toInt()
        for (hz in listOf(500, 1000, 1500, 2000, 2500, 3000)) {
            if (hz <= freqMinHz || hz > freqMaxHz) continue
            val x = (hz - freqMinHz).toFloat() / span * vw
            canvas.drawLine(x, vh - 14f, x, vh, cursorPaint)
            canvas.drawText("$hz", x - 16f, vh - 2f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_UP -> {
                val span = (freqMaxHz - freqMinHz).toFloat().coerceAtLeast(1f)
                val freq = (freqMinHz + event.x / width * span).toInt()
                    .coerceIn(freqMinHz + 100, freqMaxHz - 100)
                onFreqTapped?.invoke(freq)
                return true
            }
        }
        return false
    }

    // 0.0→黒→青→シアン→黄 のグラデーション
    private fun toColor(mag: Float): Int {
        val t = mag.coerceIn(0f, 1f)
        return when {
            t < 0.25f -> lerpColor(0xFF000020.toInt(), 0xFF0000C0.toInt(), t / 0.25f)
            t < 0.50f -> lerpColor(0xFF0000C0.toInt(), 0xFF00C0FF.toInt(), (t - 0.25f) / 0.25f)
            t < 0.75f -> lerpColor(0xFF00C0FF.toInt(), 0xFF00FF80.toInt(), (t - 0.50f) / 0.25f)
            else      -> lerpColor(0xFF00FF80.toInt(), 0xFFFFFF00.toInt(), (t - 0.75f) / 0.25f)
        }
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val r = ((c1 shr 16 and 0xFF) + ((c2 shr 16 and 0xFF) - (c1 shr 16 and 0xFF)) * t).toInt()
        val g = ((c1 shr 8  and 0xFF) + ((c2 shr 8  and 0xFF) - (c1 shr 8  and 0xFF)) * t).toInt()
        val b = ((c1         and 0xFF) + ((c2         and 0xFF) - (c1         and 0xFF)) * t).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }
}
