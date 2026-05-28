package com.koupper.monitor

import java.io.File
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// ── ANSI palette (ESC = ) ───────────────────────────────────────────────
private const val ESC = ""
private object C {
    const val R  = "$ESC[0m"    // reset
    const val BD = "$ESC[1m"    // bold
    const val CY = "$ESC[96m"   // bright cyan
    const val GR = "$ESC[92m"   // bright green
    const val MG = "$ESC[95m"   // bright magenta
    const val YL = "$ESC[93m"   // bright yellow
    const val RD = "$ESC[91m"   // bright red
    const val WH = "$ESC[97m"   // white
    const val GY = "$ESC[90m"   // dark gray

    const val HOME     = "$ESC[H"
    const val HIDE_CUR = "$ESC[?25l"
    const val SHOW_CUR = "$ESC[?25h"
    const val ALT_ON   = "$ESC[?1049h"
    const val ALT_OFF  = "$ESC[?1049l"
    const val CLEAR    = "$ESC[2J"
    const val CLREOL   = "$ESC[K"
}

// ── Domain ────────────────────────────────────────────────────────────────────
private enum class Status { PENDING, PROCESSING, DONE, FAILED }

private data class JobEntry(
    val id: String,
    val queue: String,
    @Volatile var status: Status,
    @Volatile var lastUpdate: String = ts()
)

private fun ts() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

// Strip ANSI codes to measure visible (printed) length
private val ANSI_RE = Regex("\\[[0-9;]*[mABCDEFGHJKSTh]")
private fun visLen(s: String) = ANSI_RE.replace(s, "").length

// Pad string to n visible characters
private fun pad(s: String, n: Int) = s + " ".repeat((n - visLen(s)).coerceAtLeast(0))

// Truncate to max visible chars
private fun trunc(s: String, n: Int) = if (s.length > n) s.take(n - 1) + "…" else s

// ── Entry point ───────────────────────────────────────────────────────────────
fun main(args: Array<String>) {
    val jobsDir = File(args.firstOrNull { !it.startsWith("-") } ?: "jobs")
    MonitorApp(jobsDir).run()
}

// ── Application ───────────────────────────────────────────────────────────────
class MonitorApp(private val jobsDir: File) {

    private val jobs   = ConcurrentHashMap<String, JobEntry>()
    private val alive  = AtomicBoolean(true)
    @Volatile private var watchSt = "STARTING"

    // Terminal dimensions detected at startup
    private val tw: Int; private val th: Int
    init {
        var w = 120; var h = 40
        try {
            // stty size → "rows cols"
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "stty size </dev/tty"))
            p.inputStream.bufferedReader().readLine()?.split(" ")?.let {
                if (it.size >= 2) { h = it[0].toIntOrNull() ?: h; w = it[1].toIntOrNull() ?: w }
            }
        } catch (_: Exception) {
            w = System.getenv("COLUMNS")?.toIntOrNull() ?: 120
            h = System.getenv("LINES")?.toIntOrNull()   ?: 40
        }
        tw = w.coerceAtLeast(80)
        th = h.coerceAtLeast(20)
    }

    // ── Layout constants ─────────────────────────────────────────────────────
    //
    // Body row format: ║{TI chars}║{SI chars}║  → total = TI + SI + 3 = tw
    //   So: TI = tw - SI - 3
    //
    // Content inside each cell starts with a space for padding, e.g.:
    //   ║ job-uuid   queue   STATUS   14:23║ METRICS    3║
    //
    private val SI = 24                          // sidebar cell width (including leading space)
    private val TI get() = tw - SI - 3           // table cell width   (including leading space)

    // Table column widths (visible chars, within TI - 1 for the leading space)
    private val COL_ID get() = if (TI > 70) 22 else 15
    private val COL_Q  get() = if (TI > 70) 16 else 10
    private val COL_ST = 12
    private val COL_TM = 8
    private val COL_SUM get() = 1 + COL_ID + 1 + COL_Q + 1 + COL_ST + 1 + COL_TM  // 1=leading space

    // Logo (visible width = 52 chars per line)
    private val LOGO = listOf(
        " ${C.BD}${C.CY}██████╗ ██████╗ ██████╗ ████████╗███████╗██╗  ██╗${C.R}",
        "${C.BD}${C.CY}██╔════╝██╔═══██╗██╔══██╗╚══██╔══╝██╔════╝╚██╗██╔╝${C.R}",
        "${C.BD}${C.CY}██║     ██║   ██║██████╔╝   ██║   █████╗   ╚███╔╝ ${C.R}",
        "${C.BD}${C.CY}██║     ██║   ██║██╔══██╗   ██║   ██╔══╝   ██╔██╗ ${C.R}",
        "${C.BD}${C.CY}╚██████╗╚██████╔╝██║  ██║   ██║   ███████╗██╔╝ ██╗${C.R}",
        " ${C.BD}${C.CY}╚═════╝ ╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═╝  ╚═╝${C.R}"
    )
    private val LOGO_VIS = 52

    // ── Lifecycle ────────────────────────────────────────────────────────────
    fun run() {
        setupShutdown()
        rawMode(true)
        print(C.ALT_ON + C.CLEAR + C.HOME + C.HIDE_CUR)
        System.out.flush()

        initialScan()
        thread(name = "watcher", isDaemon = true) { watchLoop() }
        thread(name = "keys",    isDaemon = true) { keyLoop() }

        try {
            while (alive.get()) { render(); Thread.sleep(80) }
        } finally {
            rawMode(false)
            print(C.SHOW_CUR + C.ALT_OFF)
            System.out.flush()
        }
    }

    private fun setupShutdown() {
        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = true) {
            rawMode(false)
            print(C.SHOW_CUR + C.ALT_OFF)
            System.out.flush()
        })
    }

    private fun rawMode(on: Boolean) {
        try {
            // -icanon: immediate key reads (no line buffering)
            // -echo: don't echo input back
            // opost kept ON: \n still triggers \r in output, preventing staircase in render
            val cmd = if (on) "stty -icanon min 1 time 0 -echo </dev/tty"
                      else    "stty icanon echo </dev/tty"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (_: Exception) {}
    }

    // ── Initial filesystem scan ───────────────────────────────────────────────
    private fun initialScan() {
        if (!jobsDir.exists()) return
        for (qDir in jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return) {
            val q = qDir.name
            for (f in qDir.listFiles() ?: continue) {
                when {
                    f.name.endsWith(".json.processing") ->
                        jobs[f.name.removeSuffix(".json.processing")] =
                            JobEntry(f.name.removeSuffix(".json.processing"), q, Status.PROCESSING)
                    f.name.endsWith(".json") ->
                        jobs.putIfAbsent(f.nameWithoutExtension, JobEntry(f.nameWithoutExtension, q, Status.PENDING))
                }
            }
            for (f in File(qDir, ".failed").listFiles { f -> f.name.endsWith(".json") } ?: continue) {
                jobs.putIfAbsent(f.nameWithoutExtension, JobEntry(f.nameWithoutExtension, q, Status.FAILED))
            }
        }
    }

    // ── WatchService loop ─────────────────────────────────────────────────────
    private fun watchLoop() {
        if (!jobsDir.exists()) jobsDir.mkdirs()
        val ws     = FileSystems.getDefault().newWatchService()
        val dirMap = mutableMapOf<Path, String?>()  // watched path → queue name (null = root)

        fun reg(d: File, q: String?) {
            d.toPath().register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            dirMap[d.toPath()] = q
        }

        reg(jobsDir, null)
        for (d in jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList<File>()) {
            reg(d, d.name)
        }

        watchSt = "ACTIVE"
        try {
            while (alive.get()) {
                val key  = ws.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val watchable = key.watchable()
                if (watchable !is Path) { key.reset(); continue }
                val path = watchable
                val q    = dirMap[path]

                for (ev in key.pollEvents()) {
                    if (ev.kind() == OVERFLOW) continue
                    @Suppress("UNCHECKED_CAST")
                    val fname = (ev as WatchEvent<Path>).context().fileName.toString()
                    val t = ts()

                    when {
                        // New queue directory at jobs/ root
                        q == null && ev.kind() == ENTRY_CREATE -> {
                            val nd = File(jobsDir, fname)
                            if (nd.isDirectory) reg(nd, fname)
                        }
                        // .json.processing — claimed / in-flight
                        fname.endsWith(".json.processing") -> {
                            val id = fname.removeSuffix(".json.processing")
                            when (ev.kind()) {
                                ENTRY_CREATE -> jobs[id] = JobEntry(id, q ?: "?", Status.PROCESSING, t)
                                ENTRY_DELETE -> {
                                    // ackFn deleted the file → job done, show briefly then remove
                                    jobs[id]?.let { it.status = Status.DONE; it.lastUpdate = t }
                                    thread(isDaemon = true) { Thread.sleep(3_000); jobs.remove(id) }
                                }
                                else -> {}
                            }
                        }
                        // .json — pending job
                        fname.endsWith(".json") && !fname.startsWith(".") -> {
                            val id = fname.removeSuffix(".json")
                            when (ev.kind()) {
                                ENTRY_CREATE -> jobs.putIfAbsent(id, JobEntry(id, q ?: "?", Status.PENDING, t))
                                ENTRY_DELETE -> if (jobs[id]?.status != Status.PROCESSING) jobs.remove(id)
                                ENTRY_MODIFY -> jobs[id]?.lastUpdate = t
                                else -> {}
                            }
                        }
                    }
                }
                key.reset()
            }
        } catch (_: InterruptedException) {
        } finally {
            ws.close()
            watchSt = "STOPPED"
        }
    }

    // ── Keyboard input ────────────────────────────────────────────────────────
    private fun keyLoop() {
        val buf = ByteArray(1)
        try {
            while (alive.get()) {
                if (System.`in`.available() > 0) {
                    System.`in`.read(buf)
                    val ch = buf[0].toInt()
                    if (ch == 'q'.code || ch == 'Q'.code || ch == 27 || ch == 3) {
                        alive.set(false); return
                    }
                }
                Thread.sleep(50)
            }
        } catch (_: Exception) {}
    }

    // ── Render engine ─────────────────────────────────────────────────────────
    private fun render() {
        val snap = jobs.values.toList().sortedWith(
            compareBy({ statusRank(it.status) }, { it.queue }, { it.id })
        )
        val pending    = snap.count { it.status == Status.PENDING }
        val processing = snap.count { it.status == Status.PROCESSING }
        val done       = snap.count { it.status == Status.DONE }
        val failed     = snap.count { it.status == Status.FAILED }

        val sb = StringBuilder()
        sb.append(C.HOME)  // Move cursor to top-left — no erase = zero flicker

        renderHeader(sb)
        renderBody(sb, snap, pending, processing, done, failed)
        renderFooter(sb, failed)
        repeat(3) { sb.append(C.CLREOL + "\n") }  // erase stale tail lines

        print(sb)
        System.out.flush()
    }

    private fun statusRank(s: Status) = when (s) {
        Status.PROCESSING -> 0; Status.PENDING -> 1; Status.FAILED -> 2; Status.DONE -> 3
    }

    // ── Header block ─────────────────────────────────────────────────────────
    private fun renderHeader(sb: StringBuilder) {
        val inner = tw - 2
        val hLine = "═".repeat(inner)
        sb.append("${C.CY}╔${hLine}╗${C.R}\n")

        for (line in LOGO) {
            // Content = " " + logo + padding to fill inner
            val rPad = (inner - 1 - LOGO_VIS).coerceAtLeast(0)
            sb.append("${C.CY}║${C.R} $line${" ".repeat(rPad)}${C.CY}║${C.R}\n")
        }

        // Sub-label right-aligned next to logo
        val subLabel = "${C.GY}SWARM MONITOR${C.R}"
        val subVis   = 13
        val subPad   = (inner - 1 - LOGO_VIS - subVis).coerceAtLeast(2)
        sb.append("${C.CY}║${C.R} ${" ".repeat(LOGO_VIS + subPad)}$subLabel${" ".repeat(inner - 1 - LOGO_VIS - subPad - subVis)}${C.CY}║${C.R}\n")

        // Centered title
        val title    = "${C.BD}${C.MG}◈  IGLY CORTEX — SWARM MONITOR  ◈${C.R}"
        val titleVis = 35
        val tPadL    = ((inner - titleVis) / 2).coerceAtLeast(0)
        val tPadR    = (inner - tPadL - titleVis).coerceAtLeast(0)
        sb.append("${C.CY}║${" ".repeat(tPadL)}$title${" ".repeat(tPadR)}║${C.R}\n")

        sb.append("${C.CY}╚${hLine}╝${C.R}\n")
    }

    // ── Body: table (left) + sidebar (right) ─────────────────────────────────
    private fun renderBody(
        sb: StringBuilder, snap: List<JobEntry>,
        pending: Int, processing: Int, done: Int, failed: Int
    ) {
        val ti = TI.coerceAtLeast(30)
        val si = SI

        // Top border — widths: ╔ + ti chars + ╦ + si chars + ╗ = ti+si+3 = tw
        val lbl1 = "═ ACTIVE JOBS "
        val lbl2 = "═ STATS "
        sb.append("${C.CY}╔${lbl1}${"═".repeat((ti - lbl1.length).coerceAtLeast(0))}╦${lbl2}${"═".repeat((si - lbl2.length).coerceAtLeast(0))}╗${C.R}\n")

        // Column header — leading space included in COL_SUM
        val hdrRow = " ${pad("${C.GY}JOB ID${C.R}", COL_ID)} ${pad("${C.GY}QUEUE${C.R}", COL_Q)} ${pad("${C.GY}STATUS${C.R}", COL_ST)} ${C.GY}UPDATED${C.R}"
        bodyRow(sb, hdrRow, COL_SUM, sideCell(0, pending, processing, done, failed), ti, si)

        // Separator
        val sepRow = " ${C.GY}${"─".repeat(COL_ID)} ${"─".repeat(COL_Q)} ${"─".repeat(COL_ST)} ${"─".repeat(COL_TM)}${C.R}"
        bodyRow(sb, sepRow, COL_SUM, sideCell(1, pending, processing, done, failed), ti, si)

        // Job rows
        val maxRows = (th - LOGO.size - 13).coerceAtLeast(3)
        val visible = snap.take(maxRows)

        visible.forEachIndexed { idx, j ->
            val idStr  = pad("${C.WH}${trunc(j.id, COL_ID)}${C.R}", COL_ID + 9)
            val qStr   = pad("${C.GY}${trunc(j.queue, COL_Q)}${C.R}", COL_Q + 9)
            val (sc, ss) = statusStyle(j.status)
            val stStr  = pad("$sc$ss${C.R}", COL_ST + sc.length + 3)
            val tmStr  = "${C.GY}${j.lastUpdate}${C.R}"
            val row    = " $idStr $qStr $stStr $tmStr"
            bodyRow(sb, row, COL_SUM, sideCell(idx + 2, pending, processing, done, failed), ti, si)
        }

        // Empty fill rows to flush old content
        val fillRows = (maxRows - visible.size + 1).coerceAtLeast(1)
        repeat(fillRows) { i ->
            bodyRow(sb, "", 0, sideCell(visible.size + 2 + i, pending, processing, done, failed), ti, si)
        }

        // Bottom border
        sb.append("${C.CY}╚${"═".repeat(ti)}╩${"═".repeat(si)}╝${C.R}\n")
    }

    // Emit one body row. Row visible width = TI + SI + 3 = tw.
    // Content and side are each padded to fill their cell completely.
    private fun bodyRow(sb: StringBuilder, content: String, contentVis: Int, side: String, ti: Int, si: Int) {
        val tPad = (ti - contentVis).coerceAtLeast(0)
        val sPad = (si - visLen(side)).coerceAtLeast(0)
        sb.append("${C.CY}║${C.R}$content${" ".repeat(tPad)}${C.CY}║${C.R}$side${" ".repeat(sPad)}${C.CY}║${C.R}\n")
    }

    // Sidebar cell content indexed by row (no borders — those are in bodyRow)
    private fun sideCell(idx: Int, pending: Int, processing: Int, done: Int, failed: Int): String = when (idx) {
        0  -> " ${C.BD}${C.CY}METRICS${C.R}"
        1  -> " ${C.GY}${"─".repeat(SI - 1)}${C.R}"
        2  -> " ${pad("${C.YL}◉ PENDING${C.R}",  20)}${C.WH}${pending.toString().padStart(3)}${C.R}"
        3  -> " ${pad("${C.MG}◉ PROC'ING${C.R}", 20)}${C.WH}${processing.toString().padStart(3)}${C.R}"
        4  -> " ${pad("${C.GR}◉ DONE${C.R}",     20)}${C.WH}${done.toString().padStart(3)}${C.R}"
        5  -> " ${pad("${C.RD}◉ FAILED${C.R}",   20)}${C.WH}${failed.toString().padStart(3)}${C.R}"
        6  -> ""
        7  -> " ${C.GY}DRIVER ${C.CY}file${C.R}"
        8  -> " ${C.GY}WATCH  ${watchCol()}${watchSt}${C.R}"
        9  -> " ${C.GY}DIR    ${C.WH}${trunc(jobsDir.name, 12)}${C.R}"
        10 -> " ${C.GY}${ts()}${C.R}"
        else -> ""
    }

    // ── Footer ────────────────────────────────────────────────────────────────
    private fun renderFooter(sb: StringBuilder, failed: Int) {
        val sys   = if (failed == 0) "${C.GR}● System: OK${C.R}" else "${C.RD}● System: ALERT${C.R}"
        val watch = "${watchCol()}● Watch: $watchSt${C.R}"
        val quit  = "${C.GY}[q] quit  [Ctrl+C] exit${C.R}"
        val content = "  $sys   $watch   ${C.GY}${ts()}${C.R}   $quit  "
        val cPad  = (tw - 2 - visLen(content)).coerceAtLeast(0)
        val hLine = "═".repeat(tw - 2)

        sb.append("${C.CY}╔${hLine}╗${C.R}\n")
        sb.append("${C.CY}║${C.R}$content${" ".repeat(cPad)}${C.CY}║${C.R}\n")
        sb.append("${C.CY}╚${hLine}╝${C.R}\n")
    }

    // ── Style helpers ─────────────────────────────────────────────────────────
    private fun statusStyle(s: Status): Pair<String, String> = when (s) {
        Status.PENDING    -> Pair(C.YL,          "PENDING")
        Status.PROCESSING -> Pair("${C.MG}${C.BD}", "PROCESSING")
        Status.DONE       -> Pair(C.GR,           "DONE")
        Status.FAILED     -> Pair(C.RD,           "FAILED")
    }

    private fun watchCol() = if (watchSt == "ACTIVE") C.GR else C.RD
}
