package com.pikafish.chess.engine

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * 两个关键点：
 *
 * 1) 安卓只允许执行应用私有 native 目录里的文件，所以引擎以 `lib*.so` 形式放进 jniLibs
 *    （配合 manifest 的 extractNativeLibs=true），系统解压到 nativeLibraryDir 后才能 exec。
 *
 * 2) 我们打包了两份引擎：dotprod 版（需要 ARMv8.2 的 asimddp 指令，快 20~30%）
 *    和 generic 版（兼容性优先）。启动时按 /proc/cpuinfo 选，选错就换另一个重试。
 *    在旧 CPU 上跑 dotprod 会直接触发 SIGILL 让进程崩掉，所以这一步很有必要。
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
    var usedDotprod = false
        private set
    var nnueBytes = 0L
        private set
    var cpuHasDotprod = false
        private set

    /** 诊断日志，界面上可直接查看 */
    private val diag = StringBuilder()
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun log(s: String) {
        synchronized(diag) {
            diag.append(ts.format(Date())).append("  ").append(s).append('\n')
            if (diag.length > 20000) diag.delete(0, diag.length - 16000)
        }
    }

    fun diagnostics(): String = synchronized(diag) { diag.toString() }

    companion object {
        private const val NNUE_ASSET = "pikafish.nnue"
        private const val NNUE_FILE = "pikafish.nnue"
        /** 权重完整性下限：真实文件约 50.7MB，低于这个数说明拷贝被中途打断 */
        private const val NNUE_MIN_BYTES = 40_000_000L
    }

    /** 读 /proc/cpuinfo 判断 CPU 是否支持 dot product 指令（ARMv8.2 asimddp） */
    private fun detectDotprod(): Boolean = try {
        val txt = File("/proc/cpuinfo").readText()
        txt.contains("asimddp")
    } catch (e: Exception) {
        log("读 /proc/cpuinfo 失败（按不支持处理）：${e.message}")
        false
    }

    /**
     * 首次启动把权重从 assets 复制到内部存储。
     *
     * 必须先写临时文件、校验长度、再原子改名 —— 否则中途被杀会留下半个文件，
     * 下次启动看到"文件存在"就直接用，引擎加载权重时报错甚至直接崩掉。
     */
    private fun ensureNnue(): File {
        val out = File(ctx.filesDir, NNUE_FILE)
        if (out.exists() && out.length() >= NNUE_MIN_BYTES) {
            nnueBytes = out.length()
            log("权重已存在：${nnueBytes} 字节")
            return out
        }
        if (out.exists()) {
            log("权重不完整（${out.length()} 字节），删除重建")
            out.delete()
        }
        val tmp = File(ctx.filesDir, "$NNUE_FILE.part")
        tmp.delete()
        log("正在从 APK 解压权重…")
        val t0 = System.currentTimeMillis()
        ctx.assets.open(NNUE_ASSET).use { ins ->
            tmp.outputStream().use { outs ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                }
                outs.flush()
                try {
                    outs.fd.sync()
                } catch (_: Exception) {
                }
            }
        }
        val sz = tmp.length()
        if (sz < NNUE_MIN_BYTES) {
            tmp.delete()
            throw IOException("权重解压不完整：$sz 字节（预期至少 $NNUE_MIN_BYTES）")
        }
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw IOException("权重文件改名失败")
        }
        nnueBytes = out.length()
        log("权重解压完成：$nnueBytes 字节，耗时 ${System.currentTimeMillis() - t0} ms")
        return out
    }

    private fun binFile(dotprod: Boolean): File =
        File(ctx.applicationInfo.nativeLibraryDir, if (dotprod) "libpikafish.so" else "libpikafish-generic.so")

    fun start(threads: Int, hashMb: Int) {
        cpuHasDotprod = detectDotprod()
        log("CPU 支持 asimddp(dotprod) = $cpuHasDotprod")

        // 优先用 CPU 支持的那一版；失败自动换另一版
        val order = if (cpuHasDotprod) listOf(true, false) else listOf(false, true)
        for (dot in order) {
            if (tryStart(dot, threads, hashMb)) {
                usedDotprod = dot
                ready = true
                log("引擎启动成功：$name（${if (dot) "dotprod" else "generic"} 版，$threads 线程，${hashMb}MB 置换表）")
                return
            }
            if (proc != null) {
                // 上一版失败留下的进程清掉
                try {
                    proc?.destroy()
                } catch (_: Exception) {
                }
                proc = null
            }
        }
        ready = false
        if (lastError == null) lastError = "两种引擎版本都无法启动"
        log("引擎启动失败：$lastError")
    }

    private fun tryStart(dotprod: Boolean, threads: Int, hashMb: Int): Boolean {
        val tag = if (dotprod) "dotprod" else "generic"
        try {
            val exe = binFile(dotprod)
            if (!exe.exists()) {
                log("[$tag] 引擎文件不存在：${exe.absolutePath}")
                lastError = "APK 里缺少 $tag 版引擎"
                return false
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
            val tail = ArrayList<String>()
            Thread {
                try {
                    while (true) {
                        val line = r.readLine() ?: break
                        synchronized(tail) {
                            tail.add(line)
                            if (tail.size > 40) tail.removeAt(0)
                        }
                        q.put(line)
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()
            // 让引擎崩溃时的输出能进日志
            this.tailBuf = tail

            send("uci")
            val ids = readUntil({ it.startsWith("uciok") }, 30_000)
            if (!p.isAlive) {
                lastError = "[$tag] 引擎握手前就退出了（多半是 CPU 指令集不兼容）：\n" + tailText()
                log(lastError!!)
                return false
            }
            ids.firstOrNull { it.startsWith("id name") }?.let { name = it.substring(7).trim() }

            send("setoption name EvalFile value ${nnue.absolutePath}")
            send("setoption name Threads value $threads")
            send("setoption name Hash value $hashMb")
            send("setoption name MultiPV value 1")
            send("isready")
            readUntil({ it == "readyok" }, 90_000)
            if (!p.isAlive) {
                lastError = "[$tag] 加载权重时引擎退出（权重可能损坏，或内存/指令集问题）：\n" + tailText()
                log(lastError!!)
                return false
            }

            // 预热：第一次真正搜索要建线程池 / 分配置换表，会多花好几秒。
            // 提前吃掉，否则用户看到的第一步会莫名卡住。
            send("position fen rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w")
            send("go movetime 600")
            readUntil({ it.startsWith("bestmove") }, 120_000)
            if (!p.isAlive) {
                lastError = "[$tag] 预热搜索时引擎退出：\n" + tailText()
                log(lastError!!)
                return false
            }
            lastError = null
            return true
        } catch (e: Throwable) {
            lastError = "[$tag] 启动异常：${e.javaClass.simpleName}: ${e.message}"
            log(lastError!!)
            return false
        }
    }

    @Volatile private var tailBuf: ArrayList<String>? = null

    private fun tailText(): String = synchronized(tailBuf ?: ArrayList<String>()) {
        val l = tailBuf
        if (l == null || l.isEmpty()) "（引擎没有输出任何错误信息）"
        else l.joinToString("\n").take(1500)
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

    /**
     * 走子 或 多候选分析；multiPv > 1 时返回前 N 个候选。
     * 如果引擎进程中途死掉，会把 ready 置回 false 并记录原因，界面据此给出提示。
     */
    fun search(fen: String, movetimeMs: Int, multiPv: Int = 1): List<EngineInfo> {
        if (!ready) return emptyList()
        lock.withLock {
            val p = proc ?: return emptyList()
            if (!p.isAlive) {
                ready = false
                lastError = "引擎进程已退出（搜索前检测到）"
                log(lastError!!)
                return emptyList()
            }
            send("setoption name MultiPV value ${multiPv.coerceIn(1, 5)}")
            send("position fen $fen")
            send("isready")
            readUntil({ it == "readyok" }, 15_000)
            send("go movetime $movetimeMs")
            val raw = readUntil({ it.startsWith("bestmove") }, movetimeMs + 30_000L)
            if (!p.isAlive) {
                ready = false
                lastError = "引擎在搜索过程中退出：\n" + tailText()
                log(lastError!!)
                return emptyList()
            }
            if (raw.none { it.startsWith("bestmove") }) {
                log("搜索超时：${movetimeMs}ms 预算内没等到 bestmove")
            }
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
