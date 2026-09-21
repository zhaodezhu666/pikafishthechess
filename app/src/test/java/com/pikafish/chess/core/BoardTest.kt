package com.pikafish.chess.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则层单元测试。
 * 这批断言是从浏览器版（已通过 30/30 的那套）逐条搬过来的。
 * CI 上先跑它们，测试不过就不出 APK —— 这是移植正确性的唯一保障。
 */
class BoardTest {

    private fun mv(fx: Int, fy: Int, tx: Int, ty: Int) =
        Chess.mv(Chess.sq(fx, fy), Chess.sq(tx, ty))

    private fun legalList(b: Board, s: Char): List<Int> {
        val out = IntArray(300)
        val n = b.legalMoves(s, out)
        return (0 until n).map { out[it] }
    }

    private fun firstLegal(g: Game): Int {
        val out = IntArray(300)
        g.board.legalMoves(g.turn, out)
        return out[0]
    }

    // ---------------- 走法生成 ----------------

    @Test
    fun startPositionHas44MovesForBothSides() {
        val b = Board.start()
        assertEquals("红方开局合法着法", 44, legalList(b, 'r').size)
        assertEquals("黑方开局合法着法", 44, legalList(b, 'b').size)
    }

    @Test
    fun fenRoundTrip() {
        assertEquals(Chess.START_FEN + " w", Board.start().fen())
    }

    @Test
    fun startPositionNobodyInCheck() {
        val b = Board.start()
        assertFalse(b.inCheck('r'))
        assertFalse(b.inCheck('b'))
    }

    @Test
    fun noLegalMoveLeavesOwnKingExposed() {
        val b = Board.start()
        for (s in charArrayOf('r', 'b')) {
            for (m in legalList(b, s)) {
                val cap = b.make(m)
                val bad = b.attackedOn(b.kingSquare(s), Chess.opp(s))
                b.unmake(m, cap)
                assertFalse(
                    "着法 ${Chess.mvFrom(m)}->${Chess.mvTo(m)} 导致自将/飞将", bad
                )
            }
        }
    }

    @Test
    fun elephantCannotCrossRiver() {
        val b = Board.fromFen("3k5/9/9/9/9/9/9/9/9/2B1K4 w")
        val rookFileMoves = legalList(b, 'r').filter { Chess.mvFrom(it) == Chess.sq(2, 9) }
        assertTrue("红相至少能动", rookFileMoves.isNotEmpty())
        assertTrue("红相不能过河", rookFileMoves.all { Chess.y(Chess.mvTo(it)) >= 5 })
    }

    @Test
    fun horseIsBlockedByItsOwnLeg() {
        val b = Board.fromFen("3k5/9/9/9/9/9/9/1N7/1P7/4K4 w")
        val t = legalList(b, 'r').filter { Chess.mvFrom(it) == Chess.sq(1, 7) }
        assertEquals("(1,6) 被己方兵塞住，马只剩 4 着", 4, t.size)
    }

    @Test
    fun horseHasSixMovesWhenFree() {
        val b = Board.fromFen("3k5/9/9/9/9/4P4/9/1N7/9/4K4 w")
        val t = legalList(b, 'r').filter { Chess.mvFrom(it) == Chess.sq(1, 7) }
        assertEquals("无阻挡时马有 6 着", 6, t.size)
    }

    @Test
    fun cannonNeedsScreenToCapture() {
        val b1 = Board.fromFen("3k5/9/9/9/9/9/9/9/9/4K3C w")
        assertFalse("炮无炮架不能吃子", legalList(b1, 'r').any { it == mv(8, 9, 4, 9) })

        val b2 = Board.fromFen("4k4/9/9/9/9/9/9/9/C1P1r4/3K5 w")
        assertTrue(
            "炮隔一子应能吃子",
            legalList(b2, 'r').any { Chess.mvFrom(it) == Chess.sq(0, 8) && Chess.mvTo(it) == Chess.sq(4, 8) }
        )
    }

    @Test
    fun facingKingsRestrictToTwoMoves() {
        val b = Board.fromFen("4k4/9/9/9/9/9/9/9/9/4K4 w")
        assertEquals("双将照面时红方只剩 2 着", 2, legalList(b, 'r').size)
    }

    @Test
    fun rookPinnedByFacingKingsCannotLeaveFile() {
        val b = Board.fromFen("4k4/9/9/9/9/4R4/9/9/9/4K4 w")
        val rook = legalList(b, 'r').filter { Chess.mvFrom(it) == Chess.sq(4, 5) }
        assertTrue("车应当有合法着法", rook.isNotEmpty())
        assertTrue("被飞将牵制的车不能离开该列", rook.all { Chess.x(Chess.mvTo(it)) == 4 })
    }

    @Test
    fun checkmateIsDetected() {
        val b = Board.fromFen("R3k3R/4R4/4R4/9/9/9/9/9/9/4K4 b")
        assertEquals("黑方被将死", 0, legalList(b, 'b').size)
        assertTrue(b.inCheck('b'))
    }

    // ---------------- 中文记谱 ----------------

    @Test
    fun chineseNotation() {
        val b = Board.start()
        assertEquals("炮二平五", Notation.toChinese(b, mv(7, 7, 4, 7), 'r'))
        assertEquals("马二进三", Notation.toChinese(b, mv(7, 9, 6, 7), 'r'))
        assertEquals("车一进一", Notation.toChinese(b, mv(8, 9, 8, 8), 'r'))
        assertEquals("兵九进一", Notation.toChinese(b, mv(0, 6, 0, 5), 'r'))
        assertEquals("马8进7", Notation.toChinese(b, mv(7, 0, 6, 2), 'b'))
        assertEquals("车9平8", Notation.toChinese(b, mv(8, 0, 7, 0), 'b'))
        assertEquals("炮2平5", Notation.toChinese(b, mv(1, 2, 4, 2), 'b'))
        assertEquals("卒1进1", Notation.toChinese(b, mv(0, 3, 0, 4), 'b'))
    }

    @Test
    fun frontBackPrefixForSameFile() {
        val b = Board.fromFen("3k5/9/9/9/9/9/9/9/R8/R3K4 w")
        assertTrue(Notation.toChinese(b, mv(0, 8, 1, 8), 'r').startsWith("前车"))
        assertTrue(Notation.toChinese(b, mv(0, 9, 1, 9), 'r').startsWith("后车"))
    }

    // ---------------- 对局状态机 ----------------

    @Test
    fun playThenUndoGoesBackToMyTurn() {
        val g = Game('r')
        repeat(4) { g.play(firstLegal(g)) }
        assertEquals(4, g.moves.size)
        assertTrue(g.undo())
        assertEquals("悔棋后应退 2 步", 2, g.moves.size)
        assertEquals("悔棋后轮到我走", 'r', g.turn)
        assertTrue("悔棋后还能继续落子", g.hasLegalMove('r'))
    }

    @Test
    fun undoWorksAfterResign() {
        val g = Game('r')
        repeat(6) { g.play(firstLegal(g)) }
        val n0 = g.moves.size
        g.resign()
        assertTrue("认输后 over 应为真", g.over)
        assertTrue(g.undo())
        assertFalse("悔棋后对局应恢复", g.over)
        assertNull("悔棋后结果应清空", g.result)
        assertEquals("悔棋后轮到我走", 'r', g.turn)
        assertEquals("步数应回退 2 步", n0 - 2, g.moves.size)
        assertTrue("悔棋后还能继续落子", g.hasLegalMove('r'))
    }

    @Test
    fun undoFromOpeningWhenOnlyAiMoved() {
        val g = Game('b')          // 我执黑，红方（AI）先走
        g.play(firstLegal(g))
        assertEquals("轮到黑方", 'b', g.turn)
        assertTrue(g.undo())
        assertEquals("没有我可退的着法时退到空局", 0, g.moves.size)
        assertEquals("退完后仍是红方走", 'r', g.turn)
    }

    @Test
    fun resignSetsWinner() {
        val g = Game('r')
        val r = g.resign()
        assertTrue(g.over)
        assertEquals('b', r.winner)
    }

    @Test
    fun engineFenTracksSideToMove() {
        val g = Game('r')
        assertEquals(Chess.START_FEN + " w", g.fenForEngine())
        g.play(firstLegal(g))
        assertTrue("走完一步后应轮到黑方", g.fenForEngine().endsWith(" b"))
    }

    @Test
    fun notationRecordedInMoveList() {
        val g = Game('r')
        val cannon = legalList(g.board, 'r').first { Chess.mvFrom(it) == Chess.sq(7, 7) && Chess.mvTo(it) == Chess.sq(4, 7) }
        g.play(cannon)
        assertEquals("炮二平五", g.moves[0].notation)
    }
}
