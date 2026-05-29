// CortexWebUiAgent.kts — IGLY CORTEX Web Dashboard
// Serves a real-time swarm monitor at http://localhost:<port>
// Uses Koupper's RuntimeRouterProvider (Grizzly HTTP) — no external deps.
//
// Usage:  koupper run CortexWebUiAgent.kts [jobsDir] [port]
// Defaults: jobsDir=~/.koupper/jobs, port=18083

import com.koupper.providers.runtime.router.GrizzlyRuntimeRouterProvider
import com.koupper.providers.runtime.router.StreamResponse
import com.koupper.shared.annotations.Export
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

val home    = System.getProperty("user.home")!!
val jobsDir = File(System.getenv("CORTEX_JOBS_DIR") ?: "$home/.koupper/jobs")
val uiPort  = System.getenv("CORTEX_WEB_PORT")?.toIntOrNull() ?: 18083
val mapper  = jacksonObjectMapper()

// ── SSE broadcast ─────────────────────────────────────────────────────────────

val sseClients = CopyOnWriteArrayList<(String) -> Unit>()

fun broadcast(data: String) {
    val dead = mutableListOf<(String) -> Unit>()
    sseClients.forEach { cb -> try { cb(data) } catch (_: Exception) { dead.add(cb) } }
    sseClients.removeAll(dead.toSet())
}

// ── Swarm snapshot ─────────────────────────────────────────────────────────────

val excluded = setOf("logs", "commands")

fun swarmSnapshot(): Map<String, Any> {
    val jobs = mutableListOf<Map<String, Any>>()
    var pending = 0; var processing = 0; var done = 0; var failed = 0

    jobsDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in excluded }
        ?.forEach { qDir ->
            qDir.listFiles()?.forEach { f ->
                when {
                    f.name.endsWith(".json.processing") -> {
                        processing++
                        val id = f.name.removeSuffix(".json.processing")
                        jobs += mapOf("id" to id, "queue" to qDir.name, "status" to "PROCESSING",
                            "updated" to java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
                    }
                    f.name.endsWith(".json") -> {
                        pending++
                        val id = f.nameWithoutExtension
                        jobs += mapOf("id" to id, "queue" to qDir.name, "status" to "PENDING",
                            "updated" to java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
                    }
                }
            }
            File(qDir, ".failed").listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                failed++
                jobs += mapOf("id" to f.nameWithoutExtension, "queue" to qDir.name, "status" to "FAILED",
                    "updated" to java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
            }
        }

    val agentsDir = File(home, ".koupper/agents")
    val agents = agentsDir.listFiles { f -> f.name.endsWith(".kts") }
        ?.map { f ->
            val desc = f.readLines().take(5).mapNotNull {
                Regex("//\\s*(?:Role|Objective)\\s*:\\s*(.+)").find(it)?.groupValues?.get(1)
            }.firstOrNull() ?: ""
            mapOf("name" to f.nameWithoutExtension, "description" to desc)
        } ?: emptyList()

    return mapOf(
        "type"    to "snapshot",
        "jobs"    to jobs,
        "metrics" to mapOf("pending" to pending, "processing" to processing, "done" to done, "failed" to failed),
        "agents"  to agents,
        "time"    to java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    )
}

// ── WatchService → broadcast on filesystem change ─────────────────────────────

fun startWatcher() = Thread {
    val ws = FileSystems.getDefault().newWatchService()
    jobsDir.mkdirs()

    fun reg(d: File) { if (d.exists()) d.toPath().register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
    reg(jobsDir)
    jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { reg(it) }

    while (true) {
        val key = ws.poll(500, TimeUnit.MILLISECONDS) ?: continue
        key.pollEvents()
        if (sseClients.isNotEmpty()) broadcast(mapper.writeValueAsString(swarmSnapshot()))
        key.reset()
    }
}.also { it.isDaemon = true }.start()

// ── HTML Dashboard ────────────────────────────────────────────────────────────

val HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>IGLY CORTEX</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0d1117;color:#c9d1d9;font-family:'Courier New',monospace;font-size:13px}
header{display:flex;justify-content:space-between;align-items:center;padding:12px 20px;border-bottom:1px solid #30363d;background:#161b22}
header h1{color:#79c0ff;font-size:15px;letter-spacing:2px}
#clock{color:#8b949e;font-size:12px}
.metrics{display:flex;gap:20px;padding:12px 20px;background:#0d1117;border-bottom:1px solid #30363d}
.metric{display:flex;flex-direction:column;align-items:center}
.metric .label{color:#8b949e;font-size:11px;text-transform:uppercase;letter-spacing:1px}
.metric .value{font-size:22px;font-weight:bold;margin-top:2px}
.metric .value.pending{color:#e3b341}.metric .value.processing{color:#d2a8ff}
.metric .value.done{color:#56d364}.metric .value.failed{color:#f85149}.metric .value.agents{color:#79c0ff}
.main{display:flex;height:calc(100vh - 110px)}
.jobs-panel{flex:1;overflow-y:auto;border-right:1px solid #30363d}
.jobs-panel h2{padding:10px 16px;color:#8b949e;font-size:11px;text-transform:uppercase;letter-spacing:1px;border-bottom:1px solid #30363d;position:sticky;top:0;background:#0d1117}
table{width:100%;border-collapse:collapse}
th{text-align:left;padding:8px 16px;color:#8b949e;font-size:11px;text-transform:uppercase;letter-spacing:1px;border-bottom:1px solid #30363d;position:sticky;top:32px;background:#0d1117}
td{padding:8px 16px;border-bottom:1px solid #161b22;cursor:pointer}
tr:hover td{background:#161b22}
tr.selected td{background:#21262d}
.status{padding:2px 8px;border-radius:3px;font-size:11px;font-weight:bold}
.status.PROCESSING{background:#2d1b4e;color:#d2a8ff}
.status.PENDING{background:#2d2100;color:#e3b341}
.status.DONE{background:#0d2b0d;color:#56d364}
.status.FAILED{background:#2b0d0d;color:#f85149}
.log-panel{width:420px;overflow-y:auto;background:#161b22}
.log-panel h2{padding:10px 16px;color:#8b949e;font-size:11px;text-transform:uppercase;letter-spacing:1px;border-bottom:1px solid #30363d;position:sticky;top:0;background:#161b22;display:flex;justify-content:space-between}
#log-content{padding:10px 16px;font-size:12px;line-height:1.6;white-space:pre-wrap;color:#8b949e}
#log-content .line-warn{color:#f85149}
#log-content .line-ok{color:#56d364}
#log-content .line-info{color:#d2a8ff}
#status{width:8px;height:8px;border-radius:50%;background:#56d364;display:inline-block;margin-right:6px}
#status.disconnected{background:#f85149}
.empty{text-align:center;padding:40px;color:#30363d;font-size:14px}
</style>
</head>
<body>
<header>
  <h1>◈ &nbsp;IGLY CORTEX — SWARM MONITOR</h1>
  <div><span id="status"></span><span id="clock">--:--:--</span></div>
</header>
<div class="metrics">
  <div class="metric"><span class="label">Pending</span><span class="value pending" id="m-pending">0</span></div>
  <div class="metric"><span class="label">Processing</span><span class="value processing" id="m-processing">0</span></div>
  <div class="metric"><span class="label">Done</span><span class="value done" id="m-done">0</span></div>
  <div class="metric"><span class="label">Failed</span><span class="value failed" id="m-failed">0</span></div>
  <div class="metric"><span class="label">Agents</span><span class="value agents" id="m-agents">0</span></div>
</div>
<div class="main">
  <div class="jobs-panel">
    <h2>Active Jobs</h2>
    <table>
      <thead><tr><th>Job ID</th><th>Queue</th><th>Status</th></tr></thead>
      <tbody id="jobs-tbody"><tr><td colspan="3" class="empty">— no jobs —</td></tr></tbody>
    </table>
  </div>
  <div class="log-panel">
    <h2><span id="log-title">Log</span><span id="log-refresh" style="cursor:pointer;color:#30363d" onclick="refreshLog()">↺</span></h2>
    <div id="log-content">Select a job to view its log.</div>
  </div>
</div>
<script>
let selectedJob = null;

const es = new EventSource('/events');
es.onopen = () => { document.getElementById('status').className = ''; };
es.onmessage = (e) => {
  const d = JSON.parse(e.data);
  if (d.type === 'snapshot') updateUI(d);
};
es.onerror = () => { document.getElementById('status').className = 'disconnected'; };

setInterval(() => {
  const now = new Date();
  document.getElementById('clock').textContent =
    String(now.getHours()).padStart(2,'0') + ':' +
    String(now.getMinutes()).padStart(2,'0') + ':' +
    String(now.getSeconds()).padStart(2,'0');
}, 1000);

function updateUI(d) {
  document.getElementById('m-pending').textContent    = d.metrics.pending;
  document.getElementById('m-processing').textContent = d.metrics.processing;
  document.getElementById('m-done').textContent       = d.metrics.done;
  document.getElementById('m-failed').textContent     = d.metrics.failed;
  document.getElementById('m-agents').textContent     = d.agents.length;

  const tbody = document.getElementById('jobs-tbody');
  if (!d.jobs.length) {
    tbody.innerHTML = '<tr><td colspan="3" class="empty">— no jobs —</td></tr>';
    return;
  }
  tbody.innerHTML = d.jobs.map(j => {
    const sel = j.id === selectedJob ? 'selected' : '';
    return '<tr class="' + sel + '" onclick="selectJob(\'' + j.id + '\')">' +
      '<td>' + j.id + '</td>' +
      '<td style="color:#8b949e">' + j.queue + '</td>' +
      '<td><span class="status ' + j.status + '">' + j.status + '</span></td>' +
    '</tr>';
  }).join('');
}

function selectJob(id) {
  selectedJob = id;
  document.getElementById('log-title').textContent = id;
  refreshLog();
  document.querySelectorAll('tbody tr').forEach(r => r.classList.toggle('selected', r.onclick && r.onclick.toString().includes(id)));
}

function refreshLog() {
  if (!selectedJob) return;
  fetch('/api/logs/' + selectedJob)
    .then(r => r.json())
    .then(d => {
      if (d.error) { document.getElementById('log-content').textContent = d.error; return; }
      document.getElementById('log-content').innerHTML = d.lines.map(l => {
        const cls = l.includes('ERROR') || l.includes('FAIL') || l.includes('[!]') ? 'line-warn'
                  : l.includes('DONE') || l.includes('[✓]') || l.includes('✓') ? 'line-ok'
                  : l.includes('CORTEX') || l.includes('[?]') || l.includes('▶') ? 'line-info'
                  : '';
        return '<span class="' + cls + '">' + l.replace(/</g,'&lt;') + '</span>';
      }).join('\n');
      const el = document.getElementById('log-content');
      el.scrollTop = el.scrollHeight;
    })
    .catch(() => {});
}

// Auto-refresh log every 2s when a job is selected
setInterval(() => { if (selectedJob) refreshLog(); }, 2000);
</script>
</body>
</html>"""

// ── API routes ────────────────────────────────────────────────────────────────

@Export
val setup: () -> Unit = {
    startWatcher()

    val router = GrizzlyRuntimeRouterProvider()

    router.registerRouter {
        // Dashboard HTML
        get<Unit> {
            path { "/" }
            script { { HTML } }
        }

        // Full swarm snapshot
        get<Unit> {
            path { "/api/swarm" }
            script { { mapper.writeValueAsString(swarmSnapshot()) } }
        }

        // Job log — last 200 lines
        get<String> {
            path { "/api/logs/{jobId}" }
            script {
                { jobId: String ->
                    val logRoot = File(jobsDir, "logs")
                    val found   = logRoot.walkTopDown().firstOrNull { it.name == "$jobId.log" }
                    when {
                        found != null && found.exists() ->
                            mapper.writeValueAsString(mapOf("jobId" to jobId, "lines" to found.readLines().takeLast(200)))
                        else ->
                            mapper.writeValueAsString(mapOf("jobId" to jobId, "lines" to emptyList<String>(), "error" to "log not found"))
                    }
                }
            }
        }

        // SSE — real-time job updates
        get<Unit> {
            path { "/events" }
            script { {
                object : StreamResponse {
                    override fun onData(callback: (String) -> Unit) {
                        sseClients.add(callback)
                        // Send initial snapshot immediately
                        try { callback(mapper.writeValueAsString(swarmSnapshot())) } catch (_: Exception) {}
                    }
                    override fun onClose(callback: () -> Unit) { /* server-initiated close, not used */ }
                }
            } }
        }
    }

    router.start(uiPort)
    println("◈ CORTEX Web UI → http://localhost:$uiPort")
    println("  Press Ctrl+C to stop.")
    Thread.currentThread().join()
}
