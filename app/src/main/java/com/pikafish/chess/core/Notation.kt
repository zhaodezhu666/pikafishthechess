package com.pikafish.chess.core

/** 中文记谱（如「炮二平五」「马8进7」「前车进一」） */
object Notation {

    /**
     * 把着法翻译成中文记谱。必须在【落子之前】的局面上调用。
     */
    fun toChinese(board: Board, move: Int, side: Char): String {
        val from = Chess.mvFrom(move)
        val to = Chess.mvTo(move)
        val p = board.pieceAt(from)
        if (p == Chess.EMPTY) return "??"

        val t = p.uppercaseChar()
        val name = Chess.glyph(p)
        val xf = Chess.x(from); val yf = Chess.y(from)
        val xt = Chess.x(to); val yt = Chess.y(to)

        // 同一列上若有多个同名子，用 前/中/后 代替列号
        val same = ArrayList<Int>(4)
        for (y in 0..9) {
            val q = board.pieceAt(Chess.sq(xf, y))
            if (q == p) same.add(Chess.sq(xf, y))
        }
        var prefix = ""
        if (same.size >= 2) {
            same.sort()
            val idx = same.indexOf(from)
            val ord = if (side == 'r') idx else same.size - 1 - idx
            val l = same.size
            prefix = when {
                l == 2 -> if (ord == 0) "前" else "后"
                l == 3 -> if (ord == 0) "前" else if (ord == 1) "中" else "后"
                else -> if (ord == 0) "前" else if (ord == l - 1) "后" else "中"
            }
        }
        val head = if (prefix.isNotEmpty()) prefix + name
        else name + Chess.numStr(Chess.fileNum(from, side), side)

        val forward = if (side == 'r') yt < yf else yt > yf
        val action = when {
            yt == yf -> "平"
            forward -> "进"
            else -> "退"
        }
        // 平走、以及马/象/士的进退，都写目标列号；直线子写步数
        val tail = if (action == "平" || t == 'N' || t == 'B' || t == 'A') {
            Chess.numStr(Chess.fileNum(to, side), side)
        } else {
            Chess.numStr(kotlin.math.abs(yt - yf), side)
        }
        return head + action + tail
    }
}
