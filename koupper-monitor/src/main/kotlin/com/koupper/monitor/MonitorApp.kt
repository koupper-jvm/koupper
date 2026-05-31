package com.koupper.monitor

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
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

private fun wrap(s: String, w: Int): List<String> {
    if (s.length <= w) return listOf(s)
    val words = s.split(" ")
    val result = mutableListOf<String>()
    val current = StringBuilder()
    for (word in words) {
        if (current.isEmpty()) {
            current.append(word)
        } else if (current.length + 1 + word.length <= w) {
            current.append(" ").append(word)
        } else {
            result.add(current.toString())
            current.setLength(0)
            current.append(word)
        }
    }
    if (current.isNotEmpty()) result.add(current.toString())
    return result
}

// ── Dashboard Theme (Sync with Web UI) ────────────────────────────────────────

private object C {
    val ACCENT   = TextColor.Indexed(135)  // #7c3aed
    val SUCCESS  = TextColor.Indexed(82)   // #10b981
    val ERROR    = TextColor.Indexed(196)  // #ef4444
    val WARNING  = TextColor.Indexed(214)  // #f59e0b
    val TEXT     = TextColor.Indexed(255)  // #ffffff
    val MUTED    = TextColor.Indexed(244)  // #8b949e
    val SUBTLE   = TextColor.Indexed(238)  // #30363d
    val BG_PANEL = TextColor.Indexed(235)  // #161b22
    val BG_MAIN  = TextColor.Indexed(234)  // #0d1117
    val SEL      = TextColor.Indexed(237)  // Selection BG
}

private enum class Mode { WATCH, LOG, COMMAND }

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
    private var logScroll     = 0

    @Volatile private var wizardActive   = false
    private var wizardSessionId: String? = null
    private var mcpServer: CortexMcpServer? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun run() {
        initialScan()
        thread(name = "watcher",         isDaemon = true) { watchLoop() }
        thread(name = "cortex-launcher", isDaemon = true) { Thread.sleep(400); launchCortex() }

        val terminal = try {
            DefaultTerminalFactory().createTerminal()
        } catch (e: Exception) {
            println("⚠ Headless mode: Terminal not available. Tools (18082) and Web (18083) are still active.")
            while(alive.get()) Thread.sleep(1000)
            return
        }

        val screen = TerminalScreen(terminal)
        screen.startScreen()
        screen.cursorPosition = null

        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = true) {
            runCatching { screen.stopScreen() }
            mcpServer?.stopHttp()
            runCatching { File(jobsDir, "cortex/cortex-session.json.processing").delete() }
        })

        try {
            var lastRenderMs = 0L
            while (alive.get()) {
                val key = screen.pollInput()
                if (key != null) handleKey(key, currentSnap())

                val now = System.currentTimeMillis()
                val resized = screen.doResizeIfNecessary() != null
                
                if (dirty || resized || (now - lastRenderMs >= 100)) {
                    render(screen)
                    screen.refresh()
                    lastRenderMs = now
                    dirty = false
                }
                Thread.sleep(16)
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
                key.character == 'q' || key.character == 'Q' || key.keyType == KeyType.EOF -> alive.set(false)
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
                key.keyType == KeyType.Escape -> { mode = Mode.WATCH; logScroll = 0; dirty = true }
                key.keyType == KeyType.Enter -> { mode = Mode.COMMAND; cmdBuf.clear(); dirty = true }
                key.keyType == KeyType.PageUp -> { logScroll += 5; dirty = true }
                key.keyType == KeyType.PageDown -> { logScroll = (logScroll - 5).coerceAtLeast(0); dirty = true }
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
            }
            Mode.COMMAND -> when {
                key.keyType == KeyType.Escape -> { mode = Mode.LOG; dirty = true }
                key.keyType == KeyType.Enter -> {
                    executeCommand(cmdBuf.toString()); cmdBuf.clear()
                    mode = Mode.LOG; dirty = true
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

    private fun executeCommand(cmd: String) {
        val t = cmd.trim().ifEmpty { return }
        val isWizard = selectedJobId == wizardSessionId && wizardActive
        if (isWizard) {
            File(jobsDir, "commands/wizard").also { it.mkdirs() }
                .let { File(it, "${System.currentTimeMillis()}.response").writeText(t) }
            cmdResult = "◈ Sent"
        } else {
            File(jobsDir, "commands/${System.currentTimeMillis()}.cmd").writeText(t)
            cmdResult = "→ Sent"
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render(screen: TerminalScreen) {
        val tw = screen.terminalSize.columns
        val th = screen.terminalSize.rows
        val g  = screen.newTextGraphics()
        
        g.backgroundColor = C.BG_MAIN
        g.fillRectangle(TerminalPosition(0,0), screen.terminalSize, ' ')

        val snap = currentSnap()
        if (snap.isNotEmpty()) {
            if (selectedIdx < 0) selectedIdx = snap.indexOfFirst { it.id == "cortex-session" }.coerceAtLeast(0)
            selectedIdx = selectedIdx.coerceIn(0, snap.size - 1)
            selectedJobId = snap[selectedIdx].id
        }

        // Layout (Three columns like Web UI)
        val leftW  = (tw * 0.25).toInt().coerceAtLeast(20)
        val rightW = (tw * 0.20).toInt().coerceAtLeast(20)
        val midW   = tw - leftW - rightW - 2

        renderTopBar(g, tw)
        renderLeftPanel(g, 0, 3, leftW, th - 4, snap)
        renderMidPanel(g, leftW + 1, 3, midW, th - 4)
        renderRightPanel(g, tw - rightW, 3, rightW, th - 4, snap)
        renderBottomBar(g, tw, th)
    }

    private fun renderTopBar(g: TextGraphics, tw: Int) {
        g.backgroundColor = C.BG_PANEL
        g.fillRectangle(TerminalPosition(0, 0), TerminalSize(tw, 2), ' ')
        
        // Retro Brain ASCII (Improved)
        g.put(1, 0, "  _---_  ", C.ACCENT)
        g.put(1, 1, " ( @ @ ) ", C.ACCENT)
        
        // Stylized CORTEX (Fixed E)
        g.put(11, 0, "█▀▀ █▀█ █▀█ ▀█▀ █▀▀ █ █", C.TEXT, mods = arrayOf(SGR.BOLD))
        g.put(11, 1, "█▄▄ █▄█ █▀▄  █  █▄▄  █ ", C.ACCENT, mods = arrayOf(SGR.BOLD))
        
        val status = if (watchSt == "ACTIVE") "● ONLINE" else "○ $watchSt"
        g.put(tw - status.length - 2, 0, status, if (watchSt == "ACTIVE") C.SUCCESS else C.ERROR)
        g.put(tw - 10, 1, ts(), C.MUTED)
        
        val center = " SWARM ORCHESTRATOR "
        g.put(tw / 2 - center.length / 2, 0, center, C.MUTED)
    }

    private fun renderLeftPanel(g: TextGraphics, x: Int, y: Int, w: Int, h: Int, snap: List<JobEntry>) {
        val panelY = y + 1 // Offset for 2-line header
        g.put(x + 1, y, "JOBS", C.MUTED, mods = arrayOf(SGR.BOLD))
        g.put(x, y + 1, "─".repeat(w), C.SUBTLE)
        
        snap.take(h - 2).forEachIndexed { i, job ->
            val row = y + 2 + i
            val isSel = i == selectedIdx
            val bg = if (isSel) C.SEL else C.BG_MAIN
            val fg = if (isSel) C.TEXT else C.MUTED
            
            g.backgroundColor = bg
            g.fillRectangle(TerminalPosition(x, row), TerminalSize(w, 1), ' ')
            val arrow = if (isSel) "▶" else " "
            g.put(x, row, "$arrow ${trunc(job.id, w - 4)}", fg, bg)
            val statusColor = statusStyle(job.status).first
            g.put(x + w - 2, row, "●", statusColor, bg)
        }
        g.put(x + w, y, "│", C.SUBTLE) // Vertical separator
    }

    private fun renderMidPanel(g: TextGraphics, x: Int, y: Int, w: Int, h: Int) {
        val title = if (mode == Mode.WATCH) "ACTIVITY" else "LOG: $selectedJobId"
        g.put(x + 2, y, title, C.MUTED, mods = arrayOf(SGR.BOLD))
        g.put(x, y + 1, "─".repeat(w), C.SUBTLE)

        if (mode == Mode.WATCH) {
            g.put(x + w / 2 - 10, y + h / 2, "Select a job to view logs", C.SUBTLE)
        } else {
            val lines = readLog(selectedJobId ?: "", h - 4, w - 4, logScroll)
            lines.forEachIndexed { i, (line, color) ->
                val row = y + 2 + i
                if (row < y + h - 1) {
                    g.put(x + 2, row, line, color)
                }
            }
        }

        if (mode == Mode.COMMAND) {
            val prompt = if (selectedJobId == wizardSessionId) "CORTEX › " else "CMD › "
            val maxLen = w - prompt.length - 4
            val fullCmd = cmdBuf.toString()
            val displayCmd = if (fullCmd.length > maxLen) "…" + fullCmd.takeLast(maxLen - 1) else fullCmd
            
            g.backgroundColor = C.SEL
            g.fillRectangle(TerminalPosition(x, y + h - 1), TerminalSize(w, 1), ' ')
            g.put(x + 2, y + h - 1, prompt, C.ACCENT, mods = arrayOf(SGR.BOLD))
            g.put(x + 2 + prompt.length, y + h - 1, displayCmd + "█", C.TEXT)
        }
        
        g.put(x + w, y, "│", C.SUBTLE) // Vertical separator
    }

    private fun renderRightPanel(g: TextGraphics, x: Int, y: Int, w: Int, h: Int, snap: List<JobEntry>) {
        g.put(x + 1, y, "STATS", C.MUTED, mods = arrayOf(SGR.BOLD))
        g.put(x, y + 1, "─".repeat(w), C.SUBTLE)
        
        val metrics = listOf(
            "TOTAL" to snap.size.toString(),
            "RUNNING" to snap.count { it.status == Status.PROCESSING }.toString(),
            "DONE" to snap.count { it.status == Status.DONE }.toString(),
            "FAILED" to snap.count { it.status == Status.FAILED }.toString()
        )
        
        metrics.forEachIndexed { i, (label, value) ->
            g.put(x + 1, y + 3 + i * 2, label, C.MUTED)
            g.put(x + w - value.length - 2, y + 3 + i * 2, value, C.TEXT, mods = arrayOf(SGR.BOLD))
        }

        g.put(x + 1, y + h - 2, "TIME", C.MUTED)
        g.put(x + w - 10, y + h - 2, ts(), C.TEXT)
    }

    private fun renderBottomBar(g: TextGraphics, tw: Int, th: Int) {
        g.backgroundColor = C.BG_PANEL
        g.fillRectangle(TerminalPosition(0, th - 1), TerminalSize(tw, 1), ' ')
        val hint = when(mode) {
            Mode.LOG -> "[Enter] Chat  [Esc] Back  [j/k] Nav  [q] Quit"
            Mode.COMMAND -> "[Enter] Send  [Esc] Cancel"
            else -> "[Enter] View Log  [:] Command  [j/k] Nav  [q] Quit"
        }
        g.put(2, th - 1, hint, C.MUTED)
        cmdResult?.let { g.put(tw - it.length - 2, th - 1, it, C.SUCCESS) }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private fun TextGraphics.put(col: Int, row: Int, text: String, fg: TextColor, bg: TextColor? = null, mods: Array<SGR> = emptyArray()) {
        foregroundColor = fg
        if (bg != null) backgroundColor = bg
        if (mods.isNotEmpty()) enableModifiers(*mods)
        putString(col, row, text)
        if (mods.isNotEmpty()) disableModifiers(*mods)
    }

    private fun logColor(c: String): TextColor = when {
        c.contains("[DONE]") || c.contains("✓") || c.contains("[OK]") -> C.SUCCESS
        c.contains("ERROR") || c.contains("FAIL") || c.contains("[!]") || c.contains("[FAILED]") -> C.ERROR
        c.contains("▶") || c.contains("CORTEX") || c.contains("[WORKER]") -> C.ACCENT
        c.contains("[DEBUG]") || (c.startsWith("[") && c.contains("]")) -> C.MUTED
        else -> C.TEXT
    }

    private fun statusStyle(s: Status) = when (s) {
        Status.PENDING    -> Pair(C.MUTED, "PENDING")
        Status.PROCESSING -> Pair(C.WARNING, "RUNNING")
        Status.DONE       -> Pair(C.SUCCESS, "DONE")
        Status.FAILED     -> Pair(C.ERROR, "FAILED")
    }

    private fun statusRank(s: Status) = when (s) {
        Status.PROCESSING -> 0; Status.PENDING -> 1; Status.FAILED -> 2; Status.DONE -> 3
    }

    private fun readLog(jobId: String, max: Int, width: Int, scroll: Int = 0): List<Pair<String, TextColor>> {
        val found = File(jobsDir, "logs").walkTopDown().firstOrNull { it.name == "$jobId.log" }
        if (found == null || !found.exists()) return emptyList()
        return try {
            val lines = found.readLines()
            val wrapped = lines.flatMap { line ->
                val c = clean(line)
                val color = logColor(c)
                wrap(c, width).map { it to color }
            }
            val offset = (wrapped.size - max - scroll).coerceAtLeast(0)
            wrapped.drop(offset).take(max)
        } catch (e: Exception) { emptyList() }
    }

    // ── Backend ───────────────────────────────────────────────────────────────

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
                        fname.endsWith(".log") -> if (mode == Mode.LOG || mode == Mode.COMMAND) dirty = true
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
