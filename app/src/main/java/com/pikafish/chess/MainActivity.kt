package com.pikafish.chess

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.pikafish.chess.core.Board
import com.pikafish.chess.core.Chess
import com.pikafish.chess.core.Game
import com.pikafish.chess.core.GameAnalysis
import com.pikafish.chess.core.Notation
import com.pikafish.chess.core.Opening
import com.pikafish.chess.engine.EngineInfo
import com.pikafish.chess.engine.UciEngine
import com.pikafish.chess.ui.BoardView
import com.pikafish.chess.ui.CurveView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ---- 视图 ----
    private lateinit var boardView: BoardView
    private lateinit var statusText: TextView
    private lateinit var moveText: TextView
    private lateinit var analysisBox: LinearLayout
    private lateinit var reviewText: TextView
    private lateinit var curveView: CurveView
    private lateinit var curveHead: TextView
    private lateinit var openingText: TextView
    private val tabs = ArrayList<TextView>()
    private val pages = ArrayList<View>()

    // ---- 状态 ----
    private var game: Game? = null
    private var engine: UciEngine? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var thinking = false
    private var engineReady = false
    private var engineStarting = false
    private var anBusy = false

    private val analysis = GameAnalysis()
    private var lastAn: List<EngineInfo> = emptyList()
    private var lastAnPly = -1
    private var anToken = 0

    private val aiMarks = ArrayList<Int>()

    private val thinkOptions = intArrayOf(1500, 3000, 6000, 10000)
    private val thinkLabels = arrayOf("快 1.5 秒", "标准 3 秒", "强 6 秒", "最强 10 秒")
    @Volatile private var thinkMs = 6000
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    private val hashMb = 128
    private val analyzeMs = 1600

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        buildUi()
        bootEngine()
        ui.postDelayed({ askSide() }, 400)
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText(
                    "时间: " + Date() + "\n线程: " + t.name + "\n\n" + sw
                )
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    // ------------------------------------------------------------------ 界面

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F5F7.toInt())
        }

        statusText = TextView(this).apply {
            text = "引擎加载中… 可以先选边、先走棋，AI 就绪后会自动应招"
            textSize = 13.5f
            setTextColor(0xFF1C1F23.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        root.addView(statusText)

        openingText = TextView(this).apply {
            text = ""
            textSize = 11.5f
            setTextColor(0xFF2563EB.toInt())
            setPadding(dp(14), 0, dp(14), dp(4))
        }
        root.addView(openingText)

        boardView = BoardView(this)
        root.addView(
            boardView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // ---- Tab 行 ----
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val names = arrayOf("着法", "分析", "胜率", "复盘")
        for (i in names.indices) {
            val t = TextView(this).apply {
                text = names[i]
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { selectTab(i) }
            }
            tabRow.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tabs.add(t)
        }
        root.addView(tabRow)

        // ---- Tab 内容区 ----
        val content = FrameLayout(this)
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(176))
        )

        val movePage = ScrollView(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        moveText = TextView(this).apply {
            text = "尚未落子"
            textSize = 13f
            setTextColor(0xFF5A6169.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        movePage.addView(moveText)
        content.addView(movePage)
        pages.add(movePage)

        val anPage = ScrollView(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        analysisBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        anPage.addView(analysisBox)
        content.addView(anPage)
        pages.add(anPage)

        val curvePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        curveHead = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, 0, dp(4))
        }
        curvePage.addView(curveHead)
        curveView = CurveView(this)
        curvePage.addView(
            curveView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        content.addView(curvePage)
        pages.add(curvePage)

        val rvPage = ScrollView(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        reviewText = TextView(this).apply {
            text = "对局结束后，这里会列出胜率下滑最多的几手（胜负手）与全局摘要。"
            textSize = 12.5f
            setTextColor(0xFF5A6169.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        rvPage.addView(reviewText)
        content.addView(rvPage)
        pages.add(rvPage)

        // ---- 设置行 ----
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(2))
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(this).apply {
            text = "AI 思考时间"
            textSize = 12.5f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, dp(8), 0)
        })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, thinkLabels)
            setSelection(2)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    thinkMs = thinkOptions[position]
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        row1.addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1)

        // ---- 按钮行 ----
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        row2.addView(mkButton("悔棋") { doUndo() })
        row2.addView(mkButton("认输") { doResign() })
        row2.addView(mkButton("重开") { askSide() })
        row2.addView(mkButton("诊断") { showDiagnostics() })
        root.addView(row2)

        setContentView(root)
        selectTab(0)
    }

    private fun mkButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
    }

    private fun selectTab(i: Int) {
        for (k in tabs.indices) {
            val on = k == i
            tabs[k].setTextColor(if (on) 0xFF2563EB.toInt() else 0xFF9AA1AB.toInt())
            tabs[k].typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tabs[k].setBackgroundColor(if (on) 0xFFEAF1FE.toInt() else 0x00000000)
            pages[k].visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------ 引擎

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
                    statusText.text = "${e.name} · ${e.usedThreads} 线程 · ${e.usedHash}MB" +
                            (if (e.usedDotprod) " · dotprod" else " · 通用版")
                    statusText.setTextColor(0xFF0B7A45.toInt())
                    maybeAiMove()
                } else {
                    statusText.text = "引擎不可用，点「诊断」查看原因"
                    statusText.setTextColor(0xFFD92D20.toInt())
                }
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ 开局

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
        analysis.reset()
        lastAn = emptyList()
        lastAnPly = -1
        anBusy = false
        anToken++
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
        selectTab(0)
        refresh()
        maybeAiMove()
    }

    // ------------------------------------------------------------------ 交互

    private fun onTap(sq: Int) {
        val g = game ?: return
        if (thinking || g.over || !g.isMyTurn()) return

        val sel = boardView.selected
        if (sel >= 0 && boardView.legalTargets.contains(sq)) {
            g.play(Chess.mv(sel, sq))
            boardView.selected = -1
            boardView.legalTargets = IntArray(0)
            boardView.lastMove = g.moves.last().move
            // 我刚走完后，轮到 AI 思考，这期间清掉上一轮分析
            lastAn = emptyList(); lastAnPly = -1
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

    /** 轮到 AI 走：搜索 -> 落子 -> 采胜率点 -> 触发对我方的分析 */
    private fun maybeAiMove() {
        val g = game ?: return
        if (!engineReady || g.over || g.isMyTurn() || thinking) return
        thinking = true
        val fen = g.fenForEngine()
        val sideToMove = g.turn
        val ply = g.moves.size
        val ms = thinkMs
        refresh()
        io.execute {
            val res = engine?.search(fen, ms, 1) ?: emptyList()
            ui.post {
                thinking = false
                val gg = game ?: return@post
                if (gg.over) { refresh(); return@post }
                val top = res.firstOrNull()
                // 顺手采一个胜率点：引擎看到的是「AI 要走」的局面
                val aiCp = top?.cp
                if (aiCp != null) analysis.record(ply, aiCp, gg.mySide, sideToMove)
                val mv = uciToMove(top?.bestMove ?: "")
                if (mv == 0) {
                    statusText.text = "引擎没有返回着法（点「诊断」查看日志）"
                    refresh()
                    return@post
                }
                gg.play(mv)
                boardView.lastMove = gg.moves.last().move
                aiMarks.add(Chess.mvTo(mv))
                refresh()
                if (gg.checkOver() != null) { showResult(); return@post }
                scheduleAnalyze()
            }
        }
    }

    /** 轮到我走：MultiPV=3 分析我的候选着法，同时给胜率曲线补一个点 */
    private fun scheduleAnalyze() {
        val g = game ?: return
        if (!engineReady || g.over || !g.isMyTurn()) return
        val ply = g.moves.size
        val sideToMove = g.turn
        val token = ++anToken
        anBusy = true
        refresh()
        io.execute {
            val res = engine?.search(g.fenForEngine(), analyzeMs, 3) ?: emptyList()
            ui.post {
                val gg = game ?: return@post
                if (token != anToken || gg.moves.size != ply) return@post
                anBusy = false
                lastAn = res
                lastAnPly = ply
                val top = res.firstOrNull()
                val myCp = top?.cp
                if (myCp != null) analysis.record(ply, myCp, gg.mySide, sideToMove)
                refresh()
            }
        }
    }

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
        aiMarks.clear()
        // 重新推进 AI 的落点标记
        for (i in g.moves.indices) {
            val m = g.moves[i]
            if (m.side != g.mySide) aiMarks.add(Chess.mvTo(m.move))
        }
        analysis.truncate(g.moves.size)
        lastAn = emptyList(); lastAnPly = -1
        anToken++
        boardView.selected = -1
        boardView.legalTargets = IntArray(0)
        boardView.lastMove = if (g.moves.isEmpty()) 0 else g.moves.last().move
        refresh()
        scheduleAnalyze()
    }

    private fun doResign() {
        val g = game ?: return
        if (thinking || g.over) return
        g.resign()
        refresh()
        showResult()
    }

    // ------------------------------------------------------------------ 刷新

    private fun refresh() {
        val g = game ?: return
        boardView.board = g.board
        boardView.aiMarks = aiMarks.toIntArray()
        boardView.checkSquare = if (!g.over && g.board.inCheck(g.turn)) g.board.kingSquare(g.turn) else -1

        statusText.text = when {
            !engineReady -> "引擎加载中… 可以先走棋，AI 就绪后会自动应招"
            g.over -> "对局结束"
            thinking -> "AI 正在推演（最多 $thinkMs 毫秒）…"
            g.isMyTurn() -> if (g.board.inCheck(g.mySide)) "你被将军，必须应将" else "轮到你走"
            else -> "AI 走子中…"
        }

        val op = Opening.identify(g.moves)
        openingText.text = if (op.isEmpty()) "" else "布局：$op　·　${Opening.explain(op)}"

        renderMoves(g)
        renderAnalysis(g)
        renderCurve(g)
        renderReview(g)
        boardView.invalidate()
    }

    private fun renderMoves(g: Game) {
        val sb = StringBuilder()
        var i = 0
        while (i < g.moves.size) {
            sb.append(String.format(Locale.US, "%2d. ", i / 2 + 1))
            sb.append(pad(g.moves[i].notation))
            if (i + 1 < g.moves.size) sb.append(pad(g.moves[i + 1].notation))
            if (i + 2 < g.moves.size) sb.append('\n')
            i += 2
        }
        if (g.moves.isNotEmpty()) {
            sb.append("\n\n—— 共 ").append((g.moves.size + 1) / 2).append(" 回合 ——")
        }
        moveText.text = if (sb.isEmpty()) "尚未落子" else sb.toString()
    }

    private fun pad(s: String): String {
        var w = 0
        for (c in s) w += if (c.code > 127) 2 else 1
        val target = 11
        return if (w >= target) "$s " else s + " ".repeat(target - w)
    }

    private fun renderAnalysis(g: Game) {
        analysisBox.removeAllViews()
        fun add(t: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) {
            analysisBox.addView(TextView(this).apply {
                text = t
                textSize = size
                setTextColor(color)
                setPadding(0, dp(top), 0, 0)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            })
        }
        if (lastAnPly != g.moves.size || lastAn.isEmpty()) {
            add(
                if (thinking || anBusy) "正在分析当前局面…" else "轮到你走时会自动给出候选着法",
                12.5f, 0xFF9AA1AB.toInt(), top = 6
            )
            return
        }
        val b = g.board
        val side = g.turn
        for ((idx, info) in lastAn.withIndex()) {
            val cp = info.cp ?: continue
            val pvMoves = info.pv
            if (pvMoves.isEmpty()) continue
            val mv = uciToMove(pvMoves[0])
            if (mv == 0) continue
            val name = Notation.toChinese(b, mv, side)
            val rank = if (idx == 0) "①" else if (idx == 1) "②" else "③"
            val scoreColor = when {
                GameAnalysis.isGood(cp) -> 0xFFC0392B.toInt()
                GameAnalysis.isBad(cp) -> 0xFF0F9D58.toInt()
                else -> 0xFF6B7280.toInt()
            }
            val head = TextView(this).apply {
                text = "$rank $name　${analysis.fmt(cp)}"
                textSize = 15f
                setTextColor(scoreColor)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(if (idx == 0) 2 else 10), 0, 0)
            }
            analysisBox.addView(head)

            val tags = moveTags(b, mv)
            val cont = pvToText(b, pvMoves, 5)
            val sub = TextView(this).apply {
                text = "$tags　·　${GameAnalysis.describe(cp)}\n后续预测：$cont"
                textSize = 11.5f
                setTextColor(0xFF6B7280.toInt())
                setPadding(0, dp(2), 0, 0)
            }
            analysisBox.addView(sub)
        }
        // 与第一候选的差距提示
        if (lastAn.size >= 2) {
            val c0 = lastAn[0].cp ?: 0
            val c1 = lastAn[1].cp ?: 0
            val gap = c0 - c1
            val tip = when {
                gap <= 20 -> "前两选几乎等价，走哪个都行。"
                gap <= 80 -> "第二选略逊约 ${gap} 厘兵，实战也够用。"
                else -> "第一选明显更好，别走第二选（差 ${gap} 厘兵）。"
            }
            analysisBox.addView(TextView(this).apply {
                text = tip
                textSize = 11.5f
                setTextColor(0xFF9AA1AB.toInt())
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun moveTags(b: Board, mv: Int): String {
        val sb = StringBuilder()
        val cap = b.pieceAt(Chess.mvTo(mv))
        if (cap != Chess.EMPTY) sb.append("吃子·得").append(Chess.glyph(cap)).append("　")
        val c = b.copy()
        c.make(mv)
        if (c.inCheck(c.side)) sb.append("将军　")
        val s = sb.toString().trim()
        return if (s.isEmpty()) "引擎优选" else s
    }

    private fun pvToText(b: Board, pv: List<String>, limit: Int): String {
        val c = b.copy()
        var side = c.side
        val out = ArrayList<String>()
        for (u in pv.take(limit)) {
            val mv = uciToMove(u)
            if (mv == 0) break
            out.add(Notation.toChinese(c, mv, side))
            c.make(mv)
            side = c.side
        }
        return out.joinToString(" → ")
    }

    private fun renderCurve(g: Game) {
        curveView.points = analysis.curve
        val cp = analysis.lastCp
        curveHead.text = "你的胜率 " + GameAnalysis.winPct(cp) + "%　·　" +
                GameAnalysis.describe(cp) + "（" + analysis.fmt(cp) + "）　·　已采样 ${analysis.curve.size} 着"
    }

    private fun renderReview(g: Game) {
        if (!g.over) {
            reviewText.text = "对局结束后，这里会列出胜率下滑最多的几手（胜负手）与全局摘要。"
            return
        }
        val sb = StringBuilder()
        sb.append(analysis.summary(g.mySide, g.moves)).append("\n\n")
        val tps = analysis.turningPoints(g.mySide, g.moves, 3)
        if (tps.isEmpty()) {
            reviewText.text = sb.toString()
            return
        }
        sb.append("胜负手（这几步让你的胜率掉得最多）：\n")
        for ((i, t) in tps.withIndex()) {
            sb.append(i + 1).append(". 第 ").append((t.ply + 1) / 2).append(" 回合  ")
            sb.append(t.notation).append("　胜率 ").append(t.beforePct).append("% → ")
            sb.append(t.afterPct).append("%（").append(t.dropPct).append(" 个百分点）\n")
        }
        reviewText.text = sb.toString()
    }

    // ------------------------------------------------------------------ 结果 / 导出

    private fun gameAsText(): String {
        val g = game ?: return ""
        val sb = StringBuilder()
        sb.append("尖头鳗指导棋 · 对局记录\n")
        sb.append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())).append('\n')
        sb.append("我执").append(if (g.mySide == 'r') "红" else "黑").append('\n')
        val op = Opening.identify(g.moves)
        if (op.isNotEmpty()) sb.append("布局：").append(op).append('\n')
        sb.append('\n')
        var i = 0
        while (i < g.moves.size) {
            sb.append(String.format(Locale.US, "%3d. ", i / 2 + 1))
            sb.append(g.moves[i].notation).append("　　")
            if (i + 1 < g.moves.size) sb.append(g.moves[i + 1].notation)
            sb.append('\n')
            i += 2
        }
        val r = g.result
        if (r != null) {
            sb.append("\n结果：")
            sb.append(
                when {
                    r.winner == null -> "和棋"
                    r.winner == g.mySide -> "我胜"
                    else -> "AI 胜"
                }
            )
            sb.append("（").append(r.reason).append("）")
        }
        return sb.toString()
    }

    private fun shareGame() {
        val txt = gameAsText()
        if (txt.isEmpty()) return
        val it = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "尖头鳗指导棋 对局记录")
            putExtra(Intent.EXTRA_TEXT, txt)
        }
        startActivity(Intent.createChooser(it, "分享棋谱"))
    }

    private fun showResult() {
        val g = game ?: return
        val r = g.result ?: return
        val title = when {
            r.winner == null -> "和棋"
            r.winner == g.mySide -> "你赢了"
            else -> "AI 获胜"
        }
        val tps = analysis.turningPoints(g.mySide, g.moves, 1)
        val extra = if (tps.isEmpty()) "" else
            "\n\n最大失误：第 ${(tps[0].ply + 1) / 2} 回合 ${tps[0].notation}（胜率掉 ${-tps[0].dropPct} 个百分点）"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("${r.reason}　·　共 ${(g.moves.size + 1) / 2} 回合$extra")
            .setPositiveButton("悔棋再战") { _, _ -> doUndo() }
            .setNegativeButton("分享棋谱") { _, _ -> shareGame() }
            .setNeutralButton("看复盘") { _, _ -> selectTab(3) }
            .show()
    }

    // ------------------------------------------------------------------ 诊断

    private fun showDiagnostics() {
        val e = engine
        val sb = StringBuilder()
        sb.append("引擎: ").append(e?.name ?: "未启动").append('\n')
        sb.append("使用版本: ").append(
            when {
                e == null -> "—"
                e.usedDotprod -> "dotprod（ARMv8.2 加速）"
                else -> "generic（兼容版）"
            }
        ).append('\n')
        sb.append("CPU 支持 dotprod: ").append(e?.cpuHasDotprod ?: false).append('\n')
        sb.append("CPU 核心数: ").append(Runtime.getRuntime().availableProcessors()).append('\n')
        sb.append("实际生效: ").append(
            if (e == null || e.usedThreads == 0) "—" else "${e.usedThreads} 线程 / ${e.usedHash}MB 置换表"
        ).append('\n')
        sb.append("权重: ").append(
            if (e == null || e.nnueBytes <= 0) "—"
            else String.format(Locale.US, "%.1f MB", e.nnueBytes / 1048576.0)
        ).append('\n')
        sb.append("状态: ").append(if (e?.ready == true) "运行中" else "未就绪").append('\n')
        sb.append("对局: ").append(game?.moves?.size ?: 0).append(" 半步，")
        sb.append("采样 ").append(analysis.curve.size).append(" 点\n")
        val err = e?.lastError
        if (!err.isNullOrBlank()) sb.append("\n!! 最近错误：\n").append(err).append('\n')

        val cf = File(filesDir, "last_crash.txt")
        if (cf.exists()) sb.append("\n=== 上次崩溃 ===\n").append(cf.readText().take(2500)).append('\n')
        sb.append("\n=== 引擎日志 ===\n").append(e?.diagnostics()?.takeLast(2500) ?: "（无）")

        val text = sb.toString()
        AlertDialog.Builder(this)
            .setTitle("诊断信息")
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", text))
                    statusText.text = "诊断信息已复制到剪贴板"
                } catch (_: Exception) {
                }
            }
            .setNeutralButton("清除崩溃记录") { _, _ ->
                try {
                    cf.delete()
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        io.shutdownNow()
    }
}
