package com.pikafish.chess.core

/**
 * 布局识别：只看着法序列就能判断走了什么布局体系，不需要内置棋谱库。
 *
 * 为什么不做内嵌开局库：Pikafish 6 秒能算到 15 层以上，开局质量已经超过
 * 常见的大师谱着，而一个能覆盖主流变例的开局库要 250KB 左右。
 * 与其塞数据，不如把"现在下的是什么布局"告诉用户 —— 这才是学习价值所在。
 */
object Opening {

    /** 返回形如「中炮对屏风马」的布局名，识别不出返回空串 */
    fun identify(moves: List<MoveRecord>): String {
        if (moves.isEmpty()) return ""
        val red = moves.filter { it.side == 'r' }.map { it.notation }
        val black = moves.filter { it.side == 'b' }.map { it.notation }
        val r1 = red.getOrNull(0) ?: ""
        val b1 = black.getOrNull(0) ?: ""

        val head = when (r1) {
            "炮二平五", "炮八平五" -> "中炮"
            "兵七进一", "兵三进一" -> "仙人指路"
            "相三进五", "相七进五" -> "飞相局"
            "马二进三", "马八进七" -> "起马局"
            "炮二平六", "炮八平四" -> "过宫炮"
            "炮二平四", "炮八平六" -> "士角炮"
            "仕四进五", "仕六进五" -> "仕角局"
            else -> ""
        }

        // 屏风马要两匹马都起来，单看第一着会误判
        val twoHorses = black.contains("马8进7") && black.contains("马2进3")
        val tail = when {
            twoHorses -> "屏风马"
            b1 == "炮8平5" -> "顺手炮"
            b1 == "炮2平5" -> "列手炮"
            b1 == "炮8平6" || b1 == "炮2平6" -> "反宫马"
            b1 == "象3进5" || b1 == "象7进5" -> "飞象"
            b1 == "炮8平9" || b1 == "炮2平1" -> "边炮"
            b1 == "马8进7" || b1 == "马2进3" -> "单提马"
            b1 == "卒7进1" || b1 == "卒3进1" -> "挺卒"
            else -> ""
        }

        return when {
            head.isNotEmpty() && tail.isNotEmpty() -> "${head}对${tail}"
            head.isNotEmpty() -> head
            tail.isNotEmpty() -> tail
            else -> ""
        }
    }

    /** 给布局名配一句简短说明，帮助用户理解 */
    fun explain(name: String): String = when {
        name.isEmpty() -> ""
        name.contains("中炮对屏风马") -> "最主流的对抗体系，红方中路施压，黑方双马护中"
        name.contains("中炮对顺手炮") -> "对攻型布局，双方同侧出炮，容易形成激烈对杀"
        name.contains("中炮对列手炮") -> "异侧出炮，中路与两翼的争夺都很关键"
        name.contains("中炮对反宫马") -> "黑方用士角炮牵制红马，阵型弹性大"
        name.contains("中炮对单提马") -> "黑方单马守中，侧重快速出动子力"
        name.contains("仙人指路") -> "先挺兵试探，保留转成多种布局的余地"
        name.contains("飞相局") -> "稳固型开局，先补中路再图后手反击"
        name.contains("起马局") -> "先出马，阵型灵活，竞争局面主动权"
        name.contains("过宫炮") -> "炮过宫集中一侧兵力，讲究子力协调"
        name.contains("士角炮") -> "炮进士角，攻守兼备的下法"
        name.startsWith("中炮") -> "以中路进攻为核心的经典下法"
        else -> ""
    }
}
