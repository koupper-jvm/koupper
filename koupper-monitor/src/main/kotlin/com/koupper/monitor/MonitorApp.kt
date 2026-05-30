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
private data class JobEntry(val id: String, val queue: String,
    @Volatile var status: Status, @Volatile var lastUpdate: String = ts())

private fun ts() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
private val ANSI = Regex("\u001B\\[[;\\d]*[mGKHFJA-Za-z]|\r")
private fun clean(s: String) = s.replace(ANSI, "")
private fun trunc(s: String, n: Int) = when { n <= 0 -> ""; s.length > n -> s.take(n - 1) + "…"; else -> s }

// ── Colors ────────────────────────────────────────────────────────────────────

private object C {
    val CY  = TextColor.Indexed(39)   // bright cyan
    val GR  = TextColor.Indexed(82)   // bright green
    val MG  = TextColor.Indexed(135)  // purple-magenta
    val YL  = TextColor.Indexed(220)  // yellow
    val RD  = TextColor.Indexed(196)  // red
    val WH  = TextColor.Indexed(255)  // white
    val GY  = TextColor.Indexed(244)  // gray
    val DM  = TextColor.Indexed(238)  // dim
    val BG  = TextColor.Indexed(235)  // dark bg for selected
    val DF  = TextColor.ANSI.DEFAULT
}

private enum class Mode { WATCH, LOG, COMMAND }

// ── ASCII assets ──────────────────────────────────────────────────────────────

private val BRAIN = listOf(
    "   ╭──∿──╮   ",
    " ╭─╯ ◉ ◉ ╰─╮ ",
    " ╰─╮ ─── ╭─╯ ",
    "   ╰──────╯   "
)

private val CORTEX_ART = listOf(
    " ██████╗ ██████╗ ██████╗ ████████╗███████╗██╗  ██╗",
    "██╔════╝██╔═══██╗██╔══██╗╚══██╔══╝██╔════╝╚██╗██╔╝",
    "██║     ██║   ██║██████╔╝   ██║   █████╗   ╚███╔╝ ",
    "╚██████╗╚██████╔╝██║  ██║   ██║   ███████╗██╔╝ ██╗",
    " ╚═════╝ ╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═╝  ╚═╝"
)

// ── Entry point ───────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    MonitorApp(File(args.firstOrNull { !it.startsWith("-") } ?: "jobs")).run()
}

// ── Application ───────────────────────────────────────────────────────────────

class MonitorApp(private val jobsDir: File) {

    private val jobs   = ConcurrentHashMap<String, JobEntry>()
    private val alive  = AtomicBoolean(true)
    @Volatile private var watchSt = "STARTING"
    @Volatile private var dirty   = true

    private val home       = System.getProperty("user.home")!!
    private val agentsDir  = File(home, ".koupper/agents").also { it.mkdirs() }
    private val koupperBin = "$home/.koupper/bin/koupper"

    @Volatile private var mode = Mode.WATCH
    private val cmdBuf = StringBuilder()
    private var cmdResult: String? = null

    private var selectedIdx   = -1
    private var selectedJobId: String? = null

    @Volatile private var wizardActive   = false
    private var wizardSessionId: String? = null
    private var mcpServer: CortexMcpServer? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun run() {
        val screen = TerminalScreen(DefaultTerminalFactory().createTerminal())
        screen.startScreen()
        screen.cursorPosition = null

        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = true) {
            runCatching { screen.stopScreen() }
            mcpServer?.stopHttp()
            runCatching { File(jobsDir, "cortex/cortex-session.json.processing").delete() }
        })

        initialScan()
        thread(name = "watcher",         isDaemon = true) { watchLoop() }
        thread(name = "cortex-launcher", isDaemon = true) { Thread.sleep(400); launchCortex() }

        try {
            var lastRenderMs = 0L
            var lastSec      = -1
            val FRAME_MS     = 100L   // max 10 fps — eliminates flicker

            while (alive.get()) {
                val key = screen.pollInput()
                if (key != null) handleKey(key, currentSnap())

                val resized = screen.doResizeIfNecessary() != null
                val nowSec  = LocalDateTime.now().second
                val nowMs   = System.currentTimeMillis()
                val frameOk = nowMs - lastRenderMs >= FRAME_MS

                if ((dirty || resized || nowSec != lastSec || mode == Mode.COMMAND) && frameOk) {
                    screen.clear()
                    render(screen)
                    screen.refresh()
                    dirty      = false
                    lastSec    = nowSec
                    lastRenderMs = nowMs
                }
                Thread.sleep(16)   // ~60fps polling, 10fps rendering
            }
        } finally {
            screen.stopScreen()
        }
    }

    private fun currentSnap(): List<JobEntry> = jobs.values.toList()
        .sortedWith(compareBy({ statusRank(it.status) }, { it.queue }, { it.id }))

    // ── Key handling ──────────────────────────────────────────────────────────

    private fun handleKey(key: com.googlecode.lanterna.input.KeyStroke, snap: List<JobEntry>) {
        when (mode) {
            Mode.WATCH -> when {
                key.character == 'q' || key.character == 'Q'
                    || key.keyType == KeyType.Escape
                    || key.keyType == KeyType.EOF -> alive.set(false)
                key.character == ':' -> { mode = Mode.COMMAND; cmdBuf.clear(); dirty = true }
                key.keyType == KeyType.ArrowDown || key.character == 'j' -> {
                    if (snap.isNotEmpty()) {
                        selectedIdx = (selectedIdx + 1).coerceAtMost(snap.size - 1)
                        selectedJobId = snap[selectedIdx].id; dirty = true
                    }
                }
                key.keyType == KeyType.ArrowUp || key.character == 'k' -> {
                    if (snap.isNotEmpty()) {
                        selectedIdx = (selectedIdx - 1).coerceAtLeast(0)
                        selectedJobId = snap[selectedIdx].id; dirty = true
                    }
                }
                key.keyType == KeyType.Enter && selectedIdx in snap.indices -> {
                    selectedJobId = snap[selectedIdx].id; mode = Mode.LOG; dirty = true
                }
            }
            Mode.LOG -> when {
                key.keyType == KeyType.Escape -> {
                    wizardActive = false; mode = Mode.WATCH; dirty = true
                }
                key.keyType == KeyType.ArrowDown || key.character == 'j' -> {
                    if (snap.isNotEmpty()) {
                        selectedIdx = (selectedIdx + 1).coerceAtMost(snap.size - 1)
                        selectedJobId = snap[selectedIdx].id; dirty = true
                    }
                }
                key.keyType == KeyType.ArrowUp || key.character == 'k' -> {
                    if (snap.isNotEmpty()) {
                        selectedIdx = (selectedIdx - 1).coerceAtLeast(0)
                        selectedJobId = snap[selectedIdx].id; dirty = true
                    }
                }
                key.keyType == KeyType.Enter && wizardActive -> {
                    mode = Mode.COMMAND; cmdBuf.clear(); dirty = true
                }
                key.character == 'q' || key.character == 'Q'
                    || key.keyType == KeyType.EOF -> alive.set(false)
            }
            Mode.COMMAND -> when {
                key.keyType == KeyType.Escape -> {
                    mode = if (wizardActive) Mode.LOG else Mode.WATCH
                    cmdBuf.clear(); dirty = true
                }
                key.keyType == KeyType.Enter -> {
                    executeCommand(cmdBuf.toString()); cmdBuf.clear()
                    mode = if (wizardActive) Mode.LOG else Mode.WATCH; dirty = true
                }
                key.keyType == KeyType.Backspace && cmdBuf.isNotEmpty() -> {
                    cmdBuf.deleteCharAt(cmdBuf.length - 1); dirty = true
                }
                key.keyType == KeyType.Character && key.character != null -> {
                    cmdBuf.append(key.character); dirty = true
                }
            }
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private fun executeCommand(cmd: String) {
        val t = cmd.trim().ifEmpty { return }
        when {
            t.equals("help", ignoreCase = true) ->
                cmdResult = "  ↑↓  navigate   Enter  view log   Esc  back   q  quit   :  cmd"
            wizardActive -> {
                File(jobsDir, "commands/wizard").also { it.mkdirs() }
                    .let { File(it, "${System.currentTimeMillis()}.response").writeText(t) }
                cmdResult = "◈ sent"
            }
            else -> {
                File(jobsDir, "commands/${System.currentTimeMillis()}.cmd").writeText(t)
                cmdResult = "→ $t"
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render(screen: TerminalScreen) {
        val tw = screen.terminalSize.columns
        val th = screen.terminalSize.rows
        val g  = screen.newTextGraphics()
        val snap = currentSnap()

        // Keep selection in bounds
        if (snap.isEmpty()) {
            selectedIdx = -1; selectedJobId = null
            if (mode == Mode.LOG) mode = Mode.WATCH
        } else {
            if (selectedIdx < 0) {
                val ci = snap.indexOfFirst { it.id == "cortex-session" }
                selectedIdx = if (ci >= 0) ci else 0
            }
            selectedIdx   = selectedIdx.coerceIn(0, snap.size - 1)
            selectedJobId = snap[selectedIdx].id
        }

        if (wizardActive && wizardSessionId != null && snap.none { it.id == wizardSessionId }) {
            wizardActive = false; wizardSessionId = null
        }

        // Layout constants
        val HDR = 9   // rows 0..8  (border0, brain+cortex 1..5, divider 6, subtitle 7, border 8)
        val FOT = 2   // footer rows at bottom

        renderHeader(g, tw)
        renderBody(g, tw, th, HDR, FOT, snap)
        if (mode == Mode.COMMAND) renderCmdBar(g, tw, th) else renderFooter(g, tw, th)
    }

    // ── Header (rows 0–8) ─────────────────────────────────────────────────────

    private fun renderHeader(g: TextGraphics, tw: Int) {
        val inner = (tw - 2).coerceAtLeast(0)
        g.put(0, 0, "╔" + "═".repeat(inner) + "╗", C.CY)
        g.put(0, 8, "╚" + "═".repeat(inner) + "╝", C.CY)

        // Borders left/right for rows 1-7
        for (r in 1..7) { g.put(0, r, "║", C.CY); g.put(tw - 1, r, "║", C.CY) }

        // Brain (4 rows) on the left, CORTEX art (5 rows) on the right
        val brainW = BRAIN[0].length
        val cortexX = brainW + 4

        BRAIN.forEachIndexed       { i, line -> g.put(2,       i + 1, line,           C.MG) }
        CORTEX_ART.forEachIndexed  { i, line -> g.put(cortexX, i + 1, line,           C.CY, mods = arrayOf(SGR.BOLD)) }

        // Divider row 6
        g.put(1, 6, "─".repeat(inner - 1), C.DM)

        // Subtitle row 7
        val subtitle = "  IGLY · SWARM MONITOR"
        val timeStr  = "  ${ts()}"
        val wstate   = if (watchSt == "ACTIVE") "  ● ACTIVE" else "  ○ $watchSt"
        var col = 1
        g.put(col, 7, subtitle, C.GY); col += subtitle.length
        g.put(col, 7, timeStr, C.DM); col += timeStr.length
        g.put(col, 7, wstate, if (watchSt == "ACTIVE") C.GR else C.RD)
    }

    // ── Body (rows HDR..(th-FOT-1)) ───────────────────────────────────────────

    private fun renderBody(g: TextGraphics, tw: Int, th: Int,
                            HDR: Int, FOT: Int, snap: List<JobEntry>) {
        val top = HDR
        val bot = th - FOT - 1   // inclusive last body row

        val leftW  = (tw * 52 / 100).coerceAtLeast(25)
        val rightW = (tw - leftW - 3).coerceAtLeast(20)
        val divX   = leftW + 1

        val lbl  = if (mode == Mode.LOG) "═ JOBS " else "═ JOBS "
        val rlbl = if (mode == Mode.LOG) "═ LOG " else "═ STATS "

        // Top border
        g.put(0, top,
            "╔$lbl${"═".repeat((leftW - lbl.length).coerceAtLeast(0))}╦" +
            "$rlbl${"═".repeat((rightW - rlbl.length).coerceAtLeast(0))}╗", C.CY)

        val COL_ID = (leftW - 26).coerceIn(8, 28)
        val COL_Q  = 9

        // Column header
        g.put(0, top + 1, "║", C.CY)
        g.put(1, top + 1,
            "  ${"ID".padEnd(COL_ID)} ${"QUEUE".padEnd(COL_Q)} ${"STATUS".padEnd(10)} TIME    ",
            C.GY)
        g.put(divX, top + 1, "║", C.CY); g.put(tw - 1, top + 1, "║", C.CY)

        // Separator
        g.put(0, top + 2, "║", C.CY)
        g.put(1, top + 2, "  " + "─".repeat(COL_ID) + " " +
            "─".repeat(COL_Q) + " " + "─".repeat(10) + " " + "─".repeat(8), C.DM)
        g.put(divX, top + 2, "║", C.CY); g.put(tw - 1, top + 2, "║", C.CY)

        val maxRows = (bot - (top + 3)).coerceAtLeast(0)
        val logLines = if (mode == Mode.LOG) readLog(selectedJobId ?: "", maxRows) else emptyList()

        snap.take(maxRows).forEachIndexed { idx, j ->
            val row = top + 3 + idx
            val isSel = idx == selectedIdx
            val bg = if (isSel) C.BG else C.DF
            val (fc, lbl2) = statusStyle(j.status)
            val arrow = if (isSel) "▶" else " "
            g.put(0, row, "║", C.CY)
            g.put(1, row,
                "$arrow ${trunc(j.id, COL_ID).padEnd(COL_ID)} " +
                "${trunc(j.queue, COL_Q).padEnd(COL_Q)} " +
                "${lbl2.padEnd(10)} ${j.lastUpdate} ",
                if (isSel) C.WH else C.GY, bg,
                if (j.status == Status.PROCESSING) arrayOf(SGR.BOLD) else emptyArray())
            // Status badge color override
            val badgeX = 1 + 2 + COL_ID + 1 + COL_Q + 1
            g.put(badgeX, row, lbl2.padEnd(10), fc, bg,
                if (j.status == Status.PROCESSING) arrayOf(SGR.BOLD) else emptyArray())
            g.put(divX, row, "║", C.CY)
            drawRightPanel(g, divX + 1, rightW, idx, row, snap, logLines)
            g.put(tw - 1, row, "║", C.CY)
        }

        for (i in snap.size.coerceAtMost(maxRows) until maxRows) {
            val row = top + 3 + i
            g.put(0, row, "║", C.CY)
            g.put(1, row, " ".repeat((divX - 1).coerceAtLeast(0)), C.DF)
            g.put(divX, row, "║", C.CY)
            drawRightPanel(g, divX + 1, rightW, i, row, snap, logLines)
            g.put(tw - 1, row, "║", C.CY)
        }

        g.put(0, bot, "╚${"═".repeat(leftW)}╩${"═".repeat(rightW)}╝", C.CY)
    }

    // ── Right panel ───────────────────────────────────────────────────────────

    private fun drawRightPanel(g: TextGraphics, x: Int, w: Int, idx: Int, row: Int, snap: List<JobEntry>, logLines: List<String>) {
        if (mode == Mode.LOG) {
            drawLogRow(g, x, w, idx, row, selectedJobId ?: "", logLines)
        } else {
            drawStatsRow(g, x, w, idx, row, snap)
        }
    }

    private fun drawStatsRow(g: TextGraphics, x: Int, w: Int, idx: Int, row: Int, snap: List<JobEntry>) {
        val pending    = snap.count { it.status == Status.PENDING }
        val processing = snap.count { it.status == Status.PROCESSING }
        val done       = snap.count { it.status == Status.DONE }
        val failed     = snap.count { it.status == Status.FAILED }
        g.put(x, row, " ".repeat(w.coerceAtLeast(0)), C.DF)  // clear row
        when (idx) {
            0  -> g.put(x, row, " METRICS", C.CY, mods = arrayOf(SGR.BOLD))
            1  -> g.put(x, row, " ${"─".repeat((w - 2).coerceAtLeast(0))}", C.DM)
            2  -> { g.put(x,      row, " ● PENDING  ", C.YL); g.put(x + 13, row, pending.toString().padStart(4), C.WH) }
            3  -> { g.put(x,      row, " ● RUNNING  ", C.MG, mods = arrayOf(SGR.BOLD)); g.put(x + 13, row, processing.toString().padStart(4), C.WH) }
            4  -> { g.put(x,      row, " ● DONE     ", C.GR); g.put(x + 13, row, done.toString().padStart(4), C.WH) }
            5  -> { g.put(x,      row, " ● FAILED   ", C.RD); g.put(x + 13, row, failed.toString().padStart(4), C.WH) }
            7  -> { g.put(x, row, " DIR  ", C.DM); g.put(x + 6, row, trunc(jobsDir.name, w - 7), C.WH) }
            8  -> { g.put(x, row, " TIME ", C.DM); g.put(x + 6, row, ts(), C.GY) }
            10 -> if (wizardActive) g.put(x, row, " ◈ WIZARD ACTIVE", C.MG, mods = arrayOf(SGR.BOLD))
                  else cmdResult?.let { g.put(x, row, " ${trunc(it, w - 2)}", C.GR) }
        }
    }

    private fun drawLogRow(g: TextGraphics, x: Int, w: Int, idx: Int, row: Int, jobId: String, lines: List<String>) {
        val maxW = (w - 2).coerceAtLeast(1)
        g.put(x, row, " ".repeat(w.coerceAtLeast(0)), C.DF)  // clear row
        when (idx) {
            0 -> g.put(x, row, " ${trunc(jobId, maxW)}", C.YL, mods = arrayOf(SGR.BOLD))
            1 -> g.put(x, row, " ${"─".repeat(maxW)}", C.DM)
            else -> lines.getOrNull(idx - 2)?.let { line ->
                val c = clean(line)
                val fc = when {
                    c.contains("[DONE]") || c.contains("✓") || c.contains("[OK]") -> C.GR
                    c.contains("ERROR") || c.contains("FAIL") || c.contains("[!]") || c.contains("[FAILED]") || c.contains("[TIMEOUT]") -> C.RD
                    c.contains("▶") || c.contains("CORTEX") || c.contains("[WORKER]") -> C.CY
                    c.contains("[DEBUG]") || (c.startsWith("[") && c.contains("]")) -> C.GY
                    else -> C.WH
                }
                g.put(x, row, " ${trunc(c, maxW)}", fc)
            }
        }
    }

    // ── Footer (2 rows) ───────────────────────────────────────────────────────

    private fun renderFooter(g: TextGraphics, tw: Int, th: Int) {
        val r = th - 2; val inner = (tw - 2).coerceAtLeast(0)
        g.put(0, r,     "╔" + "═".repeat(inner) + "╗", C.CY)
        g.put(0, r + 1, "╚" + "═".repeat(inner) + "╝", C.CY)
        g.put(0, r, "║", C.CY); g.put(tw - 1, r, "║", C.CY)

        val hint = when (mode) {
            Mode.LOG   -> "  [↑↓ / j·k]  prev·next job    [Esc]  back to list    [q]  quit" +
                          if (wizardActive) "    [Enter]  answer CORTEX" else ""
            Mode.WATCH -> "  [↑↓ / j·k]  navigate    [Enter]  view log    [:]  command    [q]  quit"
            else -> ""
        }
        g.put(2, r, trunc(hint, inner - 2), C.DM)
    }

    // ── Command bar ───────────────────────────────────────────────────────────

    private fun renderCmdBar(g: TextGraphics, tw: Int, th: Int) {
        val r = th - 2; val inner = (tw - 2).coerceAtLeast(0)
        g.put(0, r,     "╔" + "═".repeat(inner) + "╗", C.CY)
        g.put(0, r + 1, "╚" + "═".repeat(inner) + "╝", C.CY)
        g.put(0, r, "║", C.CY); g.put(tw - 1, r, "║", C.CY)

        if (wizardActive) {
            val prompt = wizardSessionId?.let { id ->
                readLog(id, 50).lastOrNull { it.contains("[?]") }
                    ?.substringAfter("[?]")?.trim()
            } ?: "answer"
            g.put(2, r, "[WIZARD]  $prompt › $cmdBuf█", C.WH)
        } else {
            g.put(2, r, ":  $cmdBuf█", C.CY)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun TextGraphics.put(col: Int, row: Int, text: String,
        fg: TextColor = C.WH, bg: TextColor = C.DF, mods: Array<SGR> = emptyArray()) {
        foregroundColor = fg; backgroundColor = bg
        if (mods.isNotEmpty()) enableModifiers(*mods)
        putString(col, row, text)
        if (mods.isNotEmpty()) disableModifiers(*mods)
    }

    private fun statusRank(s: Status) = when (s) {
        Status.PROCESSING -> 0; Status.PENDING -> 1; Status.FAILED -> 2; Status.DONE -> 3
    }

    private fun statusStyle(s: Status): Pair<TextColor, String> = when (s) {
        Status.PENDING    -> Pair(C.YL,  "PENDING")
        Status.PROCESSING -> Pair(C.MG,  "RUNNING")
        Status.DONE       -> Pair(C.GR,  "DONE")
        Status.FAILED     -> Pair(C.RD,  "FAILED")
    }

    private fun readLog(jobId: String, maxLines: Int): List<String> {
        val found = File(jobsDir, "logs").walkTopDown().firstOrNull { it.name == "$jobId.log" }
        if (found == null || !found.exists()) return listOf("— no log —", "expected: logs/<queue>/$jobId.log")
        
        return try {
            val allLines = found.readLines()
            if (allLines.size <= maxLines) allLines else allLines.takeLast(maxLines)
        } catch (e: Exception) {
            listOf("— error reading log —", e.message ?: "unknown error")
        }
    }

    // ── Backend (unchanged) ───────────────────────────────────────────────────

    private fun launchCortex() {
        val logDir  = File(jobsDir, "logs/cortex").also { it.mkdirs() }
        val logFile = File(logDir, "cortex-session.log")
        logFile.writeText("")
        val cortexQueueDir = File(jobsDir, "cortex").also { it.mkdirs() }
        File(cortexQueueDir, "cortex-session.json.processing").writeText(
            """{"id":"cortex-session","fileName":"CortexAgent","functionName":"cortex","scriptPath":"agents/CortexAgent.kts","sourceType":"script"}"""
        )
        jobs["cortex-session"] = JobEntry("cortex-session", "cortex", Status.PROCESSING)
        wizardActive = true; wizardSessionId = "cortex-session"; dirty = true

        val mcp = CortexMcpServer(jobsDir, agentsDir)
        mcp.startHttp(); mcpServer = mcp

        val agentScript = File(agentsDir, "CortexAgent.kts")
        if (!agentScript.exists() || !File(koupperBin).exists()) {
            logFile.appendText("[${ts()}] ⚠ CortexAgent.kts not found.\n")
            jobs["cortex-session"]?.status = Status.FAILED; dirty = true; return
        }

        thread(name = "cortex-agent", isDaemon = true) {
            runCatching {
                ProcessBuilder(koupperBin, "run", agentScript.absolutePath)
                    .also { pb -> pb.environment().apply {
                        System.getenv("KOUPPER_LLM_MODEL_PATH")?.let { put("KOUPPER_LLM_MODEL_PATH", it) }
                        System.getenv("KOUPPER_LLM_EXECUTABLE")?.let { put("KOUPPER_LLM_EXECUTABLE", it) }
                        put("CORTEX_JOBS_DIR", jobsDir.absolutePath)
                    }; pb.redirectErrorStream(true) }.start().waitFor()
            }
            jobs["cortex-session"]?.status = Status.DONE; dirty = true
        }

        val webAgent = File(agentsDir, "CortexWebUiAgent.kts")
        if (webAgent.exists()) thread(name = "web-ui", isDaemon = true) {
            runCatching {
                ProcessBuilder(koupperBin, "run", webAgent.absolutePath)
                    .also { it.environment()["CORTEX_JOBS_DIR"] = jobsDir.absolutePath }
                    .redirectErrorStream(true).start()
            }
        }
        dirty = true
    }

    private fun launchWizard() {
        if (wizardActive) { cmdResult = "⚠ wizard already active"; dirty = true; return }
        val script = File(agentsDir, "AgentCreatorAgent.kts")
        if (!script.exists()) { cmdResult = "⚠ AgentCreatorAgent.kts not found"; dirty = true; return }
        val sid = "wizard-${System.currentTimeMillis()}"
        wizardSessionId = sid; wizardActive = true
        runCatching {
            ProcessBuilder(koupperBin, "run", script.absolutePath, jobsDir.absolutePath, sid)
                .redirectErrorStream(true).start()
        }.onFailure { wizardActive = false; wizardSessionId = null }
        cmdResult = "◈ wizard started"; dirty = true
    }

    private fun initialScan() {
        if (!jobsDir.exists()) return
        for (qDir in jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return) {
            val q = qDir.name
            for (f in qDir.listFiles() ?: continue) when {
                f.name.endsWith(".json.processing") -> {
                    val id = f.name.removeSuffix(".json.processing")
                    jobs[id] = JobEntry(id, q, Status.PROCESSING)
                    if (id == "cortex-session") { wizardActive = true; wizardSessionId = id }
                }
                f.name.endsWith(".json") ->
                    jobs.putIfAbsent(f.nameWithoutExtension, JobEntry(f.nameWithoutExtension, q, Status.PENDING))
            }
            for (f in File(qDir, ".failed").listFiles { f -> f.name.endsWith(".json") } ?: continue)
                jobs.putIfAbsent(f.nameWithoutExtension, JobEntry(f.nameWithoutExtension, q, Status.FAILED))
        }
    }

    private fun watchLoop() {
        if (!jobsDir.exists()) jobsDir.mkdirs()
        val ws = FileSystems.getDefault().newWatchService()
        val dirMap = mutableMapOf<Path, String?>()
        fun reg(d: File, q: String?) {
            if (!d.exists()) d.mkdirs()
            d.toPath().register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            dirMap[d.toPath()] = q
        }
        reg(jobsDir, null)
        for (d in jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
            reg(d, d.name)
        File(jobsDir, "logs").also { it.mkdirs() }.toPath().register(ws, ENTRY_CREATE, ENTRY_MODIFY)
        watchSt = "ACTIVE"
        try {
            while (alive.get()) {
                val wk = ws.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val watchable = wk.watchable(); if (watchable !is Path) { wk.reset(); continue }
                val q = dirMap[watchable]
                for (ev in wk.pollEvents()) {
                    if (ev.kind() == OVERFLOW) continue
                    @Suppress("UNCHECKED_CAST")
                    val fname = (ev as WatchEvent<Path>).context().fileName.toString()
                    val t = ts()
                    when {
                        q == null && ev.kind() == ENTRY_CREATE && !fname.startsWith(".") -> {
                            val nd = File(jobsDir, fname)
                            if (nd.isDirectory) reg(nd, fname)
                            val ls = File(jobsDir, "logs/$fname")
                            if (ls.isDirectory) ls.toPath().register(ws, ENTRY_CREATE, ENTRY_MODIFY)
                        }
                        fname.endsWith(".log") -> if (mode == Mode.LOG) dirty = true
                        fname.endsWith(".json.processing") -> {
                            val id = fname.removeSuffix(".json.processing")
                            when (ev.kind()) {
                                ENTRY_CREATE -> {
                                    jobs[id] = JobEntry(id, q ?: "?", Status.PROCESSING, t)
                                    if (id == "cortex-session") { wizardActive = true; wizardSessionId = id }
                                    dirty = true
                                }
                                ENTRY_DELETE -> {
                                    jobs[id]?.let { it.status = Status.DONE; it.lastUpdate = t }; dirty = true
                                    thread(isDaemon = true) {
                                        Thread.sleep(3_000); jobs.remove(id)
                                        if (id == wizardSessionId) { wizardActive = false; wizardSessionId = null }
                                        dirty = true
                                    }
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
                wk.reset()
            }
        } catch (_: InterruptedException) {
        } finally { ws.close(); watchSt = "STOPPED" }
    }
}
