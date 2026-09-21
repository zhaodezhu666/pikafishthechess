package com.pikafish.chess.core

/** 一步棋的记录 */
class MoveRecord(
    val move: Int,
    val captured: Char,
    val notation: String,
    val side: Char,
    val gaveCheck: Boolean
)

/** 对局结果 */
class GameResult(val winner: Char?, val reason: String)

/**
 * 对局状态机：落子、悔棋、胜负判定。
 * 只负责规则，不负责搜索——搜索交给 Pikafish。
 */
class Game(val mySide: Char) {

    var board: Board = Board.start()
        private set

    val moves = ArrayList<MoveRecord>()
    /** 每一步之后的局面（含行棋方），用于重复局面判定 */
    private val positions = ArrayList<String>()

    var result: GameResult? = null
        private set
    var over = false
        private set

    init {
        positions.add(board.fen())
    }

    val turn: Char get() = board.side
    fun isMyTurn(): Boolean = board.side == mySide

    /** 某格棋子的合法落点 */
    fun legalTargets(from: Int): IntArray {
        val out = IntArray(200)
        val n = board.legalTargets(from, out)
        return out.copyOf(n)
    }

    fun hasLegalMove(s: Char): Boolean {
        val out = IntArray(300)
        return board.legalMoves(s, out) > 0
    }

    /** 落子（调用方需保证合法） */
    fun play(move: Int) {
        if (over) return
        val side = board.side
        val notation = Notation.toChinese(board, move, side)
        val captured = board.make(move)
        val gaveCheck = board.inCheck(board.side)
        moves.add(MoveRecord(move, captured, notation, side, gaveCheck))
        positions.add(board.fen())
    }

    /**
     * 悔棋：退回到「该我走棋」之前的状态。
     * 最后一步若是对方走的，会连退两步；若是我走的，退一步。
     * @return 是否真的退了
     */
    fun undo(): Boolean {
        if (moves.isEmpty()) return false
        var removedMine = 0
        while (moves.isNotEmpty()) {
            val m = moves.removeAt(moves.size - 1)
            board.unmake(m.move, m.captured)
            if (positions.isNotEmpty()) positions.removeAt(positions.size - 1)
            if (m.side == mySide) removedMine++
            if (removedMine > 0 && board.side == mySide) break
        }
        over = false
        result = null
        return true
    }

    /**
     * 判定对局是否结束。返回 null 表示继续。
     * 规则口径：将死与困毙均判负；三次重复局面判和（循环内含单方长将则长将方判负）；60 回合无吃子判和。
     */
    fun checkOver(): GameResult? {
        val mover = board.side
        if (!hasLegalMove(mover)) {
            val r = if (board.inCheck(mover)) "将死" else "困毙（无着可走）"
            val g = GameResult(Chess.opp(mover), r)
            result = g; over = true; return g
        }

        val cur = positions[positions.size - 1]
        val idxs = ArrayList<Int>()
        for (i in positions.indices) if (positions[i] == cur) idxs.add(i)
        if (idxs.size >= 3) {
            val start = idxs[idxs.size - 2]
            val end = positions.size - 1
            var rCnt = 0; var bCnt = 0; var rAll = true; var bAll = true
            for (k in start until end) {
                val m = moves.getOrNull(k) ?: continue
                if (m.side == 'r') { rCnt++; if (!m.gaveCheck) rAll = false }
                else { bCnt++; if (!m.gaveCheck) bAll = false }
            }
            val rLong = rCnt > 0 && rAll
            val bLong = bCnt > 0 && bAll
            val g = when {
                rLong && !bLong -> GameResult('b', "红方长将，判红负")
                bLong && !rLong -> GameResult('r', "黑方长将，判黑负")
                else -> GameResult(null, "同一局面三次出现，判和")
            }
            result = g; over = true; return g
        }

        var since = 0
        for (i in moves.indices.reversed()) {
            if (moves[i].captured != Chess.EMPTY) break
            since++
        }
        if (since >= 120) {
            val g = GameResult(null, "连续 60 回合无吃子，判和")
            result = g; over = true; return g
        }
        return null
    }

    /** 认输 */
    fun resign(): GameResult {
        val g = GameResult(Chess.opp(mySide), "主动认输")
        result = g; over = true
        return g
    }

    /** 给引擎的局面串 */
    fun fenForEngine(): String = board.fen()
}
