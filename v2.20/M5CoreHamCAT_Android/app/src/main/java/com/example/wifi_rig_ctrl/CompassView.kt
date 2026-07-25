package com.ji1ore.wifi_rig_ctrl

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class CompassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var bearingDeg: Float = 0f
        set(v) { field = v; invalidate() }
    var showAllDirs: Boolean = true
        set(v) { field = v; invalidate() }

    private val pFace     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0D1117.toInt(); style = Paint.Style.FILL }
    private val pRing     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt(); style = Paint.Style.STROKE }
    private val pTickMin  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF334455.toInt(); style = Paint.Style.STROKE }
    private val pTickCard = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt(); style = Paint.Style.STROKE }
    private val pNeedle   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt(); style = Paint.Style.FILL }
    private val pTail     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00BCD4.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pCenter   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt(); style = Paint.Style.FILL }
    private val pLabel    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val needlePath = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0) {
            setMeasuredDimension(w, w)   // always square = given width
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 8f || h < 8f) return

        val cx = w / 2f
        val cy = h / 2f

        // Label size based on view size — readable on any screen density
        val labelSz = (minOf(w, h) * 0.10f).coerceIn(12f, 36f)
        pLabel.textSize = labelSz

        // Ring radius: keep space for N/S/E/W labels (each needs ~1.2× label size beyond ring)
        val labelMargin = labelSz * 1.4f
        val r = (minOf(cx, cy) - labelMargin).coerceAtLeast(20f)

        val strokeW = (r * 0.03f).coerceAtLeast(2f)
        pRing.strokeWidth     = strokeW
        pTickMin.strokeWidth  = strokeW * 0.8f
        pTickCard.strokeWidth = strokeW * 0.8f
        pTail.strokeWidth     = (r * 0.055f).coerceAtLeast(3f)

        // ── Compass face ──
        canvas.drawCircle(cx, cy, r, pFace)
        canvas.drawCircle(cx, cy, r, pRing)

        // ── Ticks every 30°, cardinal longer ──
        for (a in 0 until 360 step 30) {
            val isCard = (a % 90 == 0)
            val rr = Math.toRadians(a.toDouble())
            val inner = if (isCard) r * 0.78f else r * 0.90f
            canvas.drawLine(
                cx + sin(rr).toFloat() * r,     cy - cos(rr).toFloat() * r,
                cx + sin(rr).toFloat() * inner,  cy - cos(rr).toFloat() * inner,
                if (isCard) pTickCard else pTickMin
            )
        }

        // ── Direction labels just outside the ring ──
        val ld = r + labelSz * 0.8f          // center of label from compass center
        val baseFix = labelSz * 0.36f        // text baseline → visual-center offset

        canvas.drawText("N", cx, cy - ld + baseFix, pLabel)
        if (showAllDirs) {
            canvas.drawText("S", cx,      cy + ld + baseFix, pLabel)
            canvas.drawText("E", cx + ld, cy + baseFix,      pLabel)
            canvas.drawText("W", cx - ld, cy + baseFix,      pLabel)
        }

        // ── Needle ──
        val rad     = Math.toRadians(bearingDeg.toDouble())
        val backRad = rad + Math.PI
        val perpRad = rad + Math.PI / 2.0

        val tipLen  = r * 0.88f
        val baseLen = tipLen * 0.55f
        val tailLen = r * 0.42f
        val headW   = r * 0.18f

        val tipX  = cx + sin(rad).toFloat()      * tipLen
        val tipY  = cy - cos(rad).toFloat()      * tipLen
        val baseX = cx + sin(rad).toFloat()      * baseLen
        val baseY = cy - cos(rad).toFloat()      * baseLen
        val tailX = cx + sin(backRad).toFloat()  * tailLen
        val tailY = cy - cos(backRad).toFloat()  * tailLen
        val lX    = baseX + sin(perpRad).toFloat() * headW
        val lY    = baseY - cos(perpRad).toFloat() * headW
        val rX    = baseX - sin(perpRad).toFloat() * headW
        val rY    = baseY + cos(perpRad).toFloat() * headW

        // Tail (teal)
        canvas.drawLine(tailX, tailY, baseX, baseY, pTail)

        // Arrowhead (red)
        needlePath.reset()
        needlePath.moveTo(tipX, tipY)
        needlePath.lineTo(lX, lY)
        needlePath.lineTo(rX, rY)
        needlePath.close()
        canvas.drawPath(needlePath, pNeedle)

        // Centre pivot dot
        canvas.drawCircle(cx, cy, r * 0.07f, pCenter)
    }
}
