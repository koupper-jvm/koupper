package com.koupper.monitor

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import java.io.File
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// ── Domain ────────────────────────────────────────────────────────────────────
private enum class Status { PENDING, PROCESSING, DONE, FAILED }
private data class JobEntry(
    val id: String,
    val queue: String,
    @Volatile var status: Status,
    @Volatile var lastUpdate: String = ts()
)
private fun ts() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
private fun trunc(s: String, n: Int) = if (s.length > n) s.take(n - 1) + "…" else s

// ── Colors (256-color palette: indices 8–15 = bright ANSI) ───────────────────
private object C {
    val CY  = TextColor.Indexed(14)   // bright cyan
    val GR  = TextColor.Indexed(10)   // bright green
    val MG  = TextColor.Indexed(13)   // bright magenta
    val YL  = TextColor.Indexed(11)   // bright yellow
    val RD  = TextColor.Indexed(9)    // bright red
    val WH  = TextColor.Indexed(15)   // bright white
    val GY  = TextColor.Indexed(8)    // dark gray
    val SEL = TextColor.Indexed(236)  // dark bg for selected row
    val DF  = TextColor.ANSI.DEFAULT
}

// ── Interaction modes ─────────────────────────────────────────────────────────
private enum class Mode { WATCH, COMMAND, LOG }

// ── Entry point ───────────────────────────────────────────────────────────────
fun main(args: Array<String>) {
    val jobsDir = File(args.firstOrNull { !it.startsWith("-") } ?: "jobs")
    MonitorApp(jobsDir).run()
}

// ── Application ───────────────────────────────────────────────────────────────
class MonitorApp(private val jobsDir: File) {

    private val jobs  = ConcurrentHashMap<String, JobEntry>()
    private val alive = AtomicBoolean(true)
    @Volatile private var watchSt = "STARTING"
    @Volatile private var dirty   = true

    // Mode state
    @Volatile private var mode    = Mode.WATCH
    private val commandBuffer     = StringBuilder()
    private var lastCommandResult: String? = null

    // Job selection (index into snap, recomputed each render)
    private var selectedIdx   = -1
    private var selectedJobId: String? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun run() {
        val terminal = DefaultTerminalFactory().createTerminal()
        val screen   = TerminalScreen(terminal)
        screen.startScreen()
        screen.cursorPosition = null

        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = true) {
            runCatching { screen.stopScreen() }
        })

        greeting(screen)

        initialScan()
        thread(name = "watcher", isDaemon = true) { watchLoop() }

        try {
            var lastSecond = -1
            while (alive.get()) {
                val key = screen.pollInput()
                if (key != null) {
                    val snap = jobs.values.toList()
                        .sortedWith(compareBy({ statusRank(it.status) }, { it.queue }, { it.id }))
                    handleKey(key, snap)
                }

                val resized   = screen.doResizeIfNecessary() != null
                val nowSecond = LocalDateTime.now().second
                val clockTick = nowSecond != lastSecond

                when {
                    dirty || resized -> {
                        screen.clear()
                        render(screen)
                        screen.refresh()
                        dirty = false
                        lastSecond = nowSecond
                    }
                    clockTick || mode == Mode.COMMAND -> {
                        render(screen)
                        screen.refresh()
                        lastSecond = nowSecond
                    }
                    // else: nothing changed — no bytes sent to terminal
                }
                Thread.sleep(50)
            }
        } finally {
            screen.stopScreen()
        }
    }

    // ── Greeting animation ────────────────────────────────────────────────────

    private fun greeting(screen: TerminalScreen) {
        val tw = screen.terminalSize.columns
        val th = screen.terminalSize.rows
        val g  = screen.newTextGraphics()
        screen.clear()
        screen.refresh()

        data class Line(val text: String, val row: Int, val color: TextColor, val bold: Boolean, val delayMs: Long)
        val lines = listOf(
            Line("CORTEX ONLINE",           th / 2 - 2, C.CY, true,  80L),
            Line("READY FOR INSTRUCTION",   th / 2,     C.GR, false, 45L),
            Line("— IGLY SWARM MONITOR —",  th / 2 + 2, C.GY, false, 30L)
        )

        for (line in lines) {
            val startCol = ((tw - line.text.length) / 2).coerceAtLeast(0)
            for (i in line.text.indices) {
                if (screen.pollInput() != null) return   // skip on keypress
                val mods = if (line.bold) arrayOf(SGR.BOLD) else emptyArray()
                g.put(startCol + i, line.row, line.text[i].toString(), line.color, mods = mods)
                screen.refresh()
                Thread.sleep(line.delayMs)
            }
            Thread.sleep(200)
        }
        Thread.sleep(800)
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    private fun handleKey(key: com.googlecode.lanterna.input.KeyStroke, snap: List<JobEntry>) {
        when (mode) {

            Mode.WATCH -> when {
                key.character == 'q' || key.character == 'Q'  -> alive.set(false)
                key.keyType   == KeyType.Escape                -> alive.set(false)
                key.keyType   == KeyType.EOF                   -> alive.set(false)
                key.character == ':'                           -> { mode = Mode.COMMAND; commandBuffer.clear(); dirty = true }
                key.keyType == KeyType.ArrowDown || key.character == 'j' -> {
                    if (snap.isNotEmpty()) { selectedIdx = (selectedIdx + 1).coerceAtMost(snap.size - 1); dirty = true }
                }
                key.keyType == KeyType.ArrowUp || key.character == 'k' -> {
                    selectedIdx = (selectedIdx - 1).coerceAtLeast(-1); dirty = true
                }
                key.keyType == KeyType.Enter && selectedIdx in snap.indices -> {
                    selectedJobId = snap[selectedIdx].id; mode = Mode.LOG; dirty = true
                }
            }

            Mode.COMMAND -> when {
                key.keyType == KeyType.Escape  -> { mode = Mode.WATCH; commandBuffer.clear(); dirty = true }
                key.keyType == KeyType.Enter   -> { executeCommand(commandBuffer.toString()); commandBuffer.clear(); mode = Mode.WATCH; dirty = true }
                key.keyType == KeyType.Backspace && commandBuffer.isNotEmpty() -> { commandBuffer.deleteCharAt(commandBuffer.length - 1); dirty = true }
                key.keyType == KeyType.Character && key.character != null -> { commandBuffer.append(key.character); dirty = true }
            }

            Mode.LOG -> when {
                key.keyType == KeyType.Escape  -> { mode = Mode.WATCH; selectedJobId = null; selectedIdx = -1; dirty = true }
                key.character == 'q' || key.character == 'Q' -> alive.set(false)
                key.keyType   == KeyType.EOF   -> alive.set(false)
            }
        }
    }

    // ── Command Bridge ────────────────────────────────────────────────────────

    private fun executeCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        val dir  = File(jobsDir, "commands").also { it.mkdirs() }
        File(dir, "${System.currentTimeMillis()}.cmd").writeText(trimmed)
        lastCommandResult = "→ $trimmed"
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render(screen: TerminalScreen) {
        val tw   = screen.terminalSize.columns
        val th   = screen.terminalSize.rows
        val g    = screen.newTextGraphics()
        val snap = jobs.values.toList()
            .sortedWith(compareBy({ statusRank(it.status) }, { it.queue }, { it.id }))

        // Sync selection
        if (snap.isEmpty()) {
            selectedIdx = -1; selectedJobId = null
            if (mode == Mode.LOG) { mode = Mode.WATCH; dirty = true }
        } else {
            if (selectedIdx >= snap.size) selectedIdx = snap.size - 1
            if (selectedIdx >= 0) selectedJobId = snap[selectedIdx].id
        }

        val pending    = snap.count { it.status == Status.PENDING }
        val processing = snap.count { it.status == Status.PROCESSING }
        val done       = snap.count { it.status == Status.DONE }
        val failed     = snap.count { it.status == Status.FAILED }

        renderHeader(g, tw)

        when (mode) {
            Mode.WATCH, Mode.COMMAND -> {
                val si    = 24
                val ti    = (tw - si - 3).coerceAtLeast(30)
                val sideX = 1 + ti + 1
                renderBody(g, tw, th, snap, si, selectedIdx, "═ STATS ") { idx, row ->
                    drawStatsSide(g, sideX, idx, row, pending, processing, done, failed)
                }
                if (mode == Mode.COMMAND) renderCommandBar(g, tw, th)
                else                      renderFooter(g, tw, th, failed)
            }
            Mode.LOG -> {
                val si    = (tw / 2).coerceAtLeast(30)
                val ti    = (tw - si - 3).coerceAtLeast(30)
                val sideX = 1 + ti + 1
                val maxLogRows  = (th - 4 - 13).coerceAtLeast(1)
                val rawLogLines = readLog(selectedJobId ?: "")
                val logLines    = rawLogLines.takeLast(maxLogRows)
                renderBody(g, tw, th, snap, si, selectedIdx, "═ LOG ") { idx, row ->
                    drawLogSide(g, sideX, si, idx, row, selectedJobId ?: "", logLines)
                }
                renderFooter(g, tw, th, failed)
            }
        }
    }

    private fun statusRank(s: Status) = when (s) {
        Status.PROCESSING -> 0; Status.PENDING -> 1; Status.FAILED -> 2; Status.DONE -> 3
    }

    // ── Drawing helper ────────────────────────────────────────────────────────

    private fun TextGraphics.put(
        col: Int, row: Int, text: String,
        fg: TextColor, bg: TextColor = C.DF,
        mods: Array<SGR> = emptyArray()
    ) {
        foregroundColor = fg
        backgroundColor = bg
        if (mods.isNotEmpty()) enableModifiers(*mods)
        putString(col, row, text)
        if (mods.isNotEmpty()) disableModifiers(*mods)
    }

    // ── Header (rows 0–9) ─────────────────────────────────────────────────────

    private val LOGO = listOf(
        " ██████╗ ██████╗ ██████╗ ████████╗███████╗██╗  ██╗",
        "██╔════╝██╔═══██╗██╔══██╗╚══██╔══╝██╔════╝╚██╗██╔╝",
        "██║     ██║   ██║██████╔╝   ██║   █████╗   ╚███╔╝ ",
        "██║     ██║   ██║██╔══██╗   ██║   ██╔══╝   ██╔██╗ ",
        "╚██████╗╚██████╔╝██║  ██║   ██║   ███████╗██╔╝ ██╗",
        " ╚═════╝ ╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═╝  ╚═╝"
    )

    private fun renderHeader(g: TextGraphics, tw: Int) {
        val inner = (tw - 2).coerceAtLeast(0)
        g.put(0, 0, "╔" + "═".repeat(inner) + "╗", C.CY)
        LOGO.forEachIndexed { i, line ->
            g.put(0, i + 1, "║", C.CY)
            g.put(2, i + 1, line, C.CY, mods = arrayOf(SGR.BOLD))
            g.put(tw - 1, i + 1, "║", C.CY)
        }
        g.put(0, 7, "║", C.CY)
        g.put((tw - 1 - 13).coerceAtLeast(1), 7, "SWARM MONITOR", C.GY)
        g.put(tw - 1, 7, "║", C.CY)
        val title    = "◈  IGLY CORTEX — SWARM MONITOR  ◈"
        val titleCol = ((inner - title.length) / 2 + 1).coerceAtLeast(1)
        g.put(0, 8, "║", C.CY)
        g.put(titleCol, 8, title, C.MG, mods = arrayOf(SGR.BOLD))
        g.put(tw - 1, 8, "║", C.CY)
        g.put(0, 9, "╚" + "═".repeat(inner) + "╝", C.CY)
    }

    // ── Body: table + side panel ──────────────────────────────────────────────

    private fun renderBody(
        g: TextGraphics, tw: Int, th: Int, snap: List<JobEntry>,
        si: Int, selIdx: Int, sideLabel: String,
        drawSideRow: (idx: Int, row: Int) -> Unit
    ) {
        val ti     = (tw - si - 3).coerceAtLeast(30)
        val divCol = 1 + ti
        val top    = 10
        val bot    = th - 4

        val COL_ID = if (ti > 65) 22 else 15
        val COL_Q  = if (ti > 65) 16 else 10

        // Top border
        val lbl1 = "═ ACTIVE JOBS "
        g.put(0, top,
            "╔$lbl1${"═".repeat((ti - lbl1.length).coerceAtLeast(0))}" +
            "╦$sideLabel${"═".repeat((si - sideLabel.length).coerceAtLeast(0))}╗", C.CY)

        fun borders(row: Int) {
            g.put(0, row, "║", C.CY)
            g.put(divCol, row, "║", C.CY)
            g.put(tw - 1, row, "║", C.CY)
        }

        // Column header
        borders(top + 1)
        g.put(1, top + 1, " ${"JOB ID".padEnd(COL_ID)} ${"QUEUE".padEnd(COL_Q)} ${"STATUS".padEnd(12)} UPDATED", C.GY)
        drawSideRow(0, top + 1)

        // Separator
        borders(top + 2)
        g.put(1, top + 2, " ${"─".repeat(COL_ID)} ${"─".repeat(COL_Q)} ${"─".repeat(12)} ${"─".repeat(8)}", C.GY)
        drawSideRow(1, top + 2)

        // Job rows
        val maxRows = (bot - (top + 3)).coerceAtLeast(0)
        snap.take(maxRows).forEachIndexed { idx, j ->
            val row = top + 3 + idx
            val isSelected = idx == selIdx
            val bg = if (isSelected) C.SEL else C.DF
            val (fgColor, label) = statusStyle(j.status)
            val mods = if (j.status == Status.PROCESSING) arrayOf(SGR.BOLD) else emptyArray()

            g.put(0, row, "║", C.CY)
            g.put(1,                          row, " ${trunc(j.id, COL_ID).padEnd(COL_ID)}",    C.WH, bg)
            g.put(1 + 1 + COL_ID,             row, " ${trunc(j.queue, COL_Q).padEnd(COL_Q)}",   C.GY, bg)
            g.put(1 + 1 + COL_ID + 1 + COL_Q, row, " ${label.padEnd(12)}",                      fgColor, bg, mods)
            g.put(1 + 1 + COL_ID + 1 + COL_Q + 1 + 12, row, " ${j.lastUpdate}", C.GY, bg)
            g.put(divCol, row, "║", C.CY)
            drawSideRow(idx + 2, row)
            g.put(tw - 1, row, "║", C.CY)
        }

        // Empty fill rows
        for (i in snap.size.coerceAtMost(maxRows) until maxRows) {
            borders(top + 3 + i)
            drawSideRow(i + 2, top + 3 + i)
        }

        // Bottom border
        g.put(0, bot, "╚${"═".repeat(ti)}╩${"═".repeat(si)}╝", C.CY)
    }

    // ── STATS side panel ──────────────────────────────────────────────────────

    private fun drawStatsSide(
        g: TextGraphics, x: Int, idx: Int, row: Int,
        pending: Int, processing: Int, done: Int, failed: Int
    ) {
        when (idx) {
            0  -> g.put(x, row, " METRICS", C.CY, mods = arrayOf(SGR.BOLD))
            1  -> g.put(x, row, " ${"─".repeat(22)}", C.GY)
            2  -> { g.put(x, row, " ◉ PENDING  ", C.YL); g.put(x + 13, row, pending.toString().padStart(3),    C.WH) }
            3  -> { g.put(x, row, " ◉ PROC'ING ", C.MG); g.put(x + 13, row, processing.toString().padStart(3), C.WH) }
            4  -> { g.put(x, row, " ◉ DONE     ", C.GR); g.put(x + 13, row, done.toString().padStart(3),       C.WH) }
            5  -> { g.put(x, row, " ◉ FAILED   ", C.RD); g.put(x + 13, row, failed.toString().padStart(3),     C.WH) }
            7  -> { g.put(x, row, " DRIVER ", C.GY); g.put(x + 8, row, "file", C.CY) }
            8  -> { g.put(x, row, " WATCH  ", C.GY); g.put(x + 8, row, watchSt, if (watchSt == "ACTIVE") C.GR else C.RD) }
            9  -> { g.put(x, row, " DIR    ", C.GY); g.put(x + 8, row, trunc(jobsDir.name, 12), C.WH) }
            10 -> g.put(x, row, " ${ts()}", C.GY)
            11 -> lastCommandResult?.let { g.put(x, row, " ${trunc(it, 22)}", C.GR) }
        }
    }

    // ── LOG side panel ────────────────────────────────────────────────────────

    private fun readLog(jobId: String): List<String> {
        val f = File(jobsDir, "logs/$jobId.log")
        return if (f.exists()) f.readLines()
               else listOf("— no log found —", "expected: logs/$jobId.log")
    }

    private fun drawLogSide(
        g: TextGraphics, x: Int, si: Int,
        idx: Int, row: Int, jobId: String, logLines: List<String>
    ) {
        val maxW = si - 2
        when (idx) {
            0    -> g.put(x, row, " LOG: ${trunc(jobId, maxW - 6)}", C.YL, mods = arrayOf(SGR.BOLD))
            1    -> g.put(x, row, " ${"─".repeat(maxW)}", C.GY)
            else -> logLines.getOrNull(idx - 2)?.let { g.put(x, row, " ${trunc(it, maxW)}", C.GY) }
        }
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private fun renderFooter(g: TextGraphics, tw: Int, th: Int, failed: Int) {
        val r0    = th - 3
        val inner = (tw - 2).coerceAtLeast(0)
        g.put(0, r0, "╔" + "═".repeat(inner) + "╗", C.CY)
        var col = 2
        if (failed == 0) { g.put(col, r0 + 1, "● System: OK   ", C.GR); col += 15 }
        else             { g.put(col, r0 + 1, "● System: ALERT", C.RD); col += 15 }
        g.put(col, r0 + 1, "  ● Watch: ", C.GY); col += 11
        g.put(col, r0 + 1, watchSt, if (watchSt == "ACTIVE") C.GR else C.RD); col += watchSt.length
        val now = "   ${ts()}"
        g.put(col, r0 + 1, now, C.GY); col += now.length
        val hint = when (mode) {
            Mode.LOG   -> "   [ESC] back to watch  [q] quit"
            else       -> "   [:] command  [j/k] select  [Enter] log  [q] quit"
        }
        g.put(col, r0 + 1, hint, C.GY)
        g.put(0, r0 + 2, "╚" + "═".repeat(inner) + "╝", C.CY)
    }

    // ── Command bar (replaces footer in COMMAND mode) ─────────────────────────

    private fun renderCommandBar(g: TextGraphics, tw: Int, th: Int) {
        val r0    = th - 3
        val inner = (tw - 2).coerceAtLeast(0)
        g.put(0, r0, "╔" + "═".repeat(inner) + "╗", C.CY)
        g.put(0, r0 + 1, "║", C.CY)
        g.put(2, r0 + 1, ":", C.YL, mods = arrayOf(SGR.BOLD))
        g.put(4, r0 + 1, commandBuffer.toString(), C.WH)
        g.put(4 + commandBuffer.length, r0 + 1, "█", C.CY)        // blinking cursor feel
        g.put(tw - 1, r0 + 1, "║", C.CY)
        g.put(0, r0 + 2, "╚" + "═".repeat(inner) + "╝", C.CY)
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

    private fun statusStyle(s: Status): Pair<TextColor, String> = when (s) {
        Status.PENDING    -> Pair(C.YL, "PENDING")
        Status.PROCESSING -> Pair(C.MG, "PROCESSING")
        Status.DONE       -> Pair(C.GR, "DONE")
        Status.FAILED     -> Pair(C.RD, "FAILED")
    }

    // ── Initial filesystem scan ───────────────────────────────────────────────

    private fun initialScan() {
        if (!jobsDir.exists()) return
        for (qDir in jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return) {
            val q = qDir.name
            for (f in qDir.listFiles() ?: continue) {
                when {
                    f.name.endsWith(".json.processing") -> {
                        val id = f.name.removeSuffix(".json.processing")
                        jobs[id] = JobEntry(id, q, Status.PROCESSING)
                    }
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
        val dirMap = mutableMapOf<Path, String?>()

        fun reg(d: File, q: String?) {
            d.toPath().register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            dirMap[d.toPath()] = q
        }

        reg(jobsDir, null)
        for (d in jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList<File>()) {
            reg(d, d.name)
        }
        // Also watch logs/ for live log streaming
        File(jobsDir, "logs").let { if (it.exists()) reg(it, null) }

        watchSt = "ACTIVE"
        try {
            while (alive.get()) {
                val key      = ws.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val watchable = key.watchable()
                if (watchable !is Path) { key.reset(); continue }
                val q = dirMap[watchable]

                for (ev in key.pollEvents()) {
                    if (ev.kind() == OVERFLOW) continue
                    @Suppress("UNCHECKED_CAST")
                    val fname = (ev as WatchEvent<Path>).context().fileName.toString()
                    val t = ts()
                    when {
                        q == null && ev.kind() == ENTRY_CREATE && !fname.startsWith(".") -> {
                            val nd = File(jobsDir, fname)
                            if (nd.isDirectory) {
                                reg(nd, if (fname == "logs") null else fname)
                            }
                        }
                        fname.endsWith(".log") -> {
                            if (mode == Mode.LOG) dirty = true   // live log refresh
                        }
                        fname.endsWith(".json.processing") -> {
                            val id = fname.removeSuffix(".json.processing")
                            when (ev.kind()) {
                                ENTRY_CREATE -> { jobs[id] = JobEntry(id, q ?: "?", Status.PROCESSING, t); dirty = true }
                                ENTRY_DELETE -> {
                                    jobs[id]?.let { it.status = Status.DONE; it.lastUpdate = t }
                                    dirty = true
                                    thread(isDaemon = true) { Thread.sleep(3_000); jobs.remove(id); dirty = true }
                                }
                                else -> Unit
                            }
                        }
                        fname.endsWith(".json") && !fname.startsWith(".") -> {
                            val id = fname.removeSuffix(".json")
                            when (ev.kind()) {
                                ENTRY_CREATE -> { jobs.putIfAbsent(id, JobEntry(id, q ?: "?", Status.PENDING, t)); dirty = true }
                                ENTRY_DELETE -> { if (jobs[id]?.status != Status.PROCESSING) { jobs.remove(id); dirty = true } }
                                ENTRY_MODIFY -> { jobs[id]?.lastUpdate = t; dirty = true }
                                else -> Unit
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
}
