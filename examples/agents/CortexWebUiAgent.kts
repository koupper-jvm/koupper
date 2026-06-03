// CortexWebUiAgent.kts — IGLY CORTEX Web Dashboard (polished)
// Serves a real-time swarm monitor at http://localhost:<port>
// Uses Koupper's RuntimeRouterProvider (Grizzly HTTP) — no external deps.
//
// Config:
//   CORTEX_JOBS_DIR   — jobs directory (default: ~/.koupper/jobs)
//   CORTEX_WEB_PORT   — HTTP port (default: 18083)

import com.koupper.container.app
import com.koupper.providers.runtime.router.GrizzlyRuntimeRouterProvider
import com.koupper.providers.runtime.router.StreamResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

val jobsDir = File(env("CORTEX_JOBS_DIR", "$home/.koupper/jobs"))
val uiPort  = env("CORTEX_WEB_PORT", "18083").toIntOrNull() ?: 18083
val mapper  = jacksonObjectMapper()

val excluded = setOf("logs", "commands")

// ── SSE broadcast ─────────────────────────────────────────────────────────────

val sseClients = CopyOnWriteArrayList<(String) -> Unit>()

fun broadcast(data: String) {
    val dead = mutableListOf<(String) -> Unit>()
    sseClients.forEach { cb -> try { cb(data) } catch (_: Exception) { dead.add(cb) } }
    sseClients.removeAll(dead.toSet())
}

// ── Swarm snapshot ────────────────────────────────────────────────────────────

fun swarmSnapshot(): Map<String, Any> {
    val jobs = mutableListOf<Map<String, Any>>()
    var pending = 0; var processing = 0; var failed = 0; var done = 0

    jobsDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in excluded }
        ?.forEach { qDir ->
            qDir.listFiles()?.forEach { f ->
                when {
                    f.name.endsWith(".json.processing") -> {
                        processing++
                        jobs += mapOf("id" to f.name.removeSuffix(".json.processing"),
                            "queue" to qDir.name, "status" to "PROCESSING",
                            "time" to java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
                    }
                    f.name.endsWith(".json") -> {
                        pending++
                        jobs += mapOf("id" to f.nameWithoutExtension,
                            "queue" to qDir.name, "status" to "PENDING",
                            "time" to java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
                    }
                }
            }
            // Include failed jobs from .failed/
            File(qDir, ".failed").listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                failed++
                jobs += mapOf("id" to f.nameWithoutExtension,
                    "queue" to qDir.name, "status" to "FAILED",
                    "time" to java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
            }
        }

    val agentsDir = File(home, ".koupper/agents")
    val agents = agentsDir.listFiles { f -> f.name.endsWith(".kts") }
        ?.map { f ->
            val header = runCatching { f.readLines().take(6).joinToString("\n") }.getOrDefault("")
            val desc   = Regex("//\\s*(?:Role|Objective|Description)\\s*:\\s*(.+)").find(header)
                ?.groupValues?.get(1)?.trim() ?: ""
            mapOf("name" to f.nameWithoutExtension, "description" to desc)
        } ?: emptyList()

    val schedules = runCatching {
        val f = File(home, ".koupper/schedules.json")
        if (f.exists()) mapper.readValue<List<Map<String, Any>>>(f) else emptyList()
    }.getOrDefault(emptyList())

    val cortexActive = jobs.any { it["id"] == "cortex-session" && it["status"] == "PROCESSING" }

    return mapOf(
        "type"         to "snapshot",
        "jobs"         to jobs,
        "metrics"      to mapOf("pending" to pending, "processing" to processing, "done" to done, "failed" to failed),
        "agents"       to agents,
        "schedules"    to schedules,
        "cortexActive" to cortexActive,
        "time"         to java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    )
}

// ── WatchService ──────────────────────────────────────────────────────────────

fun startWatcher() = Thread {
    val ws = FileSystems.getDefault().newWatchService()
    jobsDir.mkdirs()

    fun reg(d: File) { if (d.exists()) d.toPath().register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
    reg(jobsDir)
    jobsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { reg(it) }

    while (true) {
        val key = ws.poll(500, TimeUnit.MILLISECONDS) ?: continue

        for (ev in key.pollEvents()) {
            // When a new subdirectory appears in jobsDir, register it immediately
            // This handles cortex/, default/, pipeline/ etc. created after startup
            @Suppress("UNCHECKED_CAST")
            val fname = (ev as? java.nio.file.WatchEvent<java.nio.file.Path>)?.context()?.fileName?.toString() ?: continue
            if (ev.kind() == ENTRY_CREATE) {
                val newDir = File(jobsDir, fname)
                if (newDir.isDirectory && !fname.startsWith(".") && fname !in excluded) {
                    reg(newDir)
                }
            }
        }

        if (sseClients.isNotEmpty()) broadcast(mapper.writeValueAsString(swarmSnapshot()))
        key.reset()
    }
}.also { it.isDaemon = true }.start()

// ── HTML Dashboard ────────────────────────────────────────────────────────────

val HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>IGLY CORTEX</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#0d1117;--panel:#161b22;--border:#30363d;--cyan:#79c0ff;--green:#56d364;--yellow:#e3b341;--red:#f85149;--purple:#d2a8ff;--text:#c9d1d9;--muted:#8b949e;--hover:#1f2937;--sel:#21262d}
body{background:var(--bg);color:var(--text);font-family:'Courier New',monospace;font-size:13px;height:100vh;display:flex;flex-direction:column;overflow:hidden}
/* Header */
header{display:flex;justify-content:space-between;align-items:center;padding:10px 20px;border-bottom:1px solid var(--border);background:var(--panel);flex-shrink:0}
header h1{color:var(--cyan);font-size:14px;letter-spacing:2px;font-weight:bold}
.hdr-right{display:flex;align-items:center;gap:16px}
#cortex-badge{font-size:11px;padding:2px 10px;border-radius:12px;background:#1a0a2e;color:var(--purple);border:1px solid #4a1a7e;display:none}
#cortex-badge.active{display:inline}
#conn{width:7px;height:7px;border-radius:50%;background:var(--green)}
#conn.off{background:var(--red)}
#clock{color:var(--muted);font-size:12px}
/* Metrics */
.metrics{display:flex;align-items:center;gap:24px;padding:8px 20px;border-bottom:1px solid var(--border);flex-shrink:0}
.metric{display:flex;flex-direction:column;align-items:center;min-width:50px}
.metric .lbl{color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:1px}
.metric .val{font-size:20px;font-weight:bold;margin-top:1px;font-variant-numeric:tabular-nums}
.val.p{color:var(--yellow)}.val.pr{color:var(--purple)}.val.d{color:var(--green)}.val.f{color:var(--red)}.val.a{color:var(--cyan)}.val.s{color:#6ee7b7}
.metrics-sep{width:1px;height:32px;background:var(--border);margin:0 4px}
/* Main layout */
.main{display:flex;flex:1;overflow:hidden}
/* Jobs panel */
.jobs{flex:1;display:flex;flex-direction:column;border-right:1px solid var(--border);overflow:hidden}
.panel-hdr{padding:8px 16px;color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:1px;border-bottom:1px solid var(--border);background:var(--bg);display:flex;align-items:center;justify-content:space-between;flex-shrink:0}
.filters{display:flex;gap:4px}
.filter-btn{padding:2px 10px;border-radius:10px;border:1px solid var(--border);background:transparent;color:var(--muted);font-size:11px;cursor:pointer;font-family:inherit}
.filter-btn.active{border-color:var(--cyan);color:var(--cyan);background:#0a1929}
.jobs-table{flex:1;overflow-y:auto}
table{width:100%;border-collapse:collapse}
th{text-align:left;padding:7px 16px;color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:1px;border-bottom:1px solid var(--border);background:var(--bg);position:sticky;top:0}
td{padding:7px 16px;border-bottom:1px solid #0d1117;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:200px}
tr:hover td{background:var(--hover)}
tr.sel td{background:var(--sel)}
.badge{padding:1px 7px;border-radius:3px;font-size:10px;font-weight:bold}
.badge.PROCESSING{background:#2d1b4e;color:var(--purple)}
.badge.PENDING{background:#2d2100;color:var(--yellow)}
.badge.DONE{background:#0d2b0d;color:var(--green)}
.badge.FAILED{background:#2b0d0d;color:var(--red)}
/* Log panel */
.log{width:380px;display:flex;flex-direction:column;border-right:1px solid var(--border)}
#log-body{flex:1;overflow-y:auto;padding:10px 14px;font-size:12px;line-height:1.7;white-space:pre-wrap;background:var(--panel)}
.l-w{color:var(--red)}.l-ok{color:var(--green)}.l-info{color:var(--purple)}.l-dim{color:#4b5563}
/* Sidebar */
.sidebar{width:260px;display:flex;flex-direction:column;overflow:hidden}
.side-section{border-bottom:1px solid var(--border)}
.side-section:last-child{flex:1;overflow:hidden;display:flex;flex-direction:column}
.side-items{overflow-y:auto;flex:1}
.side-item{padding:7px 14px;border-bottom:1px solid #0d1117;font-size:12px;cursor:default}
.side-item:hover{background:var(--hover)}
.side-item .name{color:var(--text)}
.side-item .desc{color:var(--muted);font-size:10px;margin-top:1px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.side-item .badge-s{font-size:9px;padding:1px 5px;border-radius:8px}
.cron-badge{background:#0a2a1a;color:var(--green)}.rate-badge{background:#1a1a0a;color:var(--yellow)}.once-badge{background:#0a1a2a;color:var(--cyan)}
/* CORTEX chat */
.chat-area{display:flex;flex-direction:column;flex:1;overflow:hidden}
#chat-log{flex:1;overflow-y:auto;padding:10px 14px;font-size:12px;line-height:1.6;background:var(--bg)}
.chat-input-row{display:flex;padding:8px;border-top:1px solid var(--border);background:var(--panel);gap:6px}
#chat-input{flex:1;background:var(--bg);border:1px solid var(--border);border-radius:4px;padding:6px 10px;color:var(--text);font-family:inherit;font-size:12px;outline:none}
#chat-input:focus{border-color:var(--cyan)}
#chat-send{padding:6px 12px;background:#0a1929;border:1px solid var(--cyan);border-radius:4px;color:var(--cyan);cursor:pointer;font-family:inherit;font-size:11px}
#chat-send:hover{background:#0f2640}
.msg-u{color:var(--cyan)}.msg-c{color:var(--text)}
.empty{padding:20px;color:#30363d;text-align:center;font-size:12px}
/* Pulse animation for PROCESSING */
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
.pulse{animation:pulse 1.5s infinite}
</style>
</head>
<body>
<header>
  <h1>◈ &nbsp;IGLY CORTEX — SWARM MONITOR</h1>
  <div class="hdr-right">
    <span id="cortex-badge">● CORTEX ONLINE</span>
    <span id="conn"></span>
    <span id="clock">--:--:--</span>
  </div>
</header>
<div class="metrics">
  <div class="metric"><span class="lbl">Pending</span><span class="val p" id="m-p">0</span></div>
  <div class="metric"><span class="lbl">Processing</span><span class="val pr" id="m-pr">0</span></div>
  <div class="metric"><span class="lbl">Failed</span><span class="val f" id="m-f">0</span></div>
  <div class="metrics-sep"></div>
  <div class="metric"><span class="lbl">Agents</span><span class="val a" id="m-a">0</span></div>
  <div class="metric"><span class="lbl">Schedules</span><span class="val s" id="m-s">0</span></div>
</div>
<div class="main">

  <!-- Jobs table -->
  <div class="jobs">
    <div class="panel-hdr">
      <span>Jobs</span>
      <div class="filters">
        <button class="filter-btn active" onclick="setFilter('all',this)">All</button>
        <button class="filter-btn" onclick="setFilter('active',this)">Active</button>
        <button class="filter-btn" onclick="setFilter('failed',this)">Failed</button>
      </div>
    </div>
    <div class="jobs-table">
      <table>
        <thead><tr><th>Job ID</th><th>Queue</th><th>Status</th><th>Time</th></tr></thead>
        <tbody id="jobs-tbody"><tr><td colspan="4" class="empty">— no jobs —</td></tr></tbody>
      </table>
    </div>
  </div>

  <!-- Log panel -->
  <div class="log">
    <div class="panel-hdr">
      <span id="log-title" style="color:var(--text)">Log</span>
      <span style="cursor:pointer;color:var(--muted)" onclick="refreshLog()" title="Refresh">↺</span>
    </div>
    <div id="log-body"><span class="empty" style="display:block;text-align:center;padding:30px">Select a job to view its log</span></div>
  </div>

  <!-- Sidebar -->
  <div class="sidebar">

    <!-- CORTEX chat -->
    <div class="side-section" style="flex:1;display:flex;flex-direction:column;overflow:hidden">
      <div class="panel-hdr">CORTEX</div>
      <div class="chat-area">
        <div id="chat-log"><span class="empty" style="display:block;padding:20px">CORTEX chat — messages appear in the TUI log panel</span></div>
        <div class="chat-input-row">
          <input id="chat-input" placeholder="Ask CORTEX..." onkeydown="if(event.key==='Enter')sendChat()">
          <button id="chat-send" onclick="sendChat()">Send</button>
        </div>
      </div>
    </div>

    <!-- Agent store -->
    <div class="side-section" style="max-height:35%">
      <div class="panel-hdr">Agent Store (<span id="a-count">0</span>)</div>
      <div class="side-items" id="agents-list"></div>
    </div>

    <!-- Schedules -->
    <div class="side-section" style="max-height:30%">
      <div class="panel-hdr">Schedules (<span id="s-count">0</span>)</div>
      <div class="side-items" id="sched-list"></div>
    </div>

  </div>
</div>

<script>
let selectedJob = null;
let jobFilter   = 'all';
let allJobs     = [];

// ── SSE ───────────────────────────────────────────────────────────────────────
const es = new EventSource('/events');
es.onopen  = () => document.getElementById('conn').className = '';
es.onerror = () => document.getElementById('conn').className = 'off';
es.onmessage = e => { const d = JSON.parse(e.data); if (d.type === 'snapshot') updateUI(d); };

// ── Clock ─────────────────────────────────────────────────────────────────────
setInterval(() => {
  const n = new Date();
  document.getElementById('clock').textContent =
    String(n.getHours()).padStart(2,'0')+':'+String(n.getMinutes()).padStart(2,'0')+':'+String(n.getSeconds()).padStart(2,'0');
}, 1000);

// ── UI update ─────────────────────────────────────────────────────────────────
function updateUI(d) {
  document.getElementById('m-p').textContent  = d.metrics.pending;
  document.getElementById('m-pr').textContent = d.metrics.processing;
  document.getElementById('m-f').textContent  = d.metrics.failed;
  document.getElementById('m-a').textContent  = d.agents.length;
  document.getElementById('m-s').textContent  = d.schedules.length;
  document.getElementById('a-count').textContent = d.agents.length;
  document.getElementById('s-count').textContent = d.schedules.length;

  const badge = document.getElementById('cortex-badge');
  badge.className = d.cortexActive ? 'active' : '';

  allJobs = d.jobs;
  renderJobs();
  renderAgents(d.agents);
  renderSchedules(d.schedules);
}

function setFilter(f, btn) {
  jobFilter = f;
  document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  renderJobs();
}

function renderJobs() {
  const jobs = allJobs.filter(j =>
    jobFilter === 'all'    ? true :
    jobFilter === 'active' ? (j.status === 'PROCESSING' || j.status === 'PENDING') :
    jobFilter === 'failed' ? j.status === 'FAILED' : true
  );
  const tbody = document.getElementById('jobs-tbody');
  if (!jobs.length) { tbody.innerHTML = '<tr><td colspan="4" class="empty">— no jobs —</td></tr>'; return; }
  tbody.innerHTML = jobs.map(j => {
    const sel   = j.id === selectedJob ? 'sel' : '';
    const pulse = j.status === 'PROCESSING' ? ' pulse' : '';
    return '<tr class="' + sel + '" onclick="selectJob(\'' + j.id.replace(/'/g,"\\'") + '\')">' +
      '<td title="' + j.id + '">' + j.id + '</td>' +
      '<td style="color:var(--muted)">' + j.queue + '</td>' +
      '<td><span class="badge' + pulse + ' ' + j.status + '">' + j.status + '</span></td>' +
      '<td style="color:var(--muted)">' + (j.time||'') + '</td>' +
      '</tr>';
  }).join('');
}

function renderAgents(agents) {
  const el = document.getElementById('agents-list');
  if (!agents.length) { el.innerHTML = '<div class="empty">No agents installed</div>'; return; }
  el.innerHTML = agents.map(a =>
    '<div class="side-item"><div class="name">' + a.name + '</div>' +
    (a.description ? '<div class="desc">' + a.description + '</div>' : '') +
    '</div>'
  ).join('');
}

function renderSchedules(scheds) {
  const el = document.getElementById('sched-list');
  if (!scheds.length) { el.innerHTML = '<div class="empty">No schedules — use koupper schedule add</div>'; return; }
  el.innerHTML = scheds.map(s => {
    const type = s.type || 'cron';
    const info = type === 'cron' ? s.cron : type === 'rate' ? 'every ' + Math.round((s.rateMs||0)/1000) + 's' : s.runAt || '';
    const cls  = type === 'cron' ? 'cron-badge' : type === 'rate' ? 'rate-badge' : 'once-badge';
    const dot  = s.enabled === false ? '○' : '●';
    const color= s.enabled === false ? 'var(--muted)' : 'var(--green)';
    return '<div class="side-item">' +
      '<div class="name"><span style="color:' + color + '">' + dot + '</span> ' + s.agent + '</div>' +
      '<div class="desc"><span class="badge-s ' + cls + '">' + type + '</span> ' + info + '</div>' +
      '</div>';
  }).join('');
}

// ── Log ───────────────────────────────────────────────────────────────────────
function selectJob(id) {
  selectedJob = id;
  document.getElementById('log-title').textContent = id;
  document.querySelectorAll('tbody tr').forEach(r => r.classList.toggle('sel', r.onclick && r.onclick.toString().includes(id)));
  refreshLog();
}

function refreshLog() {
  if (!selectedJob) return;
  fetch('/api/logs/' + selectedJob)
    .then(r => r.json())
    .then(d => {
      if (d.error || !d.lines.length) {
        document.getElementById('log-body').innerHTML = '<span class="empty" style="display:block;padding:20px">' + (d.error||'No log yet') + '</span>';
        return;
      }
      document.getElementById('log-body').innerHTML = d.lines.map(l => {
        const cls = l.includes('ERROR')||l.includes('FAIL')||l.includes('[!]') ? 'l-w' :
                    l.includes('DONE')||l.includes('[✓]')||l.includes('✓')    ? 'l-ok' :
                    l.includes('▶')||l.includes('[?]')||l.includes('CORTEX')  ? 'l-info' :
                    l.startsWith('[') && l.includes(']') ? 'l-dim' : '';
        return '<span class="' + cls + '">' + l.replace(/</g,'&lt;') + '</span>';
      }).join('\n');
      const el = document.getElementById('log-body');
      el.scrollTop = el.scrollHeight;
    }).catch(() => {});
}

setInterval(() => { if (selectedJob) refreshLog(); }, 2000);

// ── CORTEX chat ───────────────────────────────────────────────────────────────
function sendChat() {
  const input = document.getElementById('chat-input');
  const msg   = input.value.trim();
  if (!msg) return;
  input.value = '';

  const log = document.getElementById('chat-log');
  log.innerHTML += '<div class="msg-u">▶ ' + msg.replace(/</g,'&lt;') + '</div>';
  log.scrollTop = log.scrollHeight;

  fetch('/api/cortex', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({message: msg})
  }).then(r => r.json()).then(d => {
    if (d.ok) {
      log.innerHTML += '<div style="color:var(--muted);font-size:10px">→ sent to CORTEX. See log panel for response.</div>';
      // Auto-select cortex-session to show response
      selectJob('cortex-session');
    }
    log.scrollTop = log.scrollHeight;
  }).catch(() => {});
}
</script>
</body>
</html>"""

// ── Main ──────────────────────────────────────────────────────────────────────

@Export
val setup: () -> Unit = {
    startWatcher()

    val router = GrizzlyRuntimeRouterProvider()

    router.registerRouter {
        get<Unit> {
            path { "/" }
            script { { HTML } }
        }

        get<Unit> {
            path { "/api/swarm" }
            script { { mapper.writeValueAsString(swarmSnapshot()) } }
        }

        get<String> {
            path { "/api/logs/{jobId}" }
            script { {
                jobId: String ->
                val logFile = File(jobsDir, "logs").walkTopDown()
                    .firstOrNull { it.name == "$jobId.log" }
                if (logFile != null && logFile.exists())
                    mapper.writeValueAsString(mapOf("jobId" to jobId, "lines" to logFile.readLines().takeLast(300)))
                else
                    mapper.writeValueAsString(mapOf("jobId" to jobId, "lines" to emptyList<String>(), "error" to "log not found"))
            } }
        }

        // CORTEX chat endpoint — writes to commands/wizard/ so CortexAgent picks it up
        post<String> {
            path { "/api/cortex" }
            script { {
                body: String ->
                runCatching {
                    val payload = mapper.readValue<Map<String, String>>(body)
                    val msg     = payload["message"]?.trim() ?: ""
                    if (msg.isNotBlank()) {
                        val cmdDir = File(jobsDir, "commands/wizard").also { it.mkdirs() }
                        File(cmdDir, "${System.currentTimeMillis()}.response").writeText(msg)
                        mapper.writeValueAsString(mapOf("ok" to true))
                    } else {
                        mapper.writeValueAsString(mapOf("ok" to false, "error" to "empty message"))
                    }
                }.getOrElse { e -> mapper.writeValueAsString(mapOf("ok" to false, "error" to e.message)) }
            } }
        }

        get<Unit> {
            path { "/events" }
            script { {
                object : StreamResponse {
                    override fun onData(callback: (String) -> Unit) {
                        sseClients.add(callback)
                        try { callback(mapper.writeValueAsString(swarmSnapshot())) } catch (_: Exception) {}
                    }
                    override fun onClose(callback: () -> Unit) {}
                }
            } }
        }
    }

    router.start(uiPort)
    println("◈ CORTEX Web UI → http://localhost:$uiPort")
    println("  Press Ctrl+C to stop.")
    Thread.currentThread().join()
}
