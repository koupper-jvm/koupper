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

// 256-color palette indices 8-15 = bright ANSI colors
private object C {
    val CY = TextColor.Indexed(14)   // bright cyan
    val GR = TextColor.Indexed(10)   // bright green
    val MG = TextColor.Indexed(13)   // bright magenta
    val YL = TextColor.Indexed(11)   // bright yellow
    val RD = TextColor.Indexed(9)    // bright red
    val WH = TextColor.Indexed(15)   // bright white
    val GY = TextColor.Indexed(8)    // dark gray
    val DF = TextColor.ANSI.DEFAULT
}

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

    fun run() {
        val terminal = DefaultTerminalFactory().createTerminal()
        val screen   = TerminalScreen(terminal)

        screen.startScreen()          // Lanterna handles raw mode, alt screen, cursor hide
        screen.cursorPosition = null  // hide cursor

        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = true) {
            runCatching { screen.stopScreen() }  // always restore terminal on exit
        })

        initialScan()
        thread(name = "watcher", isDaemon = true) { watchLoop() }

        try {
            while (alive.get()) {
                val key = screen.pollInput()
                if (key != null) when {
                    key.character == 'q' || key.character == 'Q' -> { alive.set(false); break }
                    key.keyType == KeyType.Escape                 -> { alive.set(false); break }
                    key.keyType == KeyType.EOF                    -> { alive.set(false); break }
                }
                screen.doResizeIfNecessary()
                render(screen)
                screen.refresh()
                Thread.sleep(100)
            }
        } finally {
            screen.stopScreen()
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render(screen: TerminalScreen) {
        val tw = screen.terminalSize.columns
        val th = screen.terminalSize.rows
        val g  = screen.newTextGraphics()
        screen.clear()  // wipe back-buffer; refresh() only sends the diff — no flicker

        val snap       = jobs.values.toList().sortedWith(compareBy({ statusRank(it.status) }, { it.queue }, { it.id }))
        val pending    = snap.count { it.status == Status.PENDING }
        val processing = snap.count { it.status == Status.PROCESSING }
        val done       = snap.count { it.status == Status.DONE }
        val failed     = snap.count { it.status == Status.FAILED }

        renderHeader(g, tw)
        renderBody(g, tw, th, snap, pending, processing, done, failed)
        renderFooter(g, tw, th, failed)
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
    private val LOGO_W = 52

    private fun renderHeader(g: TextGraphics, tw: Int) {
        val inner = (tw - 2).coerceAtLeast(0)

        // Row 0 — top border
        g.put(0, 0, "╔" + "═".repeat(inner) + "╗", C.CY)

        // Rows 1–6 — logo
        LOGO.forEachIndexed { i, line ->
            g.put(0, i + 1, "║", C.CY)
            g.put(2, i + 1, line, C.CY, mods = arrayOf(SGR.BOLD))
            g.put(tw - 1, i + 1, "║", C.CY)
        }

        // Row 7 — "SWARM MONITOR" right-aligned
        g.put(0, 7, "║", C.CY)
        val labelCol = (tw - 1 - 13).coerceAtLeast(1)
        g.put(labelCol, 7, "SWARM MONITOR", C.GY)
        g.put(tw - 1, 7, "║", C.CY)

        // Row 8 — centered title
        val title    = "◈  IGLY CORTEX — SWARM MONITOR  ◈"
        val titleCol = ((inner - title.length) / 2 + 1).coerceAtLeast(1)
        g.put(0, 8, "║", C.CY)
        g.put(titleCol, 8, title, C.MG, mods = arrayOf(SGR.BOLD))
        g.put(tw - 1, 8, "║", C.CY)

        // Row 9 — bottom border
        g.put(0, 9, "╚" + "═".repeat(inner) + "╝", C.CY)
    }

    // ── Body: table (left) + sidebar (right) — rows 10 to th-4 ───────────────

    private val SI = 24   // sidebar width (between divider and right border)

    private fun renderBody(
        g: TextGraphics, tw: Int, th: Int, snap: List<JobEntry>,
        pending: Int, processing: Int, done: Int, failed: Int
    ) {
        val ti     = (tw - SI - 3).coerceAtLeast(30)  // table width (between borders)
        val divCol = 1 + ti                             // column of middle ║
        val sideX  = divCol + 1                         // sidebar content start
        val top    = 10
        val bot    = th - 4                             // row of bottom border

        val COL_ID = if (ti > 65) 22 else 15
        val COL_Q  = if (ti > 65) 16 else 10

        // Top border
        val lbl1 = "═ ACTIVE JOBS "
        val lbl2 = "═ STATS "
        g.put(0, top,
            "╔$lbl1${"═".repeat((ti - lbl1.length).coerceAtLeast(0))}" +
            "╦$lbl2${"═".repeat((SI - lbl2.length).coerceAtLeast(0))}╗", C.CY)

        fun borders(row: Int) {
            g.put(0, row, "║", C.CY)
            g.put(divCol, row, "║", C.CY)
            g.put(tw - 1, row, "║", C.CY)
        }

        fun drawSide(sideIdx: Int, row: Int) = when (sideIdx) {
            0  -> { g.put(sideX, row, " METRICS", C.CY, mods = arrayOf(SGR.BOLD)) }
            1  -> { g.put(sideX, row, " ${"─".repeat(SI - 1)}", C.GY) }
            2  -> { g.put(sideX, row, " ◉ PENDING  ", C.YL); g.put(sideX + 13, row, pending.toString().padStart(3),    C.WH) }
            3  -> { g.put(sideX, row, " ◉ PROC'ING ", C.MG); g.put(sideX + 13, row, processing.toString().padStart(3), C.WH) }
            4  -> { g.put(sideX, row, " ◉ DONE     ", C.GR); g.put(sideX + 13, row, done.toString().padStart(3),       C.WH) }
            5  -> { g.put(sideX, row, " ◉ FAILED   ", C.RD); g.put(sideX + 13, row, failed.toString().padStart(3),     C.WH) }
            7  -> { g.put(sideX, row, " DRIVER ", C.GY); g.put(sideX + 8, row, "file",   C.CY) }
            8  -> { g.put(sideX, row, " WATCH  ", C.GY); g.put(sideX + 8, row, watchSt, if (watchSt == "ACTIVE") C.GR else C.RD) }
            9  -> { g.put(sideX, row, " DIR    ", C.GY); g.put(sideX + 8, row, trunc(jobsDir.name, 12), C.WH) }
            10 -> { g.put(sideX, row, " ${ts()}", C.GY) }
            else -> Unit
        }

        // Column header row
        borders(top + 1)
        g.put(1, top + 1, " ${"JOB ID".padEnd(COL_ID)} ${"QUEUE".padEnd(COL_Q)} ${"STATUS".padEnd(12)} UPDATED", C.GY)
        drawSide(0, top + 1)

        // Separator row
        borders(top + 2)
        g.put(1, top + 2, " ${"─".repeat(COL_ID)} ${"─".repeat(COL_Q)} ${"─".repeat(12)} ${"─".repeat(8)}", C.GY)
        drawSide(1, top + 2)

        // Job rows
        val maxRows = (bot - (top + 3)).coerceAtLeast(0)
        snap.take(maxRows).forEachIndexed { idx, j ->
            val row = top + 3 + idx
            val (fgColor, label) = statusStyle(j.status)
            val mods = if (j.status == Status.PROCESSING) arrayOf(SGR.BOLD) else emptyArray()
            borders(row)
            g.put(1, row, " ${trunc(j.id, COL_ID).padEnd(COL_ID)}", C.WH)
            g.put(1 + 1 + COL_ID, row, " ${trunc(j.queue, COL_Q).padEnd(COL_Q)}", C.GY)
            val stCol = 1 + 1 + COL_ID + 1 + COL_Q
            g.put(stCol, row, " ${label.padEnd(12)}", fgColor, mods = mods)
            g.put(stCol + 1 + 12, row, " ${j.lastUpdate}", C.GY)
            drawSide(idx + 2, row)
        }

        // Empty fill rows (clear stale content)
        for (i in snap.size.coerceAtMost(maxRows) until maxRows) {
            borders(top + 3 + i)
            drawSide(i + 2, top + 3 + i)
        }

        // Bottom border
        g.put(0, bot, "╚${"═".repeat(ti)}╩${"═".repeat(SI)}╝", C.CY)
    }

    // ── Footer (rows th-3 to th-1) ────────────────────────────────────────────

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
        g.put(col, r0 + 1, "   [q] quit  [Ctrl+C] exit", C.GY)

        g.put(0, r0 + 2, "╚" + "═".repeat(inner) + "╝", C.CY)
    }

    // ── Style ─────────────────────────────────────────────────────────────────

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
                        q == null && ev.kind() == ENTRY_CREATE -> {
                            val nd = File(jobsDir, fname)
                            if (nd.isDirectory) reg(nd, fname)
                        }
                        fname.endsWith(".json.processing") -> {
                            val id = fname.removeSuffix(".json.processing")
                            when (ev.kind()) {
                                ENTRY_CREATE -> jobs[id] = JobEntry(id, q ?: "?", Status.PROCESSING, t)
                                ENTRY_DELETE -> {
                                    jobs[id]?.let { it.status = Status.DONE; it.lastUpdate = t }
                                    thread(isDaemon = true) { Thread.sleep(3_000); jobs.remove(id) }
                                }
                                else -> Unit
                            }
                        }
                        fname.endsWith(".json") && !fname.startsWith(".") -> {
                            val id = fname.removeSuffix(".json")
                            when (ev.kind()) {
                                ENTRY_CREATE -> jobs.putIfAbsent(id, JobEntry(id, q ?: "?", Status.PENDING, t))
                                ENTRY_DELETE -> if (jobs[id]?.status != Status.PROCESSING) jobs.remove(id)
                                ENTRY_MODIFY -> jobs[id]?.lastUpdate = t
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
