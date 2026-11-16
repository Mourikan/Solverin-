package com.solverin.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.solverin.engine.core.HandClass
import com.solverin.engine.range.SpotStrategy

/** 13x13 matrix: each cell split horizontally raise | call | fold by frequency. */
class RangeGridView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var strategy: SpotStrategy? = null
        set(value) { field = value; invalidate() }

    var onHandSelected: ((Int) -> Unit)? = null

    private val raisePaint = Paint().apply { color = ctx.getColor(R.color.raise) }
    private val callPaint = Paint().apply { color = ctx.getColor(R.color.call) }
    private val foldPaint = Paint().apply { color = ctx.getColor(R.color.fold) }
    private val gridPaint = Paint().apply { color = Color.BLACK; strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val labels = Array(HandClass.COUNT) { HandClass.name(it) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val cell = width / HandClass.GRID.toFloat()
        textPaint.textSize = cell * 0.32f
        val s = strategy
        for (h in 0 until HandClass.COUNT) {
            val x = HandClass.col(h) * cell
            val y = HandClass.row(h) * cell
            val r = s?.raise(h) ?: 0f
            val c = s?.call(h) ?: 0f
            val xr = x + cell * r
            val xc = xr + cell * c
            canvas.drawRect(x, y, xr, y + cell, raisePaint)
            canvas.drawRect(xr, y, xc, y + cell, callPaint)
            canvas.drawRect(xc, y, x + cell, y + cell, foldPaint)
            canvas.drawText(labels[h], x + cell / 2, y + cell * 0.62f, textPaint)
        }
        for (i in 0..HandClass.GRID) {
            val p = i * cell
            canvas.drawLine(p, 0f, p, height.toFloat(), gridPaint)
            canvas.drawLine(0f, p, width.toFloat(), p, gridPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val cell = width / HandClass.GRID.toFloat()
            val col = (e.x / cell).toInt().coerceIn(0, 12)
            val row = (e.y / cell).toInt().coerceIn(0, 12)
            onHandSelected?.invoke(row * HandClass.GRID + col)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
