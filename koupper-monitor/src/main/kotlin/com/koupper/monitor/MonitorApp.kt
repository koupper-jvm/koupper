package com.koupper.monitor

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// ── CortexEngine: local LLM via llama-server, no external deps ────────────────
private class CortexEngine(
    private val logFile: File,
    private val agentsDir: File,
    private val modelPath: String  = System.getenv("KOUPPER_LLM_MODEL_PATH")
                                      ?: "/home/tdn-dell/develop/llama.cpp/modelo_prueba.gguf",
    private val serverBin: String  = System.getenv("KOUPPER_LLM_EXECUTABLE")
                                      ?: "/home/tdn-dell/develop/llama.cpp/build/bin/llama-server",
    private val port: Int          = 8081
) {
    private val http      = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val history   = mutableListOf<Map<String, String>>()
    private var serverProc: Process? = null

    private val SYSTEM = """
You are CORTEX, the AI orchestrator of a Koupper automation swarm.
You run entirely on a local LLM — no cloud, no remote APIs.
When asked to create an agent, generate a complete Koupper .kts script wrapped in a kotlin code block:
```kotlin
// Agent: Name
// Role: what it does
import java.io.File
val home = System.getProperty("user.home")
// implementation
```
After generating code, confirm the file was saved.
Be concise — this is a terminal. Keep replies under 8 lines unless generating code.
    """.trimIndent()

    private fun log(msg: String) = logFile.appendText("[${ts()}] $msg\n")

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(): Boolean {
        if (!File(serverBin).exists()) { log("⚠ llama-server not found: $serverBin"); return false }
        if (!File(modelPath).exists()) { log("⚠ Model not found: $modelPath"); return false }

        // Check if already running
        if (isHealthy()) { log("  llama-server already running on port $port"); return true }

        log("  Starting llama-server…")
        serverProc = ProcessBuilder(
            serverBin, "-m", modelPath,
            "--port", port.toString(), "--log-disable"
        ).redirectErrorStream(true)
         .redirectOutput(ProcessBuilder.Redirect.DISCARD)
         .start()

        Runtime.getRuntime().addShutdownHook(Thread { serverProc?.destroy() })

        // Wait up to 60s for health
        repeat(60) {
            if (isHealthy()) return true
            Thread.sleep(1000)
        }
        log("⚠ llama-server failed to start after 60s")
        return false
    }

    private fun isHealthy(): Boolean = runCatching {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/health"))
            .timeout(Duration.ofSeconds(2)).GET().build()
        http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    }.getOrDefault(false)

    // ── Inference ─────────────────────────────────────────────────────────────

    fun greet(swarmContext: String) {
        history.clear()
        history.add(mapOf("role" to "system", "content" to SYSTEM))
        history.add(mapOf("role" to "user",   "content" to "Context: $swarmContext\nGreet the user (2 lines max) and ask what they need built today."))
        val reply = infer()
        history.add(mapOf("role" to "assistant", "content" to reply))
        reply.lines().forEach { log(it) }
        log("")
        log("  Press Enter on this job, then type your request.")
    }

    fun respond(userMsg: String) {
        log("▶ $userMsg")
        log("")
        history.add(mapOf("role" to "user", "content" to userMsg))
        val reply = infer()
        history.add(mapOf("role" to "assistant", "content" to reply))

        val scriptMatch = Regex("```kotlin(.*?)```", RegexOption.DOT_MATCHES_ALL).find(reply)
        if (scriptMatch != null) {
            val script    = scriptMatch.groupValues[1].trim()
            val agentName = Regex("//\\s*Agent:\\s*(.+)").find(script)
                ?.groupValues?.get(1)?.trim()?.replace(" ", "") ?: "Agent${System.currentTimeMillis() % 1000}"
            File(agentsDir, "$agentName.kts").writeText(script)
            val out = reply.replace(scriptMatch.value,
                "\n[✓ Saved → ~/.koupper/agents/$agentName.kts]\n[  Run: koupper run ~/.koupper/agents/$agentName.kts]")
            out.lines().forEach { log(it) }
        } else {
            reply.lines().forEach { log(it) }
        }
        log("")
    }

    // ── HTTP to llama-server (/v1/chat/completions, blocking) ─────────────────

    private fun infer(): String {
        val msgs = history.joinToString(",") { m ->
            """{"role":${jstr(m["role"]!!)},"content":${jstr(m["content"]!!)}}"""
        }
        val body = """{"messages":[$msgs],"stream":false,"n_predict":512,"temperature":0.7}"""

        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            extractContent(resp.body())
        }.getOrElse { e -> "Error: ${e.message?.take(80)}" }
    }

    private fun extractContent(json: String): String {
        // parse "content":"..." from choices[0].message.content
        val m = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(json).lastOrNull()
        return m?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: "[No response]"
    }

    private fun jstr(s: String) =
        "\"${s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""
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
private fun trunc(s: String, n: Int) = if (s.length > n) s.take(n - 1) + "…" else s

// ── Colors ────────────────────────────────────────────────────────────────────
private object C {
    val CY  = TextColor.Indexed(14)
    val GR  = TextColor.Indexed(10)
    val MG  = TextColor.Indexed(13)
    val YL  = TextColor.Indexed(11)
    val RD  = TextColor.Indexed(9)
    val WH  = TextColor.Indexed(15)
    val GY  = TextColor.Indexed(8)
    val SEL = TextColor.Indexed(236)
    val DF  = TextColor.ANSI.DEFAULT
}

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

    // Selection
    private var selectedIdx   = -1
    private var selectedJobId: String? = null

    // Agent system
    private val home       = System.getProperty("user.home")!!
    private val agentsDir  = File(home, ".koupper/agents").also { it.mkdirs() }
    private val koupperBin = "$home/.koupper/bin/koupper"

    // Auto-select CORTEX session job when it appears
    private var greetingPending = true
    private val GREETING_ID     = "cortex-session"

    // Wizard / CORTEX state
    @Volatile private var wizardActive    = false
    private var wizardSessionId: String?  = null
    private var cortexEngine: CortexEngine? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun run() {
        val terminal = DefaultTerminalFactory().createTerminal()
        val screen   = TerminalScreen(terminal)
        screen.startScreen()
        screen.cursorPosition = null

        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = true) {
            runCatching { screen.stopScreen() }
        })

        // No splash animation — go straight to dashboard

        initialScan()
        thread(name = "watcher", isDaemon = true) { watchLoop() }

        thread(name = "cortex-launcher", isDaemon = true) {
            Thread.sleep(400)
            launchCortex()
        }

        try {
            var lastSecond = -1
            while (alive.get()) {
                val key = screen.pollInput()
                if (key != null) {
                    val snap = currentSnap()
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
                }
                Thread.sleep(50)
            }
        } finally {
            screen.stopScreen()
        }
    }

    private fun currentSnap() = jobs.values.toList()
        .sortedWith(compareBy({ statusRank(it.status) }, { it.queue }, { it.id }))

    // ── Greeting animation (startup splash) ───────────────────────────────────

    private fun greeting(screen: TerminalScreen) {
        val tw = screen.terminalSize.columns
        val th = screen.terminalSize.rows
        val g  = screen.newTextGraphics()
        screen.clear(); screen.refresh()

        data class Line(val text: String, val row: Int, val color: TextColor, val bold: Boolean, val ms: Long)
        val lines = listOf(
            Line("CORTEX ONLINE",           th / 2 - 2, C.CY, true,  80L),
            Line("READY FOR INSTRUCTION",   th / 2,     C.GR, false, 45L),
            Line("— IGLY SWARM MONITOR —",  th / 2 + 2, C.GY, false, 30L)
        )
        for (line in lines) {
            val x = ((tw - line.text.length) / 2).coerceAtLeast(0)
            for (i in line.text.indices) {
                if (screen.pollInput() != null) return
                val mods = if (line.bold) arrayOf(SGR.BOLD) else emptyArray()
                g.put(x + i, line.row, line.text[i].toString(), line.color, mods = mods)
                screen.refresh()
                Thread.sleep(line.ms)
            }
            Thread.sleep(200)
        }
        Thread.sleep(700)
    }

    // ── Agent launchers ───────────────────────────────────────────────────────

    private fun launchCortex() {
        val logDir  = File(jobsDir, "logs/cortex").also { it.mkdirs() }
        val logFile = File(logDir, "cortex-session.log")
        logFile.writeText("")

        // Register CORTEX job directly in memory so WatchService isn't needed for startup
        jobs["cortex-session"] = JobEntry("cortex-session", "cortex", Status.PROCESSING)
        wizardActive    = true
        wizardSessionId = "cortex-session"
        dirty = true

        val engine = CortexEngine(logFile, agentsDir)
        cortexEngine = engine

        fun log(msg: String) = logFile.appendText("[${ts()}] $msg\n")

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("  CORTEX ONLINE — Local inference")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        dirty = true

        val started = engine.start()
        if (!started) {
            log("")
            log("  llama-server could not start.")
            log("  Check KOUPPER_LLM_MODEL_PATH and")
            log("  KOUPPER_LLM_EXECUTABLE.")
            jobs["cortex-session"]?.status = Status.FAILED
            dirty = true
            return
        }

        // Swarm context for greeting
        val agentCount = agentsDir.listFiles { f -> f.name.endsWith(".kts") }?.size ?: 0
        val pending    = jobsDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "logs" }
            ?.flatMap { it.listFiles()?.filter { f -> f.name.endsWith(".json") } ?: emptyList() }
            ?.size ?: 0

        engine.greet("$agentCount agents deployed, $pending jobs pending")
        dirty = true
    }

    private fun runFallbackGreeting() {
        // Octopus not available — show static status, no LLM
        jobs[GREETING_ID] = JobEntry(GREETING_ID, "cortex", Status.PROCESSING)
        dirty = true

        val logDir  = File(jobsDir, "logs/cortex").also { it.mkdirs() }
        val logFile = File(logDir, "$GREETING_ID.log")
        logFile.writeText("")
        fun log(msg: String) = logFile.appendText("[${ts()}] $msg\n")

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("  CORTEX — OFFLINE MODE")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("  Octopus daemon is not running.")
        log("  Start it to enable AI features.")
        log("")
        log("  Run: koupper serve")
        log("")
        log("  In offline mode the monitor still")
        log("  tracks jobs in real time.")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        dirty = true

        Thread.sleep(500)
        jobs[GREETING_ID]?.status = Status.DONE
        dirty = true
        Thread.sleep(4_000)
        jobs.remove(GREETING_ID)
        dirty = true
    }

    private fun launchWizard() {
        if (wizardActive) {
            lastCommandResult = "⚠ Wizard active — session: $wizardSessionId"
            dirty = true; return
        }
        val script = File(agentsDir, "AgentCreatorAgent.kts")
        if (!script.exists()) {
            lastCommandResult = "⚠ AgentCreatorAgent.kts not found in $agentsDir"
            dirty = true; return
        }
        if (!File(koupperBin).exists()) {
            lastCommandResult = "⚠ koupper not found at $koupperBin"
            dirty = true; return
        }
        val sessionId = "wizard-${System.currentTimeMillis()}"
        wizardSessionId   = sessionId
        wizardActive      = true
        runCatching {
            ProcessBuilder(koupperBin, "run", script.absolutePath, jobsDir.absolutePath, sessionId)
                .redirectErrorStream(true)
                .start()
        }.onFailure { wizardActive = false; wizardSessionId = null }
        lastCommandResult = "◈ Wizard started"
        dirty = true
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    private fun handleKey(key: com.googlecode.lanterna.input.KeyStroke, snap: List<JobEntry>) {
        when (mode) {
            Mode.WATCH -> when {
                key.character == 'q' || key.character == 'Q' -> alive.set(false)
                key.keyType   == KeyType.Escape               -> alive.set(false)
                key.keyType   == KeyType.EOF                  -> alive.set(false)
                key.character == ':'                          -> { mode = Mode.COMMAND; commandBuffer.clear(); dirty = true }
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
                key.keyType == KeyType.Enter   -> {
                    executeCommand(commandBuffer.toString())
                    commandBuffer.clear(); mode = Mode.WATCH; dirty = true
                }
                key.keyType == KeyType.Backspace && commandBuffer.isNotEmpty() -> {
                    commandBuffer.deleteCharAt(commandBuffer.length - 1); dirty = true
                }
                key.keyType == KeyType.Character && key.character != null -> {
                    commandBuffer.append(key.character); dirty = true
                }
            }
            Mode.LOG -> when {
                key.keyType == KeyType.Escape -> { mode = Mode.WATCH; selectedJobId = null; selectedIdx = -1; dirty = true }
                key.character == 'q' || key.character == 'Q' -> alive.set(false)
                key.keyType   == KeyType.EOF  -> alive.set(false)
                // While viewing wizard log, Enter opens command bar to submit wizard answer
                key.keyType == KeyType.Enter && wizardActive -> {
                    mode = Mode.COMMAND; commandBuffer.clear(); dirty = true
                }
            }
        }
    }

    // ── Command Bridge ────────────────────────────────────────────────────────

    private fun executeCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        when {
            trimmed.equals("help", ignoreCase = true) -> showHelp()
            cortexEngine != null -> {
                lastCommandResult = "◈ thinking…"
                dirty = true
                thread(isDaemon = true) {
                    cortexEngine!!.respond(trimmed)
                    lastCommandResult = null
                    dirty = true
                }
            }
            else -> {
                File(jobsDir, "commands").mkdirs()
                File(jobsDir, "commands/${System.currentTimeMillis()}.cmd").writeText(trimmed)
                lastCommandResult = "→ $trimmed"
            }
        }
    }

    private fun showHelp() {
        lastCommandResult = ":create  :help  j/k navigate  Enter log  ESC back  q quit"
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun render(screen: TerminalScreen) {
        val tw   = screen.terminalSize.columns
        val th   = screen.terminalSize.rows
        val g    = screen.newTextGraphics()
        val snap = currentSnap()

        // Sync selection
        if (snap.isEmpty()) {
            selectedIdx = -1; selectedJobId = null
            if (mode == Mode.LOG) { mode = Mode.WATCH; dirty = true }
        } else {
            if (selectedIdx >= snap.size) selectedIdx = snap.size - 1
            if (selectedIdx >= 0) selectedJobId = snap[selectedIdx].id
        }

        // Auto-select greeting job when it first appears
        if (greetingPending && selectedIdx < 0) {
            val gi = snap.indexOfFirst { it.id == GREETING_ID }
            if (gi >= 0) {
                selectedIdx = gi; selectedJobId = GREETING_ID
                mode = Mode.LOG; greetingPending = false
            }
        }

        // Auto-select wizard job only from WATCH — never override COMMAND mode
        if (wizardActive && wizardSessionId != null && mode == Mode.WATCH) {
            val wi = snap.indexOfFirst { it.id == wizardSessionId }
            if (wi >= 0) { selectedIdx = wi; selectedJobId = wizardSessionId; mode = Mode.LOG }
        }

        // Deactivate wizard when its job disappears
        if (wizardActive && wizardSessionId != null && snap.none { it.id == wizardSessionId }) {
            wizardActive = false; wizardSessionId = null
        }

        val pending    = snap.count { it.status == Status.PENDING }
        val processing = snap.count { it.status == Status.PROCESSING }
        val done       = snap.count { it.status == Status.DONE }
        val failed     = snap.count { it.status == Status.FAILED }

        renderHeader(g, tw)

        when (mode) {
            Mode.WATCH, Mode.COMMAND -> {
                val si = 24; val ti = (tw - si - 3).coerceAtLeast(30)
                val sideX = 1 + ti + 1
                renderBody(g, tw, th, snap, si, selectedIdx, "═ STATS ") { idx, row ->
                    drawStatsSide(g, sideX, idx, row, pending, processing, done, failed)
                }
                if (mode == Mode.COMMAND) renderCommandBar(g, tw, th)
                else renderFooter(g, tw, th, failed)
            }
            Mode.LOG -> {
                val si = (tw / 2).coerceAtLeast(30); val ti = (tw - si - 3).coerceAtLeast(30)
                val sideX   = 1 + ti + 1
                val maxRows = (th - 4 - 13).coerceAtLeast(1)
                val logLines = readLog(selectedJobId ?: "").takeLast(maxRows)
                val panelLbl = if (wizardActive)
                    "═ WIZARD LOG " else "═ LOG "
                renderBody(g, tw, th, snap, si, selectedIdx, panelLbl) { idx, row ->
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
        foregroundColor = fg; backgroundColor = bg
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
            g.put(0, i + 1, "║", C.CY); g.put(2, i + 1, line, C.CY, mods = arrayOf(SGR.BOLD)); g.put(tw - 1, i + 1, "║", C.CY)
        }
        g.put(0, 7, "║", C.CY)
        g.put((tw - 1 - 13).coerceAtLeast(1), 7, "SWARM MONITOR", C.GY)
        g.put(tw - 1, 7, "║", C.CY)
        val title = "◈  IGLY CORTEX — SWARM MONITOR  ◈"
        val tc = ((inner - title.length) / 2 + 1).coerceAtLeast(1)
        g.put(0, 8, "║", C.CY); g.put(tc, 8, title, C.MG, mods = arrayOf(SGR.BOLD)); g.put(tw - 1, 8, "║", C.CY)
        g.put(0, 9, "╚" + "═".repeat(inner) + "╝", C.CY)
    }

    // ── Body ──────────────────────────────────────────────────────────────────

    private fun renderBody(
        g: TextGraphics, tw: Int, th: Int, snap: List<JobEntry>,
        si: Int, selIdx: Int, sideLabel: String,
        drawSideRow: (idx: Int, row: Int) -> Unit
    ) {
        val ti     = (tw - si - 3).coerceAtLeast(30)
        val divCol = 1 + ti
        val top = 10; val bot = th - 4
        val COL_ID = if (ti > 65) 22 else 15
        val COL_Q  = if (ti > 65) 16 else 10

        val lbl1 = "═ ACTIVE JOBS "
        g.put(0, top,
            "╔$lbl1${"═".repeat((ti - lbl1.length).coerceAtLeast(0))}" +
            "╦$sideLabel${"═".repeat((si - sideLabel.length).coerceAtLeast(0))}╗", C.CY)

        fun borders(row: Int) { g.put(0, row, "║", C.CY); g.put(divCol, row, "║", C.CY); g.put(tw - 1, row, "║", C.CY) }

        borders(top + 1)
        g.put(1, top + 1, " ${"JOB ID".padEnd(COL_ID)} ${"QUEUE".padEnd(COL_Q)} ${"STATUS".padEnd(12)} UPDATED", C.GY)
        drawSideRow(0, top + 1)

        borders(top + 2)
        g.put(1, top + 2, " ${"─".repeat(COL_ID)} ${"─".repeat(COL_Q)} ${"─".repeat(12)} ${"─".repeat(8)}", C.GY)
        drawSideRow(1, top + 2)

        val maxRows = (bot - (top + 3)).coerceAtLeast(0)
        snap.take(maxRows).forEachIndexed { idx, j ->
            val row = top + 3 + idx
            val isSelected = idx == selIdx
            val bg = if (isSelected) C.SEL else C.DF
            val (fgColor, label) = statusStyle(j.status)
            val mods = if (j.status == Status.PROCESSING) arrayOf(SGR.BOLD) else emptyArray()
            g.put(0, row, "║", C.CY)
            g.put(1,                               row, " ${trunc(j.id, COL_ID).padEnd(COL_ID)}",  C.WH, bg)
            g.put(1 + 1 + COL_ID,                  row, " ${trunc(j.queue, COL_Q).padEnd(COL_Q)}", C.GY, bg)
            g.put(1 + 1 + COL_ID + 1 + COL_Q,      row, " ${label.padEnd(12)}",                    fgColor, bg, mods)
            g.put(1 + 1 + COL_ID + 1 + COL_Q + 13, row, " ${j.lastUpdate}",                        C.GY, bg)
            g.put(divCol, row, "║", C.CY)
            drawSideRow(idx + 2, row)
            g.put(tw - 1, row, "║", C.CY)
        }
        for (i in snap.size.coerceAtMost(maxRows) until maxRows) {
            borders(top + 3 + i); drawSideRow(i + 2, top + 3 + i)
        }
        g.put(0, bot, "╚${"═".repeat(ti)}╩${"═".repeat(si)}╝", C.CY)
    }

    // ── STATS side panel ──────────────────────────────────────────────────────

    private fun drawStatsSide(g: TextGraphics, x: Int, idx: Int, row: Int,
                               pending: Int, processing: Int, done: Int, failed: Int) {
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
            11 -> if (wizardActive) g.put(x, row, " ◈ WIZARD ACTIVE", C.MG, mods = arrayOf(SGR.BOLD))
                  else lastCommandResult?.let { g.put(x, row, " ${trunc(it, 22)}", C.GR) }
        }
    }

    // ── LOG side panel ────────────────────────────────────────────────────────

    private fun readLog(jobId: String): List<String> {
        // Search logs in all subdirs: logs/cortex/, logs/wizard/, logs/default/, etc.
        val logRoot = File(jobsDir, "logs")
        val found = logRoot.walkTopDown()
            .firstOrNull { it.name == "$jobId.log" }
        return when {
            found != null && found.exists() -> found.readLines()
            else -> listOf("— no log found —", "expected: logs/<queue>/$jobId.log")
        }
    }

    private fun drawLogSide(g: TextGraphics, x: Int, si: Int,
                             idx: Int, row: Int, jobId: String, logLines: List<String>) {
        val maxW = si - 2
        when (idx) {
            0    -> g.put(x, row, " ${trunc(jobId, maxW - 2)}", C.YL, mods = arrayOf(SGR.BOLD))
            1    -> g.put(x, row, " ${"─".repeat(maxW)}", C.GY)
            else -> logLines.getOrNull(idx - 2)?.let { line ->
                val (fg, txt) = when {
                    line.contains("[?]")  -> Pair(C.CY, line)   // question — highlight
                    line.contains("[✓]")  -> Pair(C.GR, line)   // confirmed answer
                    line.contains("[!]")  -> Pair(C.RD, line)   // error/warning
                    line.contains("READY") -> Pair(C.MG, line)  // success
                    else                  -> Pair(C.GY, line)
                }
                g.put(x, row, " ${trunc(txt, maxW)}", fg)
            }
        }
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private fun renderFooter(g: TextGraphics, tw: Int, th: Int, failed: Int) {
        val r0 = th - 3; val inner = (tw - 2).coerceAtLeast(0)
        g.put(0, r0, "╔" + "═".repeat(inner) + "╗", C.CY)
        var col = 2
        if (failed == 0) { g.put(col, r0 + 1, "● System: OK   ", C.GR); col += 15 }
        else             { g.put(col, r0 + 1, "● System: ALERT", C.RD); col += 15 }
        g.put(col, r0 + 1, "  ● Watch: ", C.GY); col += 11
        g.put(col, r0 + 1, watchSt, if (watchSt == "ACTIVE") C.GR else C.RD); col += watchSt.length
        g.put(col, r0 + 1, "   ${ts()}", C.GY); col += 12
        val hint = when {
            wizardActive && mode == Mode.LOG -> "   [Enter] answer wizard  [ESC] watch  [q] quit"
            mode == Mode.LOG                 -> "   [ESC] back  [q] quit"
            else                             -> "   [:] command  [j/k] select  [Enter] log  [q] quit"
        }
        g.put(col, r0 + 1, hint, C.GY)
        g.put(0, r0 + 2, "╚" + "═".repeat(inner) + "╝", C.CY)
    }

    // ── Command bar ───────────────────────────────────────────────────────────

    private fun renderCommandBar(g: TextGraphics, tw: Int, th: Int) {
        val r0 = th - 3; val inner = (tw - 2).coerceAtLeast(0)
        g.put(0, r0, "╔" + "═".repeat(inner) + "╗", C.CY)
        g.put(0, r0 + 1, "║", C.CY)

        if (wizardActive) {
            // Show wizard context: last [?] line as prompt
            val wizardPrompt = wizardSessionId?.let { id ->
                readLog(id).lastOrNull { it.contains("[?]") }
                    ?.substringAfter("[?]")?.trim()
            } ?: "answer"
            g.put(2, r0 + 1, "[WIZARD] ", C.MG, mods = arrayOf(SGR.BOLD))
            g.put(11, r0 + 1, "$wizardPrompt › ", C.GY)
            val offset = 11 + wizardPrompt.length + 3
            g.put(offset, r0 + 1, commandBuffer.toString(), C.WH)
            g.put(offset + commandBuffer.length, r0 + 1, "█", C.CY)
        } else {
            g.put(2, r0 + 1, ": ", C.YL, mods = arrayOf(SGR.BOLD))
            g.put(4, r0 + 1, commandBuffer.toString(), C.WH)
            g.put(4 + commandBuffer.length, r0 + 1, "█", C.CY)
        }

        g.put(tw - 1, r0 + 1, "║", C.CY)
        g.put(0, r0 + 2, "╚" + "═".repeat(inner) + "╝", C.CY)
    }

    // ── Style ─────────────────────────────────────────────────────────────────

    private fun statusStyle(s: Status): Pair<TextColor, String> = when (s) {
        Status.PENDING    -> Pair(C.YL, "PENDING")
        Status.PROCESSING -> Pair(C.MG, "PROCESSING")
        Status.DONE       -> Pair(C.GR, "DONE")
        Status.FAILED     -> Pair(C.RD, "FAILED")
    }

    // ── Initial scan ──────────────────────────────────────────────────────────

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
            for (f in File(qDir, ".failed").listFiles { f -> f.name.endsWith(".json") } ?: continue)
                jobs.putIfAbsent(f.nameWithoutExtension, JobEntry(f.nameWithoutExtension, q, Status.FAILED))
        }
    }

    // ── WatchService ──────────────────────────────────────────────────────────

    private fun watchLoop() {
        if (!jobsDir.exists()) jobsDir.mkdirs()
        val ws     = FileSystems.getDefault().newWatchService()
        val dirMap = mutableMapOf<Path, String?>()

        fun reg(d: File, q: String?) {
            if (!d.exists()) d.mkdirs()
            d.toPath().register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            dirMap[d.toPath()] = q
        }

        reg(jobsDir, null)
        for (d in jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
            reg(d, d.name)

        // Watch logs/ for live log streaming
        File(jobsDir, "logs").also { it.mkdirs() }.toPath()
            .register(ws, ENTRY_CREATE, ENTRY_MODIFY)

        watchSt = "ACTIVE"
        try {
            while (alive.get()) {
                val key       = ws.poll(300, TimeUnit.MILLISECONDS) ?: continue
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
                            if (nd.isDirectory) reg(nd, fname)
                            // Also register log subdirs
                            val logSub = File(jobsDir, "logs/$fname")
                            if (logSub.isDirectory) logSub.toPath().register(ws, ENTRY_CREATE, ENTRY_MODIFY)
                        }
                        fname.endsWith(".log") -> if (mode == Mode.LOG) dirty = true
                        fname.endsWith(".json.processing") -> {
                            val id = fname.removeSuffix(".json.processing")
                            when (ev.kind()) {
                                ENTRY_CREATE -> {
                                    jobs[id] = JobEntry(id, q ?: "?", Status.PROCESSING, t)
                                    // CORTEX agent just started — activate wizard mode
                                    if (id == "cortex-session") {
                                        wizardActive = true
                                        wizardSessionId = "cortex-session"
                                    }
                                    dirty = true
                                }
                                ENTRY_DELETE -> {
                                    jobs[id]?.let { it.status = Status.DONE; it.lastUpdate = t }
                                    dirty = true
                                    thread(isDaemon = true) {
                                        Thread.sleep(3_000)
                                        jobs.remove(id)
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
                key.reset()
            }
        } catch (_: InterruptedException) {
        } finally { ws.close(); watchSt = "STOPPED" }
    }
}
