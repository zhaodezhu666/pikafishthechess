package com.pikafish.chess.core

import kotlin.math.abs
import kotlin.math.exp

/** 胜率曲线上的一个采样点：第 ply 手之后，从我方视角看的分差（厘兵） */
class EvalPoint(val ply: Int, val cp: Int)

/** 复盘里找到的一个「胜负手」 */
class TurningPoint(
    val ply: Int,
    val notation: String,
    val beforePct: Int,
    val afterPct: Int
) {
    val dropPct: Int get() = afterPct - beforePct
}

/**
 * 对局分析：胜率换算、曲线数据、复盘找胜负手。
 * 纯计算，不依赖引擎和安卓 API，可在 JVM 单测里验证。
 */
class GameAnalysis {

    private val points = ArrayList<EvalPoint>()

    /** 每半步一个采样；曲线从 50% 起步 */
    val curve: List<EvalPoint> get() = points

    fun reset() {
        points.clear()
        points.add(EvalPoint(0, 0))
    }

    /**
     * 记录一次采样。
     * @param cpFromSideToMove 引擎给的分数（永远是「轮到走的一方」视角）
     * @param mySide 我执哪一方
     */
    fun record(ply: Int, cpFromSideToMove: Int, mySide: Char, sideToMove: Char) {
        val cp = if (sideToMove == mySide) cpFromSideToMove else -cpFromSideToMove
        // 同一个 ply 重复采样就覆盖，避免曲线出现折返
        if (points.isNotEmpty() && points[points.size - 1].ply == ply) {
            points[points.size - 1] = EvalPoint(ply, cp)
        } else {
            points.add(EvalPoint(ply, cp))
        }
    }

    /** 悔棋 / 重开时把曲线回退到指定手数 */
    fun truncate(ply: Int) {
        while (points.size > 1 && points[points.size - 1].ply > ply) {
            points.removeAt(points.size - 1)
        }
    }

    val lastCp: Int get() = points.lastOrNull()?.cp ?: 0

    companion object {
        const val MATE = 30000

        /** 分差换算成胜率。330 厘兵约对应「明显优势」一档 */
        fun winProb(cp: Int): Double = when {
            cp > MATE - 1000 -> 1.0
            cp < -(MATE - 1000) -> 0.0
            else -> 1.0 / (1.0 + exp(-cp / 330.0))
        }

        fun winPct(cp: Int): Int = Math.round(winProb(cp) * 100).toInt()

        /** 把分数说成人话 */
        fun describe(cp: Int): String = when {
            cp > MATE - 1000 -> "你已有杀棋"
            cp < -(MATE - 1000) -> "AI 已有杀棋"
            cp > 300 -> "你明显占优"
            cp > 120 -> "你稍占优"
            cp > 40 -> "你略占上风"
            cp > -40 -> "双方均势"
            cp < -300 -> "AI 明显占优"
            cp < -120 -> "AI 稍占优"
            else -> "AI 略占上风"
        }

        /** 优势用红色（中国习惯：红=好），劣势用绿色 */
        fun isGood(cp: Int): Boolean = cp > 40
        fun isBad(cp: Int): Boolean = cp < -40
    }

    /**
     * 复盘：找出我方着法里让胜率下滑最多的几步（胜负手）。
     * 只看我方走的着法 —— 输棋的原因通常在自己这一步。
     */
    fun turningPoints(mySide: Char, moves: List<MoveRecord>, topN: Int = 3): List<TurningPoint> {
        if (points.size < 4) return emptyList()
        val out = ArrayList<TurningPoint>()
        for (i in 1 until points.size) {
            val cur = points[i]
            val prev = points[i - 1]
            if (cur.ply < 1) continue
            val m = moves.getOrNull(cur.ply - 1) ?: continue
            if (m.side != mySide) continue
            val before = winPct(prev.cp)
            val after = winPct(cur.cp)
            if (after - before <= -3) {
                out.add(TurningPoint(cur.ply, m.notation, before, after))
            }
        }
        out.sortBy { it.dropPct }
        return out.take(topN)
    }

    /** 全局摘要：先手方最大领先、失误次数 */
    fun summary(mySide: Char, moves: List<MoveRecord>): String {
        if (points.size < 3) return "对局着法还太少，下满 3 回合后可以复盘。"
        var best = 0
        var worst = 0
        for (p in points) {
            if (p.cp > best) best = p.cp
            if (p.cp < worst) worst = p.cp
        }
        val myCnt = moves.count { it.side == mySide }
        val blunders = turningPoints(mySide, moves, 99).filter { it.dropPct <= -8 }.size
        val sb = StringBuilder()
        sb.append("共 ").append((moves.size + 1) / 2).append(" 回合，其中你走了 ").append(myCnt).append(" 步。\n")
        sb.append("你的最好时刻：领先约 ").append(winPct(best)).append("%；")
        sb.append("最被动时：胜率 ").append(winPct(worst)).append("%。\n")
        sb.append(if (blunders == 0) "没有出现明显漏着（单步掉 8 个百分点以上），着法质量不错。"
        else "有 $blunders 步属于明显漏着（单步掉 8 个百分点以上），是主要失分点。")
        return sb.toString()
    }

    /** 分差格式化：+1.35 / -0.80 */
    fun fmt(cp: Int): String {
        if (abs(cp) > MATE - 1000) return if (cp > 0) "必胜" else "必败"
        val v = cp / 100.0
        return (if (v > 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", v)
    }
}
