package com.pikafish.chess.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.pikafish.chess.core.Board
import com.pikafish.chess.core.Chess

/**
 * 原生棋盘控件：自己用 Canvas 画棋盘网格、河界、九宫、兵炮位标记和 32 枚棋子。
 * 不依赖任何图片资源，全部由代码绘制，所以任何分辨率都清晰、APK 也小。
 */
class BoardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var board: Board? = null
        set(v) { field = v; invalidate() }

    /** 我执哪一方；决定棋盘朝向（我方棋子永远画在上半部） */
    var mySide: Char = 'r'
        set(v) { field = v; invalidate() }

    var selected: Int = -1
        set(v) { field = v; invalidate() }

    var legalTargets: IntArray = IntArray(0)
        set(v) { field = v; invalidate() }

    /** 最近一步（无论谁走的） */
    var lastMove: Int = 0
        set(v) { field = v; invalidate() }

    /** AI 走过的所有落点 */
    var aiMarks: IntArray = IntArray(0)
        set(v) { field = v; invalidate() }

    var checkSquare: Int = -1
        set(v) { field = v; invalidate() }

    var onSquareTap: ((Int) -> Unit)? = null

    private var cell = 0f
    private var ox = 0f
    private var oy = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC6B79C.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDED1B8.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC0AB87.toInt(); textSize = 40f; textAlign = Paint.Align.CENTER
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun mirror(): Boolean = mySide == 'r'

    /** 逻辑坐标 → 屏幕坐标（我方棋子永远在上半部） */
    private fun sx(x: Int): Float {
        val px = if (mirror()) 8 - x else x
        return ox + px * cell
    }

    private fun sy(y: Int): Float {
        val py = if (mirror()) 9 - y else y
        return oy + py * cell
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val usableW = w - paddingLeft - paddingRight
        val usableH = h - paddingTop - paddingBottom
        // 棋盘是 8 格宽 × 9 格高，四周各留约 0.7 格边距
        cell = minOf(usableW / 9.4f, usableH / 10.4f)
        ox = paddingLeft + (usableW - 8 * cell) / 2f
        oy = paddingTop + (usableH - 9 * cell) / 2f
        riverPaint.textSize = cell * 0.62f
        glyphPaint.textSize = cell * 0.62f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cell <= 0f) return

        // 底板
        val bg = Paint().apply { color = 0xFFFAF6EE.toInt() }
        canvas.drawRoundRect(
            RectF(ox - cell * 0.62f, oy - cell * 0.62f, ox + 8 * cell + cell * 0.62f, oy + 9 * cell + cell * 0.62f),
            cell * 0.16f, cell * 0.16f, bg
        )
        canvas.drawRoundRect(
            RectF(ox - cell * 0.62f, oy - cell * 0.62f, ox + 8 * cell + cell * 0.62f, oy + 9 * cell + cell * 0.62f),
            cell * 0.16f, cell * 0.16f, framePaint
        )

        // 横线
        for (y in 0..9) canvas.drawLine(sx(0), sy(y), sx(8), sy(y), linePaint)
        // 竖线（中间断河）
        for (x in 0..8) {
            if (x == 0 || x == 8) {
                canvas.drawLine(sx(x), sy(0), sx(x), sy(9), linePaint)
            } else {
                canvas.drawLine(sx(x), sy(0), sx(x), sy(4), linePaint)
                canvas.drawLine(sx(x), sy(5), sx(x), sy(9), linePaint)
            }
        }
        // 九宫斜线
        canvas.drawLine(sx(3), sy(0), sx(5), sy(2), linePaint)
        canvas.drawLine(sx(5), sy(0), sx(3), sy(2), linePaint)
        canvas.drawLine(sx(3), sy(7), sx(5), sy(9), linePaint)
        canvas.drawLine(sx(5), sy(7), sx(3), sy(9), linePaint)

        // 兵位 / 炮位标记
        val marks = arrayOf(
            intArrayOf(1, 2), intArrayOf(7, 2), intArrayOf(1, 7), intArrayOf(7, 7),
            intArrayOf(0, 3), intArrayOf(2, 3), intArrayOf(4, 3), intArrayOf(6, 3), intArrayOf(8, 3),
            intArrayOf(0, 6), intArrayOf(2, 6), intArrayOf(4, 6), intArrayOf(6, 6), intArrayOf(8, 6)
        )
        for (m in marks) drawTick(canvas, m[0], m[1])

        // 楚河 / 汉界
        val mir = mirror()
        val riverY = oy + 4.5f * cell + cell * 0.22f
        canvas.drawText("楚河", ox + (if (mir) 6f else 2f) * cell, riverY, riverPaint)
        canvas.drawText("汉界", ox + (if (mir) 2f else 6f) * cell, riverY, riverPaint)

        val b = board ?: return

        // ---- 高亮层 ----
        if (checkSquare >= 0) {
            val cx = sx(Chess.x(checkSquare)); val cy = sy(Chess.y(checkSquare))
            dotPaint.color = 0x22D92D20; canvas.drawCircle(cx, cy, cell * 0.58f, dotPaint)
            ringPaint.color = 0xFFD92D20.toInt(); ringPaint.strokeWidth = cell * 0.055f
            canvas.drawCircle(cx, cy, cell * 0.5f, ringPaint)
        }
        for (sq in aiMarks) {
            if (sq == Chess.mvTo(lastMove) && lastMove != 0) continue
            dotPaint.color = 0x88F08A24.toInt()
            canvas.drawCircle(sx(Chess.x(sq)), sy(Chess.y(sq)), cell * 0.1f, dotPaint)
        }
        if (lastMove != 0) {
            val f = Chess.mvFrom(lastMove); val t = Chess.mvTo(lastMove)
            ringPaint.color = 0xFFF08A24.toInt(); ringPaint.strokeWidth = cell * 0.06f
            canvas.drawCircle(sx(Chess.x(f)), sy(Chess.y(f)), cell * 0.47f, ringPaint)
            canvas.drawCircle(sx(Chess.x(t)), sy(Chess.y(t)), cell * 0.47f, ringPaint)
            drawArrow(canvas, sx(Chess.x(f)), sy(Chess.y(f)), sx(Chess.x(t)), sy(Chess.y(t)))
        }
        for (sq in legalTargets) {
            val cx = sx(Chess.x(sq)); val cy = sy(Chess.y(sq))
            if (b.pieceAt(sq) != Chess.EMPTY) {
                ringPaint.color = 0xFF22A06B.toInt(); ringPaint.strokeWidth = cell * 0.06f
                canvas.drawCircle(cx, cy, cell * 0.48f, ringPaint)
            } else {
                dotPaint.color = 0xCC22A06B.toInt()
                canvas.drawCircle(cx, cy, cell * 0.15f, dotPaint)
            }
        }
        if (selected >= 0) {
            ringPaint.color = 0xFF2563EB.toInt(); ringPaint.strokeWidth = cell * 0.065f
            canvas.drawCircle(sx(Chess.x(selected)), sy(Chess.y(selected)), cell * 0.5f, ringPaint)
        }

        // ---- 棋子 ----
        val r = cell * 0.43f
        for (sq in 0..89) {
            val p = b.pieceAt(sq)
            if (p == Chess.EMPTY) continue
            val cx = sx(Chess.x(sq)); val cy = sy(Chess.y(sq))
            val red = Chess.isRed(p)
            val col = if (red) 0xFFC0392B.toInt() else 0xFF3A4048.toInt()
            discPaint.color = 0xFFE8DCC3.toInt()
            canvas.drawCircle(cx, cy, r * 1.04f, discPaint)
            discPaint.color = if (red) 0xFFFFF7F5.toInt() else 0xFFF8F9FB.toInt()
            canvas.drawCircle(cx, cy, r, discPaint)
            ringPaint.color = col; ringPaint.strokeWidth = cell * 0.032f
            canvas.drawCircle(cx, cy, r, ringPaint)
            glyphPaint.color = col
            val fm = glyphPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(Chess.glyph(p), cx, baseline, glyphPaint)
        }
    }

    private fun drawTick(canvas: Canvas, x: Int, y: Int) {
        val o = cell * 0.11f
        val len = cell * 0.16f
        val cx = sx(x); val cy = sy(y)
        val px = if (mirror()) 8 - x else x
        if (px > 0) {
            canvas.drawLine(cx - o - len, cy - o, cx - o, cy - o, linePaint)
            canvas.drawLine(cx - o, cy - o - len, cx - o, cy - o, linePaint)
            canvas.drawLine(cx - o - len, cy + o, cx - o, cy + o, linePaint)
            canvas.drawLine(cx - o, cy + o + len, cx - o, cy + o, linePaint)
        }
        if (px < 8) {
            canvas.drawLine(cx + o + len, cy - o, cx + o, cy - o, linePaint)
            canvas.drawLine(cx + o, cy - o - len, cx + o, cy - o, linePaint)
            canvas.drawLine(cx + o + len, cy + o, cx + o, cy + o, linePaint)
            canvas.drawLine(cx + o, cy + o + len, cx + o, cy + o, linePaint)
        }
    }

    private fun drawArrow(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < cell * 0.8f) return      // 相邻格不画箭头，免得糊成一团
        val ux = dx / len; val uy = dy / len
        val ax = x0 + ux * cell * 0.42f; val ay = y0 + uy * cell * 0.42f
        val bx = x1 - ux * cell * 0.55f; val by = y1 - uy * cell * 0.55f
        arrowPaint.color = 0xE6F08A24.toInt(); arrowPaint.strokeWidth = cell * 0.075f
        canvas.drawLine(ax, ay, bx, by, arrowPaint)
        val nx = -uy; val ny = ux
        val h = cell * 0.22f
        val path = Path()
        path.moveTo(bx, by)
        path.lineTo(bx - nx * h * 0.55f - ux * h, by - ny * h * 0.55f - uy * h)
        path.lineTo(bx + nx * h * 0.55f - ux * h, by + ny * h * 0.55f - uy * h)
        path.close()
        arrowHead.color = 0xF2F08A24.toInt()
        canvas.drawPath(path, arrowHead)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        if (cell <= 0f) return true
        val gx = Math.round((event.x - ox) / cell)
        val gy = Math.round((event.y - oy) / cell)
        if (gx !in 0..8 || gy !in 0..9) return true
        // 离交叉点太远视为误触
        val d = kotlin.math.hypot(
            (event.x - (ox + gx * cell)).toDouble(),
            (event.y - (oy + gy * cell)).toDouble()
        )
        if (d > cell * 0.62) return true
        val lx = if (mirror()) 8 - gx else gx
        val ly = if (mirror()) 9 - gy else gy
        onSquareTap?.invoke(Chess.sq(lx, ly))
        return true
    }
}
