package com.pikafish.chess.engine

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 一次搜索的返回 */
class EngineInfo(
    val bestMove: String,
    val cp: Int?,
    val depth: Int,
    val nodes: Long,
    val nps: Long,
    val pv: List<String>
)

/**
 * Pikafish 引擎进程封装。
 *
 * 关键点：安卓只允许执行应用私有 native 目录里的文件，所以引擎二进制是以
 * `libpikafish.so` 的形式放进 jniLibs 的（配合 manifest 的 extractNativeLibs=true），
 * 系统会把它解压到 `applicationInfo.nativeLibraryDir`，那里才能 exec。
 */
class UciEngine(private val ctx: Context) {

    private var proc: Process? = null
    private var writer: OutputStreamWriter? = null
    private var queue: LinkedBlockingQueue<String>? = null
    private val lock = ReentrantLock()

    var name = "Pikafish"
        private set
    var ready = false
        private set
    var lastError: String? = null
        private set

    companion object {
        private const val NNUE_ASSET = "pikafish.nnue"
        private const val NNUE_FILE = "pikafish.nnue"
    }

    /** 首次启动时把权重从 assets 复制到内部存储（约 50MB，只在第一次做） */
    private fun ensureNnue(): File {
        val out = File(ctx.filesDir, NNUE_FILE)
        if (out.exists() && out.length() > 1_000_000L) return out
        ctx.assets.open(NNUE_ASSET).use { ins ->
            out.outputStream().use { outs -> ins.copyTo(outs, 1 shl 16) }
        }
        return out
    }

    fun start(threads: Int, hashMb: Int) {
        try {
            val exe = File(ctx.applicationInfo.nativeLibraryDir, "libpikafish.so")
            if (!exe.exists()) {
                lastError = "引擎文件不存在：${exe.absolutePath}（APK 里可能没打进 arm64 引擎）"
                return
            }
            val nnue = ensureNnue()

            val pb = ProcessBuilder(exe.absolutePath)
            pb.directory(ctx.filesDir)
            pb.redirectErrorStream(true)
            val p = pb.start()
            proc = p
            writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
            val q = LinkedBlockingQueue<String>()
            queue = q
            val r = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            Thread {
                try {
                    while (true) {
                        val line = r.readLine() ?: break
                        q.put(line)
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()

            send("uci")
            val ids = readUntil({ it.startsWith("uciok") }, 30_000)
            ids.firstOrNull { it.startsWith("id name") }?.let { name = it.substring(7).trim() }

            send("setoption name EvalFile value ${nnue.absolutePath}")
            send("setoption name Threads value $threads")
            send("setoption name Hash value $hashMb")
            send("setoption name MultiPV value 1")
            send("isready")
            readUntil({ it == "readyok" }, 60_000)

            // 预热：第一次真正搜索要建线程池/分配置换表，会多花好几秒。
            // 提前吃掉这个开销，否则用户看到的第一步会莫名卡住。
            send("position fen rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w")
            send("go movetime 800")
            readUntil({ it.startsWith("bestmove") }, 120_000)

            ready = true
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            ready = false
        }
    }

    private fun send(cmd: String) {
        val w = writer ?: return
        w.write(cmd)
        w.write("\n")
        w.flush()
    }

    private fun readUntil(pred: (String) -> Boolean, timeoutMs: Long): List<String> {
        val q = queue ?: return emptyList()
        val out = ArrayList<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = q.poll(120, TimeUnit.MILLISECONDS) ?: continue
            out.add(line)
            if (pred(line)) return out
        }
        return out
    }

    /** 走子 或 多候选分析；multiPv > 1 时返回前 N 个候选 */
    fun search(fen: String, movetimeMs: Int, multiPv: Int = 1): List<EngineInfo> {
        if (!ready) return emptyList()
        lock.withLock {
            send("setoption name MultiPV value ${multiPv.coerceIn(1, 5)}")
            send("position fen $fen")
            send("isready")
            readUntil({ it == "readyok" }, 15_000)
            send("go movetime $movetimeMs")
            val raw = readUntil({ it.startsWith("bestmove") }, movetimeMs + 30_000L)
            return parse(raw)
        }
    }

    private fun parse(raw: List<String>): List<EngineInfo> {
        var best = ""
        val byPv = HashMap<Int, EngineInfo>()
        val depths = HashMap<Int, Int>()
        for (line in raw) {
            val tk = line.split(" ").filter { it.isNotEmpty() }
            if (tk.isEmpty()) continue
            if (tk[0] == "bestmove") {
                if (tk.size > 1) best = tk[1]
                continue
            }
            if (tk[0] != "info") continue

            var depth = -1
            var pvIndex = 1
            var cp: Int? = null
            var nodes = 0L
            var nps = 0L
            val pv = ArrayList<String>()
            var i = 0
            while (i < tk.size) {
                when (tk[i]) {
                    "depth" -> if (i + 1 < tk.size) depth = tk[i + 1].toIntOrNull() ?: -1
                    "multipv" -> if (i + 1 < tk.size) pvIndex = tk[i + 1].toIntOrNull() ?: 1
                    "nodes" -> if (i + 1 < tk.size) nodes = tk[i + 1].toLongOrNull() ?: 0L
                    "nps" -> if (i + 1 < tk.size) nps = tk[i + 1].toLongOrNull() ?: 0L
                    "score" -> {
                        if (i + 2 < tk.size) {
                            when (tk[i + 1]) {
                                "cp" -> cp = tk[i + 2].toIntOrNull()
                                "mate" -> {
                                    val n = tk[i + 2].toIntOrNull() ?: 0
                                    cp = (30000 - Math.abs(n) * 2) * (if (n > 0) 1 else -1)
                                }
                            }
                        }
                    }
                    "pv" -> {
                        for (k in i + 1 until tk.size) pv.add(tk[k])
                        i = tk.size
                        continue
                    }
                }
                i++
            }
            if (depth < 0 || cp == null) continue
            val prevDepth = depths[pvIndex] ?: -1
            if (depth >= prevDepth) {
                depths[pvIndex] = depth
                byPv[pvIndex] = EngineInfo(
                    bestMove = pv.firstOrNull() ?: best,
                    cp = cp, depth = depth, nodes = nodes, nps = nps, pv = pv
                )
            }
        }
        if (byPv.isEmpty() && best.isNotEmpty()) {
            return listOf(EngineInfo(best, null, 0, 0, 0, listOf(best)))
        }
        return byPv.values.sortedByDescending { it.cp ?: Int.MIN_VALUE }
    }

    fun stop() {
        try {
            send("quit")
        } catch (_: Exception) {
        }
        try {
            proc?.destroy()
        } catch (_: Exception) {
        }
        proc = null
        writer = null
        queue = null
        ready = false
    }
}
