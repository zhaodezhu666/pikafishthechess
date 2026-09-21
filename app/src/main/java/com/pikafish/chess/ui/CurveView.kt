package com.pikafish.chess.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.pikafish.chess.core.EvalPoint
import com.pikafish.chess.core.GameAnalysis
import kotlin.math.max

/**
 * 实时胜率曲线。纯 Canvas 手绘，不依赖任何图表库。
 * 红线区 = 你占优，绿线区 = AI 占优，中间虚线是 50%。
 */
class CurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var points: List<EvalPoint> = emptyList()
        set(v) { field = v; invalidate() }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt(); strokeWidth = 5f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDEFF2.toInt(); strokeWidth = 2f
    }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC7DBFD.toInt(); strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA1AB.toInt(); textSize = 26f
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22C0392B }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x220F9D58 }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val pl = 78f
    private val pr = 26f
    private val pt = 26f
    private val pb = 46f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= pl + pr || h <= pt + pb) return
        val iw = w - pl - pr
        val ih = h - pt - pb
        val y50 = pt + ih * 0.5f

        // 背景 + 网格
        canvas.drawColor(0xFFFCFCFD.toInt())
        for (g in 0..4) {
            val y = pt + ih * g / 4f
            canvas.drawLine(pl, y, w - pr, y, gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${100 - g * 25}", pl - 12f, y + 9f, textPaint)
        }
        canvas.drawLine(pl, y50, w - pr, y50, midPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("50%", w - pr, y50 - 8f, textPaint)

        if (points.size < 2) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("开局后逐着采样", pl + iw / 2f, pt + ih / 2f + 9f, textPaint)
            return
        }

        val n = points.size
        val span = max(12, n - 1).toFloat()
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val p = GameAnalysis.winProb(points[i].cp).toFloat()
            xs[i] = pl + iw * (i / span)
            ys[i] = pt + ih * (1f - p)
        }

        // 曲线与基线的分区填充：跨过 50% 线处按交点切开
        fillRuns(canvas, xs, ys, y50, true, upPaint)
        fillRuns(canvas, xs, ys, y50, false, downPaint)

        // 折线
        val path = Path()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) path.lineTo(xs[i], ys[i])
        canvas.drawPath(path, linePaint)

        // 当前点
        val last = points[n - 1]
        dotPaint.color = if (last.cp >= 0) 0xFFC0392B.toInt() else 0xFF0F9D58.toInt()
        canvas.drawCircle(xs[n - 1], ys[n - 1], 11f, dotPaint)

        // 轴标签
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("开局", pl, h - 14f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("第 ${(last.ply + 1) / 2} 回合", w - pr, h - 14f, textPaint)
    }

    /** 把折线在基线处切开，分别填充基线上方 / 下方的区域 */
    private fun fillRuns(
        canvas: Canvas, xs: FloatArray, ys: FloatArray, y50: Float, above: Boolean, paint: Paint
    ) {
        fun inside(y: Float) = if (above) y <= y50 else y >= y50

        var i = 0
        while (i < xs.size) {
            if (!inside(ys[i])) { i++; continue }
            // 找到一段连续处于目标侧的区间
            val run = ArrayList<Float>()
            if (i > 0 && inside(ys[i - 1]) != inside(ys[i])) {
                val t = (y50 - ys[i - 1]) / (ys[i] - ys[i - 1])
                run.add(xs[i - 1] + (xs[i] - xs[i - 1]) * t); run.add(y50)
            }
            var j = i
            while (j < xs.size && inside(ys[j])) {
                run.add(xs[j]); run.add(ys[j]); j++
            }
            if (j < xs.size) {
                val t = (y50 - ys[j - 1]) / (ys[j] - ys[j - 1])
                run.add(xs[j - 1] + (xs[j] - xs[j - 1]) * t); run.add(y50)
            }
            if (run.size >= 6) {
                val path = Path()
                path.moveTo(run[0], y50)
                var k = 0
                while (k < run.size) { path.lineTo(run[k], run[k + 1]); k += 2 }
                path.lineTo(run[run.size - 2], y50)
                path.close()
                canvas.drawPath(path, paint)
            }
            i = j
        }
    }
}
