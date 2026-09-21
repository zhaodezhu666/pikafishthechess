package com.pikafish.chess

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.pikafish.chess.core.Chess
import com.pikafish.chess.core.Game
import com.pikafish.chess.engine.UciEngine
import com.pikafish.chess.ui.BoardView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var boardView: BoardView
    private lateinit var statusText: TextView
    private lateinit var moveText: TextView

    private var game: Game? = null
    private var engine: UciEngine? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var thinking = false
    private var engineReady = false
    private var engineStarting = false

    // 默认「强度优先」：6 线程 / 6 秒
    private val thinkOptions = intArrayOf(1500, 3000, 6000, 10000)
    private val thinkLabels = arrayOf("快 · 1.5 秒", "标准 · 3 秒", "强 · 6 秒", "最强 · 10 秒")
    @Volatile private var thinkMs = 6000

    private val threads = 6
    private val hashMb = 256

    /** AI 走过的落点，用于棋盘上的橙色标记 */
    private val aiMarks = ArrayList<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        bootEngine()
        ui.postDelayed({ askSide() }, 400)
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F5F7.toInt())
        }

        statusText = TextView(this).apply {
            text = "引擎启动中…（首次启动要解压 50MB 权重，稍等）"
            textSize = 14f
            setTextColor(0xFF1C1F23.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(6))
        }
        root.addView(statusText)

        boardView = BoardView(this)
        root.addView(
            boardView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(TextView(this).apply {
            text = "我方棋子永远在上半部　·　橙圈=AI 最近一步　·　绿点=可落子"
            textSize = 11.5f
            setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
        })

        val moveWrap = ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(92)
            ).apply { setMargins(dp(12), dp(2), dp(12), dp(6)) }
        }
        moveText = TextView(this).apply {
            text = "尚未落子"
            textSize = 13f
            setTextColor(0xFF5A6169.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        moveWrap.addView(moveText)
        root.addView(moveWrap)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(2))
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(this).apply {
            text = "AI 思考时间"
            textSize = 12.5f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, dp(8), 0)
        })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, thinkLabels
            )
            setSelection(2)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    thinkMs = thinkOptions[position]
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        row1.addView(
            spinner,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        row2.addView(mkButton("悔棋") { doUndo() })
        row2.addView(mkButton("认输") { doResign() })
        row2.addView(mkButton("重开一局") { askSide() })
        root.addView(row2)

        setContentView(root)
    }

    private fun mkButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13.5f
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }

    // --------------------------------------------------------------- 引擎

    private fun bootEngine() {
        if (engineStarting) return
        engineStarting = true
        io.execute {
            val e = UciEngine(applicationContext)
            e.start(threads, hashMb)
            ui.post {
                engine = e
                engineReady = e.ready
                if (e.ready) {
                    statusText.text = "${e.name}　·　${threads} 线程　·　${hashMb}MB 置换表"
                    statusText.setTextColor(0xFF0B7A45.toInt())
                    maybeAiMove()
                } else {
                    statusText.text = "引擎不可用：${e.lastError ?: "未知错误"}"
                    statusText.setTextColor(0xFFD92D20.toInt())
                }
                refresh()
            }
        }
    }

    // --------------------------------------------------------------- 开局

    private fun askSide() {
        if (thinking) return
        AlertDialog.Builder(this)
            .setTitle("选择执子")
            .setItems(arrayOf("我执红（先行）", "我执黑（AI 先行）")) { _, which ->
                startGame(if (which == 0) 'r' else 'b')
            }
            .setCancelable(false)
            .show()
    }

    private fun startGame(side: Char) {
        aiMarks.clear()
        val g = Game(side)
        game = g
        thinking = false
        boardView.mySide = side
        boardView.board = g.board
        boardView.selected = -1
        boardView.legalTargets = IntArray(0)
        boardView.lastMove = 0
        boardView.checkSquare = -1
        boardView.onSquareTap = { sq -> onTap(sq) }
        refresh()
        maybeAiMove()
    }

    // --------------------------------------------------------------- 交互

    private fun onTap(sq: Int) {
        val g = game ?: return
        if (thinking || !engineReady || g.over || !g.isMyTurn()) return

        val sel = boardView.selected
        if (sel >= 0 && boardView.legalTargets.contains(sq)) {
            g.play(Chess.mv(sel, sq))
            boardView.selected = -1
            boardView.legalTargets = IntArray(0)
            boardView.lastMove = g.moves.last().move
            refresh()
            if (g.checkOver() != null) { showResult(); return }
            maybeAiMove()
            return
        }

        val p = g.board.pieceAt(sq)
        if (p != Chess.EMPTY && Chess.sideOf(p) == g.mySide) {
            boardView.selected = sq
            boardView.legalTargets = g.legalTargets(sq)
        } else {
            boardView.selected = -1
            boardView.legalTargets = IntArray(0)
        }
        boardView.invalidate()
    }

    private fun maybeAiMove() {
        val g = game ?: return
        if (!engineReady || g.over || g.isMyTurn() || thinking) return
        thinking = true
        val fen = g.fenForEngine()
        val ms = thinkMs
        refresh()
        io.execute {
            val e = engine
            val res = e?.search(fen, ms, 1) ?: emptyList()
            ui.post {
                thinking = false
                val gg = game ?: return@post
                if (gg.over) { refresh(); return@post }
                val mv = uciToMove(res.firstOrNull()?.bestMove ?: "")
                if (mv == 0) {
                    statusText.text = "引擎没有返回着法（可重开一局再试）"
                    refresh()
                    return@post
                }
                gg.play(mv)
                boardView.lastMove = gg.moves.last().move
                aiMarks.add(Chess.mvTo(mv))
                refresh()
                if (gg.checkOver() != null) showResult()
            }
        }
    }

    /** UCI 坐标（如 h2e2）→ 内部编码。列 a-i 从左到右，行 0-9 从红方底线往上数。 */
    private fun uciToMove(uci: String): Int {
        if (uci.length < 4) return 0
        return try {
            val fx = uci[0].lowercaseChar() - 'a'
            val fy = 9 - (uci[1] - '0')
            val tx = uci[2].lowercaseChar() - 'a'
            val ty = 9 - (uci[3] - '0')
            if (fx !in 0..8 || tx !in 0..8 || fy !in 0..9 || ty !in 0..9) 0
            else Chess.mv(Chess.sq(fx, fy), Chess.sq(tx, ty))
        } catch (_: Exception) {
            0
        }
    }

    private fun doUndo() {
        val g = game ?: return
        if (thinking) return
        if (!g.undo()) return
        if (aiMarks.isNotEmpty()) aiMarks.removeAt(aiMarks.size - 1)
        boardView.selected = -1
        boardView.legalTargets = IntArray(0)
        boardView.lastMove = if (g.moves.isEmpty()) 0 else g.moves.last().move
        refresh()
    }

    private fun doResign() {
        val g = game ?: return
        if (thinking || g.over) return
        g.resign()
        refresh()
        showResult()
    }

    // --------------------------------------------------------------- 刷新

    private fun refresh() {
        val g = game ?: return
        boardView.board = g.board
        boardView.aiMarks = aiMarks.toIntArray()
        boardView.checkSquare =
            if (!g.over && g.board.inCheck(g.turn)) g.board.kingSquare(g.turn) else -1

        statusText.text = when {
            !engineReady -> "引擎启动中…（首次启动要解压 50MB 权重，稍等）"
            g.over -> "对局结束"
            thinking -> "AI 正在推演（最多 $thinkMs 毫秒）…"
            g.isMyTurn() -> if (g.board.inCheck(g.mySide)) "你被将军，必须应将" else "轮到你走"
            else -> "AI 走子中…"
        }

        val sb = StringBuilder()
        var i = 0
        while (i < g.moves.size) {
            sb.append(String.format("%2d. ", i / 2 + 1))
            sb.append(pad(g.moves[i].notation))
            if (i + 1 < g.moves.size) sb.append(pad(g.moves[i + 1].notation))
            if (i + 2 < g.moves.size) sb.append('\n')
            i += 2
        }
        moveText.text = if (sb.isEmpty()) "尚未落子" else sb.toString()
        boardView.invalidate()
    }

    /** 中文按 2 个字符宽对齐，让两栏着法看起来整齐 */
    private fun pad(s: String): String {
        var w = 0
        for (c in s) w += if (c.code > 127) 2 else 1
        val target = 11
        return if (w >= target) "$s " else s + " ".repeat(target - w)
    }

    private fun showResult() {
        val g = game ?: return
        val r = g.result ?: return
        val title = when {
            r.winner == null -> "和棋"
            r.winner == g.mySide -> "你赢了"
            else -> "AI 获胜"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("${r.reason}　·　共 ${(g.moves.size + 1) / 2} 回合")
            .setPositiveButton("悔棋再战") { _, _ -> doUndo() }
            .setNeutralButton("再来一局") { _, _ -> askSide() }
            .setNegativeButton("看棋盘", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        io.shutdownNow()
    }
}
