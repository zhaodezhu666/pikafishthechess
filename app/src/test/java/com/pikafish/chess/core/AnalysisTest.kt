package com.pikafish.chess.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 二期新增逻辑（布局识别 / 胜率换算 / 曲线 / 复盘）的单元测试 */
class AnalysisTest {

    private fun rec(n: String, s: Char) = MoveRecord(0, Chess.EMPTY, n, s, false)

    // ---------------- 布局识别 ----------------

    @Test
    fun openingIdentifyMiddleCannonVsScreenHorse() {
        val ms = listOf(
            rec("炮二平五", 'r'), rec("马8进7", 'b'),
            rec("马二进三", 'r'), rec("马2进3", 'b')
        )
        assertEquals("中炮对屏风马", Opening.identify(ms))
    }

    @Test
    fun openingIdentifySingleCannonOnly() {
        // 只有一匹马起来时不应误判成屏风马
        assertEquals("中炮对单提马", Opening.identify(listOf(rec("炮二平五", 'r'), rec("马8进7", 'b'))))
    }

    @Test
    fun openingIdentifyVarious() {
        assertEquals("仙人指路", Opening.identify(listOf(rec("兵七进一", 'r'))))
        assertEquals("飞相局", Opening.identify(listOf(rec("相三进五", 'r'))))
        assertEquals("中炮对顺手炮", Opening.identify(listOf(rec("炮二平五", 'r'), rec("炮8平5", 'b'))))
        assertEquals("起马局对挺卒", Opening.identify(listOf(rec("马二进三", 'r'), rec("卒7进1", 'b'))))
        assertEquals("", Opening.identify(emptyList()))
    }

    @Test
    fun openingExplainNonEmptyForKnown() {
        assertTrue(Opening.explain("中炮对屏风马").isNotEmpty())
        assertEquals("", Opening.explain(""))
    }

    // ---------------- 胜率换算 ----------------

    @Test
    fun winProbConversion() {
        assertEquals(50, GameAnalysis.winPct(0))
        assertEquals(73, GameAnalysis.winPct(330))
        assertEquals(27, GameAnalysis.winPct(-330))
        assertEquals(100, GameAnalysis.winPct(GameAnalysis.MATE))
        assertEquals(0, GameAnalysis.winPct(-GameAnalysis.MATE))
    }

    @Test
    fun describeAndFlags() {
        assertTrue(GameAnalysis.describe(500).contains("明显占优"))
        assertTrue(GameAnalysis.describe(-500).contains("明显占优"))
        assertTrue(GameAnalysis.describe(0).contains("均势"))
        assertTrue(GameAnalysis.isGood(100))
        assertTrue(GameAnalysis.isBad(-100))
    }

    // ---------------- 曲线采样 ----------------

    @Test
    fun recordConvertsToMyPerspective() {
        val ga = GameAnalysis()
        ga.reset()
        // 我执红。刚走完，轮到黑：引擎给的是黑方视角，我方应取反
        ga.record(1, -50, 'r', 'b')
        assertEquals(50, ga.lastCp)
        // 黑走完，轮到我：引擎给的就是我方视角
        ga.record(2, 40, 'r', 'r')
        assertEquals(40, ga.lastCp)
    }

    @Test
    fun recordSamePlyOverwrites() {
        val ga = GameAnalysis()
        ga.reset()
        ga.record(1, 10, 'r', 'r')
        ga.record(1, 20, 'r', 'r')
        assertEquals("同一手重复采样应覆盖而不是追加", 2, ga.curve.size)
        assertEquals(20, ga.lastCp)
    }

    @Test
    fun truncateOnUndo() {
        val ga = GameAnalysis()
        ga.reset()
        for (p in 1..5) ga.record(p, p * 10, 'r', 'r')
        assertEquals(6, ga.curve.size)
        ga.truncate(2)
        assertEquals(3, ga.curve.size)
        assertEquals(2, ga.curve[2].ply)
    }

    @Test
    fun resetStartsAtFiftyPercent() {
        val ga = GameAnalysis()
        ga.reset()
        assertEquals(1, ga.curve.size)
        assertEquals(50, GameAnalysis.winPct(ga.lastCp))
    }

    // ---------------- 复盘 ----------------

    private fun sampleCurve(): Pair<GameAnalysis, List<MoveRecord>> {
        val ga = GameAnalysis()
        ga.reset()
        // 我方视角的分差：0 -> +50 -> +40 -> -400 -> -380 -> -60
        ga.record(1, -50, 'r', 'b')     // 我走后轮黑 -> 我方 +50
        ga.record(2, 40, 'r', 'r')      // 黑走后轮我 -> 我方 +40
        ga.record(3, 400, 'r', 'b')     // 我走后轮黑 -> 我方 -400  这一手是大漏着
        ga.record(4, -380, 'r', 'r')    // 黑走后轮我 -> 我方 -380
        ga.record(5, 60, 'r', 'b')      // 我走后轮黑 -> 我方 -60
        val ms = listOf(
            rec("炮二平五", 'r'), rec("马8进7", 'b'),
            rec("车一进一", 'r'), rec("炮8平9", 'b'),
            rec("马二进三", 'r')
        )
        return ga to ms
    }

    @Test
    fun turningPointsFindTheBlunder() {
        val (ga, ms) = sampleCurve()
        val tps = ga.turningPoints('r', ms, 3)
        assertTrue("应该找到胜负手", tps.isNotEmpty())
        assertEquals("最大的失误应是第 3 手（车一进一）", 3, tps[0].ply)
        assertEquals("车一进一", tps[0].notation)
        assertTrue("掉分应该很明显", tps[0].dropPct <= -20)
    }

    @Test
    fun turningPointsIgnoreOpponentMoves() {
        val (ga, ms) = sampleCurve()
        // 站在黑方视角复盘时，红方的大漏着不该算进黑方的胜负手
        val tps = ga.turningPoints('b', ms, 3)
        assertTrue("黑方没有明显失误", tps.none { it.ply == 3 })
    }

    @Test
    fun summaryMentionsBlunderCount() {
        val (ga, ms) = sampleCurve()
        val s = ga.summary('r', ms)
        assertTrue(s.contains("回合"))
        assertTrue(s.contains("漏着"))
    }

    @Test
    fun fmtFormatting() {
        val ga = GameAnalysis()
        assertEquals("+1.35", ga.fmt(135))
        assertEquals("-0.80", ga.fmt(-80))
        assertEquals("必胜", ga.fmt(GameAnalysis.MATE))
        assertEquals("必败", ga.fmt(-GameAnalysis.MATE))
    }
}
