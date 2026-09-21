package com.pikafish.chess.core

/**
 * 棋盘常量与基本工具。
 * 棋盘坐标：90 格一维数组，index = y*9 + x；x 0..8 从左到右，y 0..9 从上到下。
 * 棋子用 FEN 字母：大写=红（帅仕相马车炮兵），小写=黑（将士象马车炮卒）。
 * 着法编码：(from shl 8) or to。
 */
object Chess {
    const val START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"
    const val EMPTY = '\u0000'

    val GLYPH: Map<Char, String> = mapOf(
        'K' to "帅", 'A' to "仕", 'B' to "相", 'N' to "马", 'R' to "车", 'C' to "炮", 'P' to "兵",
        'k' to "将", 'a' to "士", 'b' to "象", 'n' to "马", 'r' to "车", 'c' to "炮", 'p' to "卒"
    )

    inline fun isRed(p: Char): Boolean = p < 'a'
    inline fun sideOf(p: Char): Char = if (p < 'a') 'r' else 'b'
    inline fun opp(s: Char): Char = if (s == 'r') 'b' else 'r'
    inline fun x(sq: Int): Int = sq % 9
    inline fun y(sq: Int): Int = sq / 9
    inline fun sq(x: Int, y: Int): Int = y * 9 + x
    inline fun mv(from: Int, to: Int): Int = (from shl 8) or to
    inline fun mvFrom(m: Int): Int = m ushr 8
    inline fun mvTo(m: Int): Int = m and 255

    /** 中文记谱用的列号：红方 9-x（一~九），黑方 x+1（1~9） */
    fun fileNum(sq: Int, side: Char): Int =
        if (side == 'r') 9 - x(sq) else x(sq) + 1

    private val NUM_CN = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    fun numStr(v: Int, side: Char): String =
        if (side == 'r') NUM_CN[v.coerceIn(0, 9)] else v.toString()

    fun glyph(p: Char): String = GLYPH[p] ?: "?"
}

/**
 * 棋盘：局面表示 + 着法生成 + 攻击判定。
 * 这一层是纯逻辑，不依赖任何安卓 API，所以能在 CI 上用普通 JVM 单元测试跑。
 */
class Board private constructor(private val cells: CharArray) {

    var side: Char = 'r'
        private set

    companion object {
        // 方向表放伴生对象里，避免每个 Board 实例各分配一份
        private val HDX = intArrayOf(0, 0, -1, 1)
        private val HDY = intArrayOf(-1, 1, 0, 0)
        private val HHX = intArrayOf(-1, -2, 1, -2, -2, -1, 2, -1, -2, 1, 2, 1, -1, 2, 1, 2)
        private val ADX = intArrayOf(-1, -1, 1, 1)
        private val ADY = intArrayOf(-1, 1, -1, 1)
        private val BDX = intArrayOf(-2, -2, 2, 2)
        private val BDY = intArrayOf(-2, 2, -2, 2)
        private val RDX = intArrayOf(0, 0, -1, 1)
        private val RDY = intArrayOf(-1, 1, 0, 0)
        private val NOX = intArrayOf(-1, 1, -2, 2, -2, 2, -1, 1)
        private val NOY = intArrayOf(-2, -2, -1, -1, 1, 1, 2, 2)
        private val NLX = intArrayOf(0, 0, -1, 1, -1, 1, 0, 0)
        private val NLY = intArrayOf(-1, -1, 0, 0, 0, 0, 1, 1)

        fun start(): Board = fromFen(Chess.START_FEN)

        fun fromFen(fen: String): Board {
            val b = Board(CharArray(90) { Chess.EMPTY })
            val parts = fen.trim().split(" ").filter { it.isNotEmpty() }
            var x = 0
            var y = 0
            for (ch in parts[0]) {
                when {
                    ch == '/' -> { y++; x = 0 }
                    ch in '1'..'9' -> x += ch - '0'
                    else -> {
                        if (x in 0..8 && y in 0..9) b.cells[Chess.sq(x, y)] = ch
                        x++
                    }
                }
            }
            b.side = if (parts.size > 1 && parts[1].startsWith("b")) 'b' else 'r'
            return b
        }
    }

    fun copy(): Board {
        val b = Board(cells.copyOf())
        b.side = side
        return b
    }

    fun pieceAt(sq: Int): Char = cells[sq]

    fun fen(): String {
        val sb = StringBuilder()
        for (y in 0..9) {
            var empty = 0
            for (x in 0..8) {
                val p = cells[Chess.sq(x, y)]
                if (p == Chess.EMPTY) empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(p)
                }
            }
            if (empty > 0) sb.append(empty)
            if (y < 9) sb.append('/')
        }
        sb.append(if (side == 'r') " w" else " b")
        return sb.toString()
    }

    /** 只在 UCI 里给引擎用的局面串（引擎接受带行棋方的标准 FEN） */
    fun fenForEngine(): String = fen()

    fun kingSquare(s: Char): Int {
        val k = if (s == 'r') 'K' else 'k'
        for (i in 0..89) if (cells[i] == k) return i
        return -1
    }

    /** 目标格是否被 bySide 方攻击（只判车/炮/马/兵/将，士象够不到将） */
    fun attackedOn(sq: Int, bySide: Char): Boolean {
        if (sq < 0 || sq > 89) return false
        val x = Chess.x(sq)
        val y = Chess.y(sq)

        for (d in 0..3) {
            val dx = HDX[d]; val dy = HDY[d]
            var cx = x + dx; var cy = y + dy
            var first = true
            while (cx in 0..8 && cy in 0..9) {
                val p = cells[Chess.sq(cx, cy)]
                if (p != Chess.EMPTY) {
                    if (first) {
                        if (Chess.sideOf(p) == bySide) {
                            val t = p.uppercaseChar()
                            if (t == 'R') return true
                            if (t == 'K') {
                                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) == 1) return true
                                if (dx == 0) return true          // 将帅照面（飞将）
                            }
                        }
                        first = false
                    } else {
                        if (Chess.sideOf(p) == bySide && p.uppercaseChar() == 'C') return true
                        break
                    }
                }
                cx += dx; cy += dy
            }
        }

        for (i in 0..7) {
            val hx = x + HHX[i * 2]
            val hy = y + HHX[i * 2 + 1]
            if (hx !in 0..8 || hy !in 0..9) continue
            val p = cells[Chess.sq(hx, hy)]
            if (p == Chess.EMPTY || p.uppercaseChar() != 'N') continue
            if (Chess.sideOf(p) != bySide) continue
            val ddx = x - hx; val ddy = y - hy
            val lx: Int; val ly: Int
            if (kotlin.math.abs(ddx) == 2) { lx = hx + if (ddx > 0) 1 else -1; ly = hy }
            else { lx = hx; ly = hy + if (ddy > 0) 1 else -1 }
            if (cells[Chess.sq(lx, ly)] == Chess.EMPTY) return true
        }

        if (bySide == 'r') {
            if (y + 1 <= 9 && cells[Chess.sq(x, y + 1)] == 'P') return true
            if (y <= 4) {
                if (x > 0 && cells[Chess.sq(x - 1, y)] == 'P') return true
                if (x < 8 && cells[Chess.sq(x + 1, y)] == 'P') return true
            }
        } else {
            if (y - 1 >= 0 && cells[Chess.sq(x, y - 1)] == 'p') return true
            if (y >= 5) {
                if (x > 0 && cells[Chess.sq(x - 1, y)] == 'p') return true
                if (x < 8 && cells[Chess.sq(x + 1, y)] == 'p') return true
            }
        }
        return false
    }

    fun inCheck(s: Char): Boolean = attackedOn(kingSquare(s), Chess.opp(s))

    /** 伪合法着法（含送将），写入 out，返回个数 */
    fun genPseudo(s: Char, out: IntArray, capsOnly: Boolean): Int {
        var n = 0
        val red = s == 'r'
        for (sq in 0..89) {
            val p = cells[sq]
            if (p == Chess.EMPTY || Chess.sideOf(p) != s) continue
            val x = Chess.x(sq)
            val y = Chess.y(sq)
            when (p.uppercaseChar()) {
                'P' -> {
                    val fdy = if (red) -1 else 1
                    val ny = y + fdy
                    if (ny in 0..9) {
                        val to = Chess.sq(x, ny)
                        val m = cells[to]
                        if (m == Chess.EMPTY) { if (!capsOnly) out[n++] = Chess.mv(sq, to) }
                        else if (Chess.sideOf(m) != s) out[n++] = Chess.mv(sq, to)
                    }
                    val crossed = if (red) y <= 4 else y >= 5
                    if (crossed) {
                        for (i in intArrayOf(-1, 1)) {
                            val nx = x + i
                            if (nx !in 0..8) continue
                            val to = Chess.sq(nx, y)
                            val m = cells[to]
                            if (m == Chess.EMPTY) { if (!capsOnly) out[n++] = Chess.mv(sq, to) }
                            else if (Chess.sideOf(m) != s) out[n++] = Chess.mv(sq, to)
                        }
                    }
                }
                'A' -> {
                    for (i in 0..3) {
                        val ax = x + ADX[i]; val ay = y + ADY[i]
                        if (ax !in 3..5) continue
                        if (if (red) (ay < 7 || ay > 9) else (ay < 0 || ay > 2)) continue
                        val to = Chess.sq(ax, ay)
                        val m = cells[to]
                        if (m == Chess.EMPTY) { if (!capsOnly) out[n++] = Chess.mv(sq, to) }
                        else if (Chess.sideOf(m) != s) out[n++] = Chess.mv(sq, to)
                    }
                }
                'B' -> {
                    for (i in 0..3) {
                        val bx = x + BDX[i]; val by = y + BDY[i]
                        if (bx !in 0..8 || by !in 0..9) continue
                        if (if (red) by < 5 else by > 4) continue          // 象不过河
                        if (cells[Chess.sq(x + BDX[i] / 2, y + BDY[i] / 2)] != Chess.EMPTY) continue  // 塞象眼
                        val to = Chess.sq(bx, by)
                        val m = cells[to]
                        if (m == Chess.EMPTY) { if (!capsOnly) out[n++] = Chess.mv(sq, to) }
                        else if (Chess.sideOf(m) != s) out[n++] = Chess.mv(sq, to)
                    }
                }
                'N' -> {
                    for (i in 0..7) {
                        val hx = x + NOX[i]; val hy = y + NOY[i]
                        if (hx !in 0..8 || hy !in 0..9) continue
                        if (cells[Chess.sq(x + NLX[i], y + NLY[i])] != Chess.EMPTY) continue  // 蹩马腿
                        val to = Chess.sq(hx, hy)
                        val m = cells[to]
                        if (m == Chess.EMPTY) { if (!capsOnly) out[n++] = Chess.mv(sq, to) }
                        else if (Chess.sideOf(m) != s) out[n++] = Chess.mv(sq, to)
                    }
                }
                'R', 'C' -> {
                    val isCannon = p.uppercaseChar() == 'C'
                    for (i in 0..3) {
                        var cx = x + RDX[i]; var cy = y + RDY[i]
                        var jumped = false
                        while (cx in 0..8 && cy in 0..9) {
                            val to = Chess.sq(cx, cy)
                            val m = cells[to]
                            if (!isCannon) {
                                if (m == Chess.EMPTY) { if (!capsOnly) out[n++] = Chess.mv(sq, to) }
                                else { if (Chess.sideOf(m) != s) out[n++] = Chess.mv(sq, to); break }
                            } else {
                                if (!jumped) {
                                    if (m == Chess.EMPTY) { if (!capsOnly) out[n++] = Chess.mv(sq, to) }
                                    else jumped = true
                                } else {
                                    if (m != Chess.EMPTY) {
                                        if (Chess.sideOf(m) != s) out[n++] = Chess.mv(sq, to)
                                        break
                                    }
                                }
                            }
                            cx += RDX[i]; cy += RDY[i]
                        }
                    }
                }
                'K' -> {
                    for (i in 0..3) {
                        val kx = x + RDX[i]; val ky = y + RDY[i]
                        if (kx !in 3..5) continue
                        if (if (red) (ky < 7 || ky > 9) else (ky < 0 || ky > 2)) continue
                        val to = Chess.sq(kx, ky)
                        val m = cells[to]
                        if (m == Chess.EMPTY) { if (!capsOnly) out[n++] = Chess.mv(sq, to) }
                        else if (Chess.sideOf(m) != s) out[n++] = Chess.mv(sq, to)
                    }
                }
            }
        }
        return n
    }

    private val tmp = IntArray(300)

    /** 合法着法（过滤自将与飞将）。吃掉对方将帅的着法被排除（合法对局不会出现）。 */
    fun legalMoves(s: Char, out: IntArray): Int {
        val n = genPseudo(s, tmp, false)
        var k = 0
        for (i in 0 until n) {
            val m = tmp[i]
            val from = Chess.mvFrom(m); val to = Chess.mvTo(m)
            val cap = cells[to]
            if (cap == 'K' || cap == 'k') continue
            cells[to] = cells[from]; cells[from] = Chess.EMPTY
            val ok = !attackedOn(kingSquare(s), Chess.opp(s))
            cells[from] = cells[to]; cells[to] = cap
            if (ok) out[k++] = m
        }
        return k
    }

    /** 某格棋子的所有合法落点 */
    fun legalTargets(from: Int, out: IntArray): Int {
        val all = IntArray(200)
        val n = legalMoves(side, all)
        var k = 0
        for (i in 0 until n) if (Chess.mvFrom(all[i]) == from) out[k++] = Chess.mvTo(all[i])
        return k
    }

    /** 落子，返回被吃的子（可能为 EMPTY） */
    fun make(m: Int): Char {
        val from = Chess.mvFrom(m); val to = Chess.mvTo(m)
        val cap = cells[to]
        cells[to] = cells[from]
        cells[from] = Chess.EMPTY
        side = Chess.opp(side)
        return cap
    }

    fun unmake(m: Int, cap: Char) {
        val from = Chess.mvFrom(m); val to = Chess.mvTo(m)
        cells[from] = cells[to]
        cells[to] = cap
        side = Chess.opp(side)
    }
}
