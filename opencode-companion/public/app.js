// opencode companion — minimalista Claude/ChatGPT
// drawer + pillow + composer limpio + SSE bidireccional + persistencia backend
// + multimodal (+ chips) + voz dúplex
const $ = s => document.querySelector(s);
const msgsEl = $("#msgs"), promptEl = $("#prompt");
const projectsList = $("#projects-list"), standaloneList = $("#standalone-list");
const agentSel = $("#agent-select");
const hubStatusEl = $("#hub-status"), ocStatusEl = $("#oc-status"), dotHub = $("#dot-hub"), dotOc = $("#dot-oc");
const sysInfo = $("#sys-info"), hubFoot = $("#hub-pill-foot");
const statusPill = $("#status-pill"), statusPillText = $("#status-pill-text");
const drawer = $("#drawer"), drawerBackdrop = $("#drawer-backdrop"), btnHamburger = $("#btn-hamburger");
const filePicker = $("#file-picker"), chipsEl = $("#chips");
const projectsCountEl = $("#projects-count");
const voiceModeToggle = $("#voice-mode");
const btnMic = $("#btn-mic"), btnSend = $("#btn-send"), btnAttach = $("#btn-attach");

let state = {
  sessions: [], projects: [],
  currentSessionId: null,
  currentProject: null, // legacy folder name
  currentProjectId: null, // companion managed id (spec 5)
  agents: [], voices: [],
  lastAssistantText: "",
  abortCtrl: null,
  listening: false, recognition: null,
  attachedFiles: [], // {name,type,size,kind,ext,b64,text,previewUrl}
  voiceMode: "push", // default OFF (Texto) — duplex only after explicit toggle (fix 1)
};

// ---- helpers
function el(tag, cls, text){ const e=document.createElement(tag); if(cls) e.className=cls; if(text!==undefined) e.textContent=text; return e; }
function addMsg(role, text, meta=""){
  const w = el("div","msg "+role); w.textContent = text;
  msgsEl.appendChild(w);
  if(meta){ const m=el("div","muted",meta); m.style.fontSize="11px"; m.style.alignSelf = role==="user" ? "flex-end" : "flex-start"; msgsEl.appendChild(m); }
  msgsEl.scrollTop = msgsEl.scrollHeight;
  if(role==="assistant"){ state.lastAssistantText = text; queueTts(text); }
}
function setDot(elDot, ok){ if(!elDot) return; elDot.className = "dot "+(ok?"ok":"bad"); }
async function jget(url){ const r=await fetch(url); if(!r.ok) throw new Error(url+" -> "+r.status+" "+await r.text().catch(()=> "")); return r.json(); }
async function jpost(url, body){
  const r=await fetch(url,{method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify(body)});
  if(!r.ok) throw new Error(url+" -> "+r.status+" "+await r.text().catch(()=> "")); const ct=r.headers.get("content-type")||""; return ct.includes("json") ? r.json() : r.text();
}
async function jpatch(url, body){
  const r=await fetch(url,{method:"PATCH", headers:{"Content-Type":"application/json"}, body: JSON.stringify(body)});
  if(!r.ok) throw new Error(url+" -> "+r.status+" "+await r.text().catch(()=> "")); return r.json();
}
function persistUi(patch){
  // Spec 5: persist active projectId (companion managed) + sessionId + legacy project folder name in ui-state.json
  if("project" in patch){ state.currentProject = patch.project; localStorage.setItem("occ.project", patch.project || ""); }
  if("projectId" in patch){ state.currentProjectId = patch.projectId; if(patch.projectId) localStorage.setItem("occ.projectId", patch.projectId); else localStorage.removeItem("occ.projectId"); }
  if("sessionId" in patch){ state.currentSessionId = patch.sessionId; if(patch.sessionId) localStorage.setItem("occ.sessionId", patch.sessionId); else localStorage.removeItem("occ.sessionId"); }
  jpatch("/api/ui/state", patch).catch(()=>{});
}
// Extract data from {ok:true,data:...} envelope or legacy bare payload
function unwrap(resp){ if(resp && typeof resp === "object" && "ok" in resp){ if(resp.ok) return resp.data; throw new Error(resp.error || "request failed"); } return resp; }
async function jgetOk(url){ const r = await fetch(url); if(!r.ok) throw new Error(url+" -> "+r.status+" "+await r.text().catch(()=> "")); const j = await r.json(); return unwrap(j); }
function humanSize(b){
  if(b<1024) return b+" B";
  if(b<1024*1024) return (b/1024).toFixed(1)+" KB";
  return (b/1024/1024).toFixed(1)+" MB";
}
function mimeGuess(ext){
  const m={jpg:"image/jpeg",jpeg:"image/jpeg",png:"image/png",webp:"image/webp",gif:"image/gif",mp4:"video/mp4",mov:"video/mp4",webm:"video/webm",wav:"audio/wav",mp3:"audio/mpeg",pdf:"application/pdf",docx:"application/vnd.openxmlformats-officedocument.wordprocessingml.document",txt:"text/plain",md:"text/markdown",json:"application/json"};
  return m[ext]||"application/octet-stream";
}
function getFileKind(file){
  const mime=(file.type||"").toLowerCase();
  const name=file.name||"";
  const ext=name.split(".").pop().toLowerCase();
  if(mime.startsWith("image/")) return {kind:"image", ext, mime};
  if(mime.startsWith("video/")) return {kind:"video", ext, mime};
  if(mime.startsWith("audio/")) return {kind:"audio", ext, mime};
  const map={jpg:"image",jpeg:"image",png:"image",webp:"image",gif:"image",bmp:"image",svg:"image",mp4:"video",mov:"video",webm:"video",avi:"video",mkv:"video",wav:"audio",mp3:"audio",ogg:"audio",m4a:"audio",flac:"audio",opus:"audio",pdf:"pdf",docx:"docx",doc:"docx",txt:"text",md:"text",log:"text",csv:"text",ini:"text",toml:"text",yaml:"text",yml:"text",xml:"text",json:"text",cmd:"exec",bat:"exec",sh:"exec",bash:"exec",ps1:"exec",py:"code",js:"code",mjs:"code",cjs:"code",ts:"code",jsx:"code",tsx:"code",html:"code",css:"code",c:"code",cpp:"code",h:"code",hpp:"code",java:"code",go:"code",rs:"code",rb:"code",php:"code",pl:"code",lua:"code",swift:"code",kt:"code",dart:"code"};
  const k=map[ext]||"unknown";
  return {kind:k, ext, mime: mime || mimeGuess(ext)};
}
function fileToBase64(file){
  return new Promise((res,rej)=>{
    const r=new FileReader();
    r.onload=()=>{ try{ const s=String(r.result||""); const b64=s.split(",")[1]||""; res(b64);}catch(e){rej(e);} };
    r.onerror=()=> rej(r.error|| new Error("read error"));
    r.readAsDataURL(file);
  });
}
function fileToText(file){
  return new Promise((res,rej)=>{
    const r=new FileReader();
    r.onload=()=> res(String(r.result||""));
    r.onerror=()=> rej(r.error|| new Error("read error"));
    r.readAsText(file);
  });
}

// ---- drawer
function setupDrawer(){
  const toggle = (id) => {
    const body = document.getElementById(id);
    const btn = document.querySelector(`.section-toggle[data-target="${id}"]`);
    if(!body || !btn) return;
    const collapsed = body.classList.contains("collapsed");
    if(collapsed){ body.classList.remove("collapsed"); btn.setAttribute("aria-expanded","true"); }
    else { body.classList.add("collapsed"); btn.setAttribute("aria-expanded","false"); }
  };
  document.querySelectorAll(".section-toggle").forEach(b=>{
    b.addEventListener("click", ()=> toggle(b.dataset.target));
  });
  const setOpen = (open) => {
    if(!drawer || !drawerBackdrop) return;
    drawer.classList.toggle("open", open);
    drawerBackdrop.classList.toggle("open", open);
    btnHamburger?.setAttribute("aria-expanded", String(open));
  };
  btnHamburger?.addEventListener("click", ()=>{
    const open = !drawer.classList.contains("open");
    setOpen(open);
  });
  drawerBackdrop?.addEventListener("click", ()=> setOpen(false));
  document.addEventListener("keydown", e=>{ if(e.key==="Escape") setOpen(false); });
  return { setOpen, toggle };
}
const drawerCtl = setupDrawer();

// ---- status + pill (SSE en tiempo real, bidireccional Wizard)
let pillTimer = null;
function showPill(text, kind="thinking"){
  if(!statusPill || !statusPillText) return;
  statusPillText.textContent = text;
  statusPill.classList.remove("hidden");
  const dot = statusPill.querySelector(".pill-dot");
  if(dot){ dot.className = "pill-dot " + (kind || "thinking"); }
  clearTimeout(pillTimer);
  pillTimer = setTimeout(hidePill, 4200);
}
function hidePill(){ statusPill?.classList.add("hidden"); }
function pillFromSseEvent(d){
  const t = (d.type || d.event || "").toLowerCase();
  const tool = (d.tool || d.name || d.command || "").toString().slice(0,80);
  const file = (d.file || d.path || "").toString().split("/").slice(-1)[0];
  if(t.includes("thinking") || t.includes("reason")) return ["Pensando…", "thinking"];
  if(t.includes("read") || t.includes("file") || file) return [file ? `Leyendo ${file}` : "Leyendo archivos…", "read"];
  if(t.includes("bash") || t.includes("tool") || tool) return [tool ? `Ejecutando ${tool}` : "Ejecutando…", "bash"];
  if(t.includes("write") || t.includes("edit")) return ["Escribiendo…", "bash"];
  if(d.text && String(d.text).length > 0) return [String(d.text).slice(0,64), "thinking"];
  return [t || "Trabajando…", "thinking"];
}
let sseRetry = 0;
function setupSSE(){
  let es = null;
  const connect = () => {
    try{
      es = new EventSource("/opencode/event");
      es.onopen = ()=> { sseRetry = 0; showPill("Conectado", "read"); setTimeout(hidePill, 900); };
      es.onmessage = e=>{
        try{
          const d = JSON.parse(e.data);
          const [text, kind] = pillFromSseEvent(d);
          showPill(text, kind);
          if(hubStatusEl && d.sessionId) hubStatusEl.textContent = "wizard · " + (text.slice(0,18));
        }catch{}
      };
      es.addEventListener("tool", e=>{
        try{ const d=JSON.parse(e.data); const [t,k]=pillFromSseEvent({type:"tool", ...d}); showPill(t,k);}catch{}
      });
      es.onerror = ()=> {
        try{ es.close(); }catch{}
        sseRetry = Math.min(sseRetry+1, 6);
        const backoff = 1200 * Math.pow(1.6, sseRetry);
        showPill(`Reconectando…`, "thinking");
        setTimeout(connect, backoff);
      };
    }catch(e){ showPill("SSE no disponible", "thinking"); }
  };
  connect();
  window.__companionShowPill = showPill;
}

// ---- status bar + sessionOwnership pill (spec 5)
// Ownership colors: green companion-owned, blue termux-native, red none
// Reads /api/system/status sessionOwnership and renders pill with distinct dot/background.
let lastOwnership = null;
function ownershipMeta(ownership){
  // Maps ownership string to display metadata for pill coloring
  switch(ownership){
    case "companion-owned": return { label: "companion", dot: "own-companion", pillClass: "pill-ownership companion", color: "#22c55e", desc: "companion-owned (we launched)" };
    case "termux-native":   return { label: "termux",     dot: "own-termux",     pillClass: "pill-ownership termux",     color: "#3b82f6", desc: "termux-native (existing, not touched)" };
    case "none":            return { label: "none",       dot: "own-none",       pillClass: "pill-ownership none",       color: "#ef4444", desc: "no serve running" };
    default:                return { label: ownership || "unknown", dot: "own-unknown", pillClass: "pill-ownership unknown", color: "#8e8ea0", desc: ownership || "unknown" };
  }
}
function renderOwnership(ownership, sessionInfo){
  // Updates header pills with ownership badge — called from refreshStatus
  const meta = ownershipMeta(ownership);
  const pidHint = sessionInfo && sessionInfo.pid ? ` pid ${sessionInfo.pid}` : "";
  // Use ocStatus pill for ownership display when available — append ownership label
  // Keep dot color in sync: orig dotOc stays green if oc healthy, but ownership adds secondary hint
  // We also update statusPill when ownership is known for prominent feedback
  lastOwnership = ownership;
  return meta;
}
async function refreshStatus(){
  try{
    // Prefer /api/system/status for ownership (spec), fallback to /api/status legacy
    let s = null, ownership = null, sessionInfo = null;
    let lastErr = null;
    console.log("[refreshStatus] fetching /api/system/status");
    try {
      const sys = await jget("/api/system/status");
      console.log(`[refreshStatus] /api/system/status ready=${sys.ready} ownership=${sys.sessionOwnership}`);
      // Derive hub_ok from sys: ready implies hub is ok; sys also has hub+opencode fields
      ownership = sys.sessionOwnership || null;
      sessionInfo = sys.sessionInfo || null;
      // Normalize to the shape refreshStatus expects for legacy rendering
      s = {
        hub: sys.ready ? "ok" : (sys.opencode && sys.opencode.up ? "ok" : "ok"), // hub itself is always ok if this endpoint responded
        hub_port: 8765,
        opencode: sys.opencode,
        projects: null, // will be fetched separately if needed
        root: sys.hint || "",
        // carry ownership for extra rendering below
        _ownership: ownership,
        _sessionInfo: sessionInfo,
        _ready: sys.ready,
        _bridgeA11y: sys.bridge && sys.bridge.a11y
      };
    } catch (e) { lastErr = e; try{ console.error(`[refreshStatus] /api/system/status fail: ${String(e).slice(0,300)}`);}catch{} }
    // Fallback to legacy /api/status if new endpoint not yet available
    if (!s) {
      try { s = await jget("/api/status"); }
      catch (e) { lastErr = e; throw e; }
    }
    // Also fetch legacy projects count if missing (sys status doesn't include projects list)
    if (!s.projects) {
      try { const full = await jget("/api/status"); s.projects = full.projects; s.hub_port = full.hub_port || s.hub_port; } catch (e) { lastErr = e; }
    }
    const oc = s.opencode;
    const hubOk = s.hub==="ok";
    const ocOk = oc && (oc.healthy || oc.version);
    if(hubStatusEl) hubStatusEl.textContent = hubOk ? `hub :${s.hub_port}` : "hub off";
    // Ownership-aware oc status: show "termux:8765 pid 123" style with color dot
    const meta = renderOwnership(ownership || s._ownership || null, sessionInfo || s._sessionInfo || null);
    if(ocStatusEl){
      if(ownership || s._ownership){
        // Display ownership label with dot color matching spec (5)
        ocStatusEl.textContent = ocOk ? `${meta.label}${sessionInfo && sessionInfo.pid ? ` pid ${sessionInfo.pid}` : (s._sessionInfo && s._sessionInfo.pid ? ` pid ${s._sessionInfo.pid}` : "")}` : "off";
        // Set dot to ownership color rather than healthy green — spec requires distinct color per ownership
        if(dotOc){
          // Use ownership-specific class: own-companion (green), own-termux (blue), own-none (red)
          dotOc.className = "dot " + meta.dot;
        }
      } else {
        if(ocStatusEl) ocStatusEl.textContent = ocOk ? (oc.version ? `v${oc.version}` : "ok") : "off";
        setDot(dotOc, !!ocOk);
      }
    } else {
      setDot(dotOc, !!ocOk);
    }
    setDot(dotHub, hubOk);
    if(s.projects && projectsCountEl) projectsCountEl.textContent = String(s.projects.length);
    if(sysInfo){
      const ownLine = (ownership || s._ownership) ? `ownership: ${(ownership||s._ownership)}${(sessionInfo && sessionInfo.pid) ? ` pid ${sessionInfo.pid}` : ""} · ${meta ? meta.desc : ""}` : "";
      sysInfo.textContent = `hub :${s.hub_port} · opencode ${ocOk ? "OK" : "—"} ${oc?.version||""}${ownLine ? `\n${ownLine}` : ""}\n${(s.root||"").slice(0,280)}`;
    }
    if(hubFoot) hubFoot.textContent = hubOk ? `:${s.hub_port}` : "off";
    // Also update floating statusPill with ownership for visibility on first load (distinct color per spec)
    if((ownership || s._ownership) && statusPill && statusPillText){
      const m = ownershipMeta(ownership || s._ownership);
      // Only show ownership pill briefly on refresh if ownership changed or is notable
      if(ownership === "termux-native" || ownership === "companion-owned" || ownership === "none"){
        // Respect existing pill if showing thinking/bash; otherwise show ownership hint
        const curText = statusPillText.textContent || "";
        if(!curText.includes("Pensando") && !curText.includes("Ejecutando") && !curText.includes("Leyendo")){
          statusPillText.textContent = `${m.label}${(sessionInfo && sessionInfo.pid) ? ` · pid ${sessionInfo.pid}` : ""} · ${ocOk ? "ready" : "not ready"}`;
          statusPill.classList.remove("hidden");
          statusPill.className = "status-pill " + (m.label === "companion" ? "own-companion" : m.label === "termux" ? "own-termux" : m.label === "none" ? "own-none" : "");
          const dot = statusPill.querySelector(".pill-dot");
          if(dot) dot.className = "pill-dot " + (m.label === "companion" ? "own-companion" : m.label === "termux" ? "own-termux" : m.label === "none" ? "own-none" : "thinking");
        }
      }
    }
    return ocOk;
  }catch(e){
    const msg = String(e).slice(0,400);
    if(hubStatusEl) hubStatusEl.textContent="err"; if(ocStatusEl) ocStatusEl.textContent="err";
    setDot(dotHub,false); setDot(dotOc,false);
    if(sysInfo) sysInfo.textContent = `hub no responde: ${msg}`;
    showToast(`SISTEMA: ${msg}`, "error");
    return false;
  }
}

// ---- projects + sessions — companion managed (projects.json) + opencode sessions ----
let projectExpanded = new Set(JSON.parse(localStorage.getItem("occ.expandedProjects") || "[]"));
function persistExpanded(){ localStorage.setItem("occ.expandedProjects", JSON.stringify([...projectExpanded])); }
// Fetch companion managed projects — unwraps {ok:true,data:[]} envelope and falls back to bare array
async function fetchManagedProjects(){
  try { return await jgetOk("/api/projects"); } catch { return []; }
}
function fmtDate(d){
  try { const dt = new Date(d); if(isNaN(dt.getTime())) return String(d).slice(0,10); return dt.toLocaleDateString() + " " + dt.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'}); } catch { return String(d).slice(0,16); }
}
// Toast system for visible errors (fix 5) + console forwarding for logcat (diagnostic)
try {
  const _origError = console.error;
  console.error = function(...args) { try { _origError.apply(console, args); } catch{}; /* WebChromeClient forwards to logcat */ };
  const _origLog = console.log;
  console.log = function(...args) { try { _origLog.apply(console, args); } catch{}; };
} catch {}
function showToast(msg, kind="error") {
  const t = el("div", "toast " + kind);
  t.textContent = msg;
  t.style.cssText = "position:fixed;right:12px;bottom:16px;max-width:88vw;background:var(--panel2);border:1px solid var(--border);border-radius:12px;padding:10px 12px;box-shadow:var(--shadow);font-size:12px;z-index:9999;white-space:pre-wrap;word-break:break-word;";
  if (kind === "error") t.style.borderColor = "rgba(239,68,68,.6)";
  document.body.appendChild(t);
  setTimeout(()=> t.remove(), 5200);
  // Also pill + logcat
  try { console.error(`[toast:${kind}] ${msg.slice(0,300)}`); } catch {}
  showPill(msg.slice(0,42), kind === "error" ? "thinking" : "read");
}

async function refreshProjects(){
  try{
    console.log("[refreshProjects] fetching /api/projects");
    const list = await fetchManagedProjects();
    console.log(`[refreshProjects] -> ${Array.isArray(list) ? list.length : 'non-array'}`);
    state.projects = Array.isArray(list) ? list : [];
    // Warm SISTEMA on success so drawer doesn't stay "cargando"
    renderDrawer();
  }catch(e){
    const err = String(e).slice(0,400);
    try { console.error(`[refreshProjects] error: ${err}`); } catch {}
    if(projectsList) projectsList.innerHTML = `<div class="muted" style="padding:8px;color:var(--err)">Error proyectos: ${err.slice(0,200)}</div>`;
    if(sysInfo && sysInfo.textContent.includes("cargando")) sysInfo.textContent = `Error proyectos: ${err}`;
    showToast(`Proyectos: ${err}`, "error");
  }
}
async function refreshSessions(){
  // Primary: companion /api/opencode/sessions (live opencode proxy, fix 4), fallback to legacy /opencode/session
  const endpoints = ["/api/opencode/sessions", "/opencode/session"];
  for (const ep of endpoints) {
    try{
      console.log(`[refreshSessions] fetching ${ep}`);
      const data = await jget(ep);
      const list = Array.isArray(data) ? data : (data.sessions || data.data || []);
      try { console.log(`[refreshSessions] ${ep} -> ${Array.isArray(list) ? list.length : 'non-array'}`); } catch {}
      if (Array.isArray(list) && list.length > 0 || ep === endpoints[endpoints.length-1]) {
        list.sort((a,b)=> new Date(b.updatedAt||b.updated_at||b.createdAt||0) - new Date(a.updatedAt||a.updated_at||a.createdAt||0));
        state.sessions = list;
        renderDrawer();
        return;
      }
      // If empty, try next endpoint (covers empty companion list but live has data)
      if (list.length === 0) continue;
    }catch(e){
      try { console.error(`[refreshSessions] ${ep} error: ${String(e).slice(0,300)}`); } catch {}
      if (ep === endpoints[endpoints.length-1]) {
        if(standaloneList) standaloneList.innerHTML = `<div class="muted" style="padding:8px;color:var(--err)">Error sesiones: ${String(e).slice(0,200)}</div>`;
        showToast(`Sesiones: ${String(e).slice(0,200)}`, "error");
      }
      // try next
    }
  }
  // If both returned empty, render empty state
  if (!state.sessions || state.sessions.length === 0) {
    state.sessions = [];
    renderDrawer();
  }
}
// Sessions for a managed project — resolve via project's sessions array (spec 1: sessions association)
// Fallback: if project has no managed sessions, use loose title heuristic for ungrouped display
function sessionsForManagedProject(proj){
  const ids = new Set((proj.sessions || []).map(s => String(s.sessionId)));
  const mapped = (proj.sessions || []).map(entry => {
    // Enrich with live opencode session data if present (title etc.), otherwise use stored entry
    const live = state.sessions.find(l => String(l.id||l.ID||l.sessionID||l.sessionId) === String(entry.sessionId));
    if (live) return { _entry: entry, live };
    return { _entry: entry, live: null };
  });
  return mapped;
}
function renderDrawer(){
  if(!projectsList || !standaloneList) return;
  projectsList.innerHTML = "";
  const usedIds = new Set((state.projects || []).flatMap(p => (p.sessions||[]).map(s => String(s.sessionId))).filter(Boolean));
  // Also mark live sessions that belong to managed projects as used so standalone stays clean
  state.projects.forEach(proj=>{
    const group = el("div","project-group");
    const expKey = proj.id || proj.name;
    const isExpanded = projectExpanded.has(expKey);
    // Header: managed project name + description + session count + archive indicator
    const header = el("button","project-row");
    const count = (proj.sessions || []).length;
    const archivedHint = proj.archivedAt ? " · archivado" : "";
    header.innerHTML = `<span style="flex:1; min-width:0"><span class="project-name">${proj.name}</span><br><span class="project-meta">${proj.description ? proj.description.slice(0,48) : ""}${count ? ` · ${count} sesión${count!==1?"es":""}` : " · sin sesiones"}${archivedHint}</span></span><span class="chevron" style="transform: rotate(${isExpanded?180:0}deg)">⌃</span>`;
    const isActive = state.currentProjectId ? (state.currentProjectId === proj.id) : (state.currentProject === proj.name);
    header.classList.toggle("active", !!isActive);
    header.setAttribute("aria-expanded", String(isExpanded));
    header.onclick = ()=>{
      // Spec 5: persist active projectId
      state.currentProjectId = proj.id;
      state.currentProject = proj.name; // legacy compat
      persistUi({ projectId: proj.id, project: proj.name });
      [...projectsList.querySelectorAll(".project-row")].forEach(n=> n.classList.toggle("active", n===header));
      if(projectExpanded.has(expKey)) projectExpanded.delete(expKey); else projectExpanded.add(expKey);
      persistExpanded();
      renderDrawer();
      refreshSkillsUI();
      showPill(`Proyecto: ${proj.name}`, "read");
    };
    // Right-click to archive/unarchive quick action
    header.oncontextmenu = async (e)=>{
      e.preventDefault();
      const act = proj.archivedAt ? "restaurar" : "archivar";
      if(confirm(`${act} proyecto "${proj.name}"?`)){
        try{
          if(proj.archivedAt) await jpost(`/api/projects/${encodeURIComponent(proj.id)}`, {}) && await fetch(`/api/projects/${encodeURIComponent(proj.id)}`, {method:"PATCH", headers:{"Content-Type":"application/json"}, body: JSON.stringify({archived:false})}).then(r=>r.json());
          else await fetch(`/api/projects/${encodeURIComponent(proj.id)}`, {method:"DELETE"}).then(r=>r.json());
          await refreshProjects();
        }catch(err){ alert(String(err).slice(0,400)); }
      }
    };
    group.appendChild(header);
    const list = document.createElement("div");
    list.style.display = isExpanded ? "grid" : "none";
    list.style.gap = "6px";
    const entries = sessionsForManagedProject(proj).slice(0, 30);
    if (entries.length === 0) {
      const empty = el("div","muted", "sin sesiones asociadas");
      empty.style.padding="4px 8px"; empty.style.fontSize="12px";
      list.appendChild(empty);
    } else {
      entries.forEach(({_entry, live})=>{
        // Use stored title/lastUsed but prefer live title if richer
        const title = (live && (live.title || live.name)) ? (live.title||live.name) : (_entry.title || _entry.sessionId.slice(0,8));
        const lastUsed = _entry.lastUsed || _entry.createdAt || (live && (live.updatedAt || live.createdAt)) || "";
        const s = { id: _entry.sessionId, title, lastUsed, _entry, live };
        list.appendChild(managedSessionRowEl(s, proj));
      });
    }
    group.appendChild(list);
    projectsList.appendChild(group);
  });
  if(projectsCountEl) projectsCountEl.textContent = String(state.projects.length);
  // Standalone: live sessions not yet associated to any managed project
  standaloneList.innerHTML = "";
  const free = state.sessions.filter(s=>{
    const id = String(s.id||s.ID||s.sessionID||s.sessionId);
    return !usedIds.has(id);
  }).slice(0, 60);
  free.forEach(s=> standaloneList.appendChild(sessionRowEl(s)));
  if(free.length===0){
    standaloneList.innerHTML = `<div class="muted" style="padding:8px; font-size:12px">sin chats sueltos</div>`;
  } else {
    // Hint: drag association is via New Session flow; standalone sessions can be linked via context menu in future
    const hint = el("div","muted", `${free.length} sueltos — crea sesión dentro de un proyecto o asocia uno existente vía API`);
    hint.style.padding="6px 8px"; hint.style.fontSize="11px";
    standaloneList.appendChild(hint);
  }
  document.querySelectorAll(".session-row").forEach(r=>{
    r.classList.toggle("active", r.dataset.sessionId===state.currentSessionId);
  });
}
// Row for managed project session — shows title + lastUsed and loads into composer on click (spec 3)
function managedSessionRowEl(s, proj){
  const id = s.id;
  const row = el("button","session-row");
  row.dataset.sessionId = id;
  row.title = id + (s._entry && s._entry.summary ? "\n"+s._entry.summary : "");
  row.dataset.hasHandler = "1";
  const when = s.lastUsed ? fmtDate(s.lastUsed) : "";
  row.innerHTML = `<span class="session-title">${(s.title||id.slice(0,8))}</span><span class="session-sub">${when || new Date(s._entry && s._entry.createdAt || Date.now()).toLocaleDateString()}</span>`;
  if(state.currentSessionId===id) row.classList.add("active");
  row.onclick = ()=> {
    // Spec 3: clicking loads session into composer (selectSession handles project context)
    // Also persist active projectId/sessionId (spec 5)
    persistUi({ projectId: proj.id, sessionId: id, project: proj.name });
    selectSession(id);
    drawerCtl?.setOpen?.(false);
  };
  row.oncontextmenu = async (e)=>{
    e.preventDefault();
    if(confirm(`Desasociar sesión "${s.title||id.slice(0,8)}" de "${proj.name}"?`)){
      try{
        const r = await fetch(`/api/projects/${encodeURIComponent(proj.id)}/sessions/${encodeURIComponent(id)}`, {method:"DELETE"});
        const j = await r.json();
        if(!j.ok) throw new Error(j.error || r.status);
        // Touch lastUsed locally then re-render
        await refreshProjects();
        if(state.currentSessionId===id){
          persistUi({ sessionId: null });
          msgsEl.innerHTML = "";
          addMsg("system", "Sesión desasociada.");
        }
      }catch(err){ alert(String(err).slice(0,400)); }
    }
  };
  return row;
}
function sessionRowEl(s){
  const id = s.id || s.ID || s.sessionID || s.sessionId;
  const title = s.title || s.name || id.slice(0,8);
  const row = el("button","session-row");
  row.dataset.sessionId = id;
  row.title = id;
  row.dataset.hasHandler = "1";
  // Standalone session: show lastUsed if present, else model/date
  const last = s.updatedAt || s.updated_at || s.lastUsed || s.createdAt;
  row.innerHTML = `<span class="session-title">${title}</span><span class="session-sub">${(s.model?.id||s.model||"").toString().slice(0,10) || (last ? fmtDate(last) : new Date(s.createdAt||Date.now()).toLocaleDateString())}</span>`;
  if(state.currentSessionId===id) row.classList.add("active");
  row.onclick = ()=> {
    // Load into composer (spec 3): standalone session loads without project context, but remembers sessionId
    persistUi({ sessionId: id });
    selectSession(id);
    drawerCtl?.setOpen?.(false);
  };
  row.oncontextmenu = async (e)=>{ e.preventDefault(); if(confirm(`Borrar sesión ${title}?`)){ try{ await fetch(`/opencode/session/${id}`,{method:"DELETE"}); await refreshSessions(); }catch(err){ alert(String(err).slice(0,400)); } } };
  return row;
}

// ---- sessions load + select (persistencia backend) — also touches managed lastUsed ----
async function selectSession(id){
  state.currentSessionId = id;
  persistUi({ sessionId: id });
  document.querySelectorAll(".session-row").forEach(r=> r.classList.toggle("active", r.dataset.sessionId===id));
  msgsEl.innerHTML="";
  addMsg("system", `Sesión ${id.slice(0,8)} — cargando…`);
  showPill("Cargando sesión…", "read");
  try{
    const data = await jget(`/opencode/session/${id}/message`);
    const items = Array.isArray(data) ? data : (data.messages || data.data || []);
    msgsEl.innerHTML="";
    if(!items.length) addMsg("system","Sesión vacía — escribe abajo.");
    items.forEach(m=>{
      const info = m.info || m.message || m;
      const parts = m.parts || m.content || [];
      const role = info.role || info.type || "assistant";
      let text="";
      if(typeof parts==="string") text=parts;
      else if(Array.isArray(parts)) text = parts.map(p=> p.text || p.content || p.value || (p.type==="text"? p.text:"") || JSON.stringify(p).slice(0,600)).join("\n");
      else if(parts?.text) text=parts.text;
      else text = info.content || info.text || JSON.stringify(m).slice(0,700);
      if(!String(text).trim()) return;
      const r = role==="user"||role==="human" ? "user" : (role==="system"?"system":"assistant");
      addMsg(r, String(text).slice(0,8000));
    });
    hidePill();
    // Touch lastUsed for managed project association if any
    if(state.currentProjectId){
      const proj = state.projects.find(p=>p.id===state.currentProjectId);
      if(proj && (proj.sessions||[]).some(s=>String(s.sessionId)===String(id))){
        // Fire-and-forget patch to update lastUsed
        fetch(`/api/projects/${encodeURIComponent(state.currentProjectId)}/sessions`, {headers:{}}).catch(()=>{});
        // We'll update via direct dot: patch project to bump lastUsed for this session
        try {
          const store = proj.sessions.find(s=>String(s.sessionId)===String(id));
          if(store){
            // Use PATCH on project to normalize? Easier: re-associate with updated lastUsed via delete+post is heavy — just update local
            store.lastUsed = new Date().toISOString();
          }
        } catch {}
      }
    }
  }catch(e){
    addMsg("system","No se pudo cargar historial: "+String(e).slice(0,500));
    showPill("Error cargando sesión", "thinking");
  }
}
async function createSession(){
  try{
    showPill("Creando chat…", "thinking");
    const title = state.currentProject ? `companion:${state.currentProject}:${Date.now()%100000}` : `companion:${new Date().toISOString().slice(0,16)}`;
    const s = await jpost("/opencode/session", { title });
    const id = s.id || s.ID || s.sessionId || s.sessionID;
    await refreshSessions();
    if(id){
      await selectSession(id);
      // If an active managed project is selected, associate the new session to it (spec 2: POST /:id/sessions)
      if(state.currentProjectId && id){
        try {
          await jpost(`/api/projects/${encodeURIComponent(state.currentProjectId)}/sessions`, { sessionId: id, title: title });
          await refreshProjects();
        } catch {}
      }
    }
    hidePill();
    return id;
  }catch(e){ addMsg("system","Error creando sesión: "+String(e).slice(0,600)); showPill("Error creando chat", "thinking"); return null; }
}

// New Project inline form — spec 4
function setupNewProjectForm(){
  const btnNew = $("#btn-new-project"), form = $("#new-project-form");
  const nameEl = $("#new-project-name"), descEl = $("#new-project-desc");
  const btnCreate = $("#btn-create-project"), btnCancel = $("#btn-cancel-project");
  const errEl = $("#new-project-error");
  if(!btnNew || !form) return;
  function openForm(){
    form.classList.remove("hidden"); form.style.display = "grid";
    btnNew.classList.add("hidden");
    nameEl.value = ""; descEl.value = ""; if(errEl) errEl.textContent = "";
    setTimeout(()=> nameEl.focus(), 50);
  }
  function closeForm(){
    form.classList.add("hidden"); form.style.display = "none";
    btnNew.classList.remove("hidden");
    if(errEl) errEl.textContent = "";
  }
  btnNew.addEventListener("click", openForm);
  btnCancel?.addEventListener("click", closeForm);
  // Escape closes
  form.addEventListener("keydown", (e)=>{ if(e.key==="Escape") closeForm(); });
  btnCreate?.addEventListener("click", async ()=>{
    const name = (nameEl.value || "").trim();
    const description = (descEl.value || "").trim();
    if(!name){ if(errEl) errEl.textContent = "Nombre requerido"; nameEl.focus(); return; }
    btnCreate.disabled = true;
    if(errEl) errEl.textContent = "Creando…";
    try {
      const res = await fetch("/api/projects", {method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify({ name, description })});
      const j = await res.json();
      if(!j.ok) throw new Error(j.error || `HTTP ${res.status}`);
      const proj = j.data;
      await refreshProjects();
      // Activate new project
      state.currentProjectId = proj.id;
      state.currentProject = proj.name;
      persistUi({ projectId: proj.id, project: proj.name });
      projectExpanded.add(proj.id);
      persistExpanded();
      closeForm();
      renderDrawer();
      showPill(`Proyecto "${proj.name}" creado`, "read");
    } catch(e){
      if(errEl) errEl.textContent = String(e.message || e).slice(0,300);
    } finally { btnCreate.disabled = false; }
  });
  // Enter on name creates (with Ctrl/Cmd not needed — simple Enter)
  nameEl?.addEventListener("keydown", (e)=>{ if(e.key==="Enter"){ e.preventDefault(); btnCreate.click(); } });
}

// ---- Skills + Linked Projects UI (drawer Project Settings panel) ----
async function fetchSkills(projectId){
  try {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : "";
    const r = await fetch(`/api/skills${qs}`);
    const j = await r.json();
    if (!j.ok) throw new Error(j.error);
    // j.data is {skills, projectId, counts}
    return j.data;
  } catch (e) { console.warn("[skills] fetch", e.message); return { skills: [], counts: {} }; }
}
async function refreshSkillsUI(){
  const pid = state.currentProjectId;
  const hint = $("#project-settings-hint");
  const listEl = $("#skills-list");
  const linkedList = $("#linked-projects-list");
  const selectEl = $("#linked-project-select");
  if (!pid) {
    if (hint) hint.textContent = "Selecciona un proyecto para ver skills y vínculos.";
    if (listEl) listEl.innerHTML = `<div class="muted" style="font-size:11px;padding:4px;">—</div>`;
    if (linkedList) linkedList.innerHTML = `<div class="muted" style="font-size:11px;padding:4px;">—</div>`;
    if (selectEl) selectEl.innerHTML = "";
    return;
  }
  const proj = state.projects.find(p => p.id === pid);
  if (hint) hint.textContent = proj ? `${proj.name}${proj.description ? " — " + proj.description.slice(0,60) : ""}` : pid;
  // Skills
  try {
    const data = await fetchSkills(pid);
    const skills = data.skills || [];
    listEl.innerHTML = "";
    if (!skills.length) listEl.innerHTML = `<div class="muted" style="font-size:11px;padding:4px;">Sin skills (global o del proyecto).</div>`;
    for (const s of skills) {
      const row = el("div", "");
      row.style.cssText = "display:flex;flex-direction:column;gap:4px;border:1px solid var(--border);border-radius:8px;padding:8px;background:var(--panel2);";
      const head = el("div", "");
      head.style.cssText = "display:flex;gap:6px;align-items:center;";
      const badge = el("span", "chip", s.scope === "global" ? "global" : "proyecto");
      badge.style.fontSize = "10px";
      const title = el("span", "", s.name);
      title.style.cssText = "font-weight:700;font-size:12px;flex:1;";
      const btnEdit = el("button", "btn", "Editar");
      btnEdit.style.cssText = "padding:2px 6px;font-size:11px;";
      const btnDel = el("button", "btn", "×");
      btnDel.style.cssText = "padding:2px 6px;font-size:12px;color:var(--err);";
      head.append(badge, title, btnEdit, btnDel);
      const preview = el("div", "muted");
      preview.style.cssText = "font-size:11px;white-space:pre-wrap;max-height:80px;overflow:auto;background:#0a0a0f;border:1px solid var(--border);border-radius:6px;padding:6px;";
      preview.textContent = (s.content || "").slice(0, 400);
      row.append(head, preview);
      // Edit flow — reuse add form populated
      btnEdit.onclick = () => {
        $("#skill-scope").value = s.scope === "global" ? "global" : "project";
        $("#skill-name").value = s.name;
        $("#skill-name").dataset.editScope = s.scope;
        $("#skill-name").dataset.editName = s.name;
        $("#skill-content").value = s.content || "";
        const form = $("#add-skill-form");
        form.classList.remove("hidden"); form.style.display = "grid";
        $("#skill-content").focus();
      };
      btnDel.onclick = async () => {
        if (!confirm(`Eliminar skill "${s.name}" [${s.scope}]?`)) return;
        try {
          const r = await fetch(`/api/skills/${encodeURIComponent(s.scope)}/${encodeURIComponent(s.name)}`, { method: "DELETE" });
          const j = await r.json();
          if (!j.ok) throw new Error(j.error);
          showPill(`Skill "${s.name}" eliminada`, "read");
          await refreshSkillsUI();
        } catch (e) { alert(String(e.message).slice(0,400)); }
      };
      listEl.appendChild(row);
    }
  } catch (e) { listEl.innerHTML = `<div class="muted" style="color:var(--err);font-size:11px;">${String(e.message).slice(0,200)}</div>`; }
  // Linked Projects
  try {
    const proj2 = state.projects.find(p => p.id === pid);
    const linked = (proj2 && proj2.linkedProjects) || [];
    linkedList.innerHTML = "";
    if (!linked.length) linkedList.innerHTML = `<div class="muted" style="font-size:11px;padding:4px;">Sin proyectos vinculados.</div>`;
    else {
      for (const lid of linked) {
        const lp = state.projects.find(p => p.id === lid);
        const name = lp ? lp.name : lid;
        const row = el("div", "");
        row.style.cssText = "display:flex;gap:6px;align-items:center;border:1px solid var(--border);border-radius:8px;padding:6px;background:var(--panel2);";
        const label = el("span", "", name);
        label.style.cssText = "flex:1;font-size:12px;";
        const btnRm = el("button", "btn", "×");
        btnRm.style.cssText = "padding:2px 6px;";
        btnRm.onclick = async () => {
          const next = linked.filter(x => x !== lid);
          try {
            const r = await fetch(`/api/projects/${encodeURIComponent(pid)}`, { method: "PATCH", headers: {"Content-Type":"application/json"}, body: JSON.stringify({ linkedProjects: next }) });
            const j = await r.json(); if (!j.ok) throw new Error(j.error);
            await refreshProjects(); await refreshSkillsUI();
          } catch (e) { alert(String(e.message).slice(0,300)); }
        };
        row.append(label, btnRm);
        linkedList.appendChild(row);
      }
    }
    // Populate select with available projects not yet linked and not self/archived
    const available = state.projects.filter(p => p.id !== pid && !p.archivedAt && !linked.includes(p.id));
    selectEl.innerHTML = available.length ? "" : `<option value="">(no hay proyectos)</option>`;
    for (const p of available) {
      const opt = document.createElement("option");
      opt.value = p.id; opt.textContent = p.name;
      selectEl.appendChild(opt);
    }
  } catch {}
}
function setupSkillsUI(){
  const btnAdd = $("#btn-add-skill"), form = $("#add-skill-form");
  const scopeEl = $("#skill-scope"), nameEl = $("#skill-name"), contentEl = $("#skill-content");
  const btnSave = $("#btn-save-skill"), btnCancel = $("#btn-cancel-skill");
  const errEl = $("#add-skill-error");
  if (!btnAdd || !form) return;
  btnAdd.onclick = () => {
    form.classList.remove("hidden"); form.style.display = "grid";
    if (nameEl) { nameEl.value = ""; delete nameEl.dataset.editScope; delete nameEl.dataset.editName; }
    if (contentEl) contentEl.value = "";
    if (errEl) errEl.textContent = "";
    setTimeout(() => nameEl.focus(), 50);
  };
  btnCancel.onclick = () => {
    form.classList.add("hidden"); form.style.display = "none";
    if (errEl) errEl.textContent = "";
    if (nameEl) { delete nameEl.dataset.editScope; delete nameEl.dataset.editName; }
  };
  btnSave.onclick = async () => {
    const rawScope = scopeEl ? scopeEl.value : "global";
    const scope = rawScope === "project" ? (state.currentProjectId || "global") : "global";
    const name = (nameEl.value || "").trim();
    const content = contentEl ? contentEl.value : "";
    if (!state.currentProjectId && scope !== "global") { if (errEl) errEl.textContent = "Selecciona un proyecto primero"; return; }
    if (!name) { if (errEl) errEl.textContent = "Nombre requerido"; nameEl.focus(); return; }
    if (errEl) errEl.textContent = "Guardando…";
    btnSave.disabled = true;
    try {
      const isEdit = !!(nameEl.dataset.editScope && nameEl.dataset.editName);
      if (isEdit) {
        const es = nameEl.dataset.editScope, en = nameEl.dataset.editName;
        const r = await fetch(`/api/skills/${encodeURIComponent(es)}/${encodeURIComponent(en)}`, { method: "PATCH", headers: {"Content-Type":"application/json"}, body: JSON.stringify({ content }) });
        const j = await r.json(); if (!j.ok) throw new Error(j.error);
        // If user changed name/scope during edit, we keep original location; rename not supported via edit — inform
        if (es !== scope || en !== name) {
          // Create new and keep old — simplest: also create new
          const r2 = await fetch(`/api/skills`, { method: "POST", headers: {"Content-Type":"application/json"}, body: JSON.stringify({ scope, name, content }) });
          const j2 = await r2.json(); if (!j2.ok) throw new Error(j2.error);
        }
      } else {
        const r = await fetch(`/api/skills`, { method: "POST", headers: {"Content-Type":"application/json"}, body: JSON.stringify({ scope, name, content }) });
        const j = await r.json(); if (!j.ok) throw new Error(j.error);
      }
      form.classList.add("hidden"); form.style.display = "none";
      if (nameEl) { delete nameEl.dataset.editScope; delete nameEl.dataset.editName; }
      if (errEl) errEl.textContent = "";
      showPill(`Skill "${name}" guardada [${scope}]`, "read");
      await refreshSkillsUI();
    } catch (e) { if (errEl) errEl.textContent = String(e.message).slice(0,400); }
    finally { btnSave.disabled = false; }
  };
  $("#btn-link-project")?.addEventListener("click", async () => {
    const pid = state.currentProjectId;
    if (!pid) { $("#linked-error").textContent = "Selecciona un proyecto"; return; }
    const sel = $("#linked-project-select");
    const target = sel ? sel.value : "";
    if (!target) return;
    const proj = state.projects.find(p => p.id === pid);
    const cur = (proj && proj.linkedProjects) || [];
    if (cur.includes(target)) return;
    try {
      const next = [...cur, target];
      const r = await fetch(`/api/projects/${encodeURIComponent(pid)}`, { method: "PATCH", headers: {"Content-Type":"application/json"}, body: JSON.stringify({ linkedProjects: next }) });
      const j = await r.json(); if (!j.ok) throw new Error(j.error);
      await refreshProjects(); await refreshSkillsUI();
      $("#linked-error").textContent = "";
    } catch (e) { $("#linked-error").textContent = String(e.message).slice(0,300); }
  });
  $("#btn-summarize-project")?.addEventListener("click", async () => {
    const pid = state.currentProjectId;
    if (!pid) { $("#linked-error").textContent = "Selecciona un proyecto"; return; }
    $("#linked-error").textContent = "Generando resumen…";
    try {
      const r = await fetch(`/api/projects/${encodeURIComponent(pid)}/summarize`, { method: "POST", headers: {"Content-Type":"application/json"}, body: "{}" });
      const j = await r.json(); if (!j.ok) throw new Error(j.error);
      $("#linked-error").textContent = "";
      showPill(`Resumen generado: ${(j.data && j.data.summary || "").slice(0,60)}…`, "read");
      await refreshSkillsUI();
    } catch (e) { $("#linked-error").textContent = String(e.message).slice(0,300); }
  });
}

// ---- composer: input expandible + Enter envía
function autoGrow(){
  promptEl.style.height = "auto";
  promptEl.style.height = Math.min(promptEl.scrollHeight, 140) + "px";
}
promptEl.addEventListener("input", autoGrow);
promptEl.addEventListener("keydown", e=>{
  if(e.key==="Enter" && !e.shiftKey){
    e.preventDefault();
    sendPrompt();
  }
});

// ---- multimodal: chips descartables + Base64
function renderChips(){
  if(!chipsEl) return;
  if(!state.attachedFiles.length){ chipsEl.classList.add("hidden"); chipsEl.innerHTML=""; return; }
  chipsEl.classList.remove("hidden");
  chipsEl.innerHTML="";
  state.attachedFiles.forEach((f, idx)=>{
    const chip = el("div","chip-file");
    let thumb="";
    if(f.kind==="image" && f.previewUrl){
      thumb=`<img class="thumb" src="${f.previewUrl}" alt="">`;
    } else {
      const icons={video:"🎬",audio:"🎵",pdf:"📄",docx:"📝",text:"📄",code:"💻",exec:"⚙️",unknown:"📎"};
      thumb=`<span style="font-size:14px">${icons[f.kind]||"📎"}</span>`;
    }
    const hint = (f.kind==="exec"||f.kind==="code") ? `<span class="chip-doc-hint">${f.ext} ● análisis</span>` : "";
    chip.innerHTML = `${thumb}<span class="name" title="${f.name}">${f.name}</span><span class="meta">${humanSize(f.size)}</span>${hint}<button class="x" data-idx="${idx}" aria-label="Quitar">×</button>`;
    chipsEl.appendChild(chip);
  });
  chipsEl.querySelectorAll(".x").forEach(btn=>{
    btn.addEventListener("click", ()=>{
      const idx=parseInt(btn.dataset.idx);
      state.attachedFiles.splice(idx,1);
      renderChips();
      showPill(state.attachedFiles.length?`Quedan ${state.attachedFiles.length}`:"Sin adjuntos","read");
    });
  });
}
async function handleFiles(fileList){
  const files=[...fileList].slice(0,6);
  if(!files.length) return;
  if(state.attachedFiles.length + files.length > 6){
    showPill("Máximo 6 archivos","thinking");
    files.splice(6 - state.attachedFiles.length);
  }
  for(const file of files){
    const {kind, ext, mime} = getFileKind(file);
    const limits={image:15*1024*1024, video:30*1024*1024, audio:25*1024*1024, pdf:20*1024*1024, docx:20*1024*1024, text:10*1024*1024, code:10*1024*1024, exec:10*1024*1024, unknown:10*1024*1024};
    const lim=limits[kind]||10*1024*1024;
    if(file.size > lim){
      showPill(`${file.name} muy grande (${humanSize(file.size)} > ${humanSize(lim)}) — se omite`,"thinking");
      continue;
    }
    let entry={name:file.name, type: file.type || mimeGuess(ext) || mime, size:file.size, kind, ext, b64:null, text:null, previewUrl:null};
    try{
      if(kind==="image" || kind==="video" || kind==="audio" || kind==="pdf" || kind==="docx"){
        const b64 = await fileToBase64(file);
        entry.b64 = b64;
        if(kind==="image") entry.previewUrl = `data:${entry.type};base64,${b64}`;
      } else if(kind==="text" || kind==="code" || kind==="exec"){
        const txt = await fileToText(file);
        entry.text = txt.slice(0,120000);
      } else {
        if(file.size < 2*1024*1024){
          const txt = await fileToText(file).catch(()=> null);
          if(txt && txt.trim().length>0) entry.text = txt.slice(0,80000);
          else entry.b64 = await fileToBase64(file);
        } else {
          entry.b64 = await fileToBase64(file);
        }
      }
    }catch(e){
      showPill(`Error leyendo ${file.name}: ${String(e).slice(0,80)}`,"thinking");
      continue;
    }
    state.attachedFiles.push(entry);
  }
  renderChips();
  showPill(state.attachedFiles.length?`${state.attachedFiles.length} archivo(s) listos`:"Sin archivos","read");
  if(filePicker) filePicker.value="";
}
btnAttach?.addEventListener("click", ()=> filePicker?.click());
filePicker?.addEventListener("change", async ()=>{
  const files=[...(filePicker.files||[])];
  await handleFiles(files);
});
// drag & drop sobre composer
const composerEl = $(".composer");
composerEl?.addEventListener("dragover", e=>{ e.preventDefault(); composerEl.style.borderColor="var(--accent)"; });
composerEl?.addEventListener("dragleave", ()=>{ composerEl.style.borderColor=""; });
composerEl?.addEventListener("drop", async e=>{
  e.preventDefault(); composerEl.style.borderColor="";
  const files=[...(e.dataTransfer?.files||[])];
  if(files.length) await handleFiles(files);
});

function getExecAnalysisHint(files){
  const execFiles = files.filter(f=> f.kind==="exec" || f.kind==="code" || f.kind==="text" );
  if(!execFiles.length) return "";
  const toolMap={
    cmd:{label:"Windows Batch (.cmd/.bat)", tools:["wine cmd","cmd.exe","pwsh"], cmd:"wine cmd /c"},
    bat:{label:"Windows Batch (.bat)", tools:["wine cmd","pwsh"], cmd:"wine cmd /c"},
    sh:{label:"Shell (.sh)", tools:["bash","shellcheck","shfmt"], cmd:"bash"},
    bash:{label:"Shell (.sh)", tools:["bash","shellcheck"], cmd:"bash"},
    ps1:{label:"PowerShell (.ps1)", tools:["pwsh","powershell"], cmd:"pwsh -File"},
    py:{label:"Python (.py)", tools:["python3","ruff","black","pytest"], cmd:"python3"},
    js:{label:"JavaScript (.js)", tools:["node","eslint","prettier"], cmd:"node"},
    mjs:{label:"JavaScript (.mjs)", tools:["node"], cmd:"node"},
    cjs:{label:"JavaScript (.cjs)", tools:["node"], cmd:"node"},
    ts:{label:"TypeScript (.ts)", tools:["ts-node","tsc","tsx"], cmd:"npx tsx"},
    java:{label:"Java (.java)", tools:["javac","java","maven","gradle"], cmd:"javac && java"},
    go:{label:"Go (.go)", tools:["go run","go build"], cmd:"go run"},
    rs:{label:"Rust (.rs)", tools:["rustc","cargo run"], cmd:"cargo run"},
    c:{label:"C (.c)", tools:["gcc","clang","make"], cmd:"gcc"},
    cpp:{label:"C++ (.cpp)", tools:["g++","clang++","cmake"], cmd:"g++"},
    html:{label:"HTML", tools:["npx serve","python3 -m http.server"], cmd:"abrir en navegador"},
    css:{label:"CSS", tools:["prettier","stylelint"], cmd:"prettier"},
    php:{label:"PHP (.php)", tools:["php"], cmd:"php"},
    rb:{label:"Ruby (.rb)", tools:["ruby"], cmd:"ruby"},
    txt:{label:"Texto (.txt)", tools:["cat","less","grep","awk","sed"], cmd:"cat"},
    md:{label:"Markdown (.md)", tools:["cat","pandoc","glow"], cmd:"cat"},
  };
  const lines=["[Archivos adjuntos — análisis Linux solicitado]"];
  execFiles.forEach(f=>{
    const info=toolMap[f.ext] || {label:`${f.ext.toUpperCase()} (.${f.ext})`, tools:["cat","less","file"], cmd:"cat"};
    lines.push(`- ${f.name} (${info.label}, ${humanSize(f.size)}): herramientas sugeridas: ${info.tools.join(", ")}. Comando propuesto: \`${info.cmd} ${f.name}\` — verificar sintaxis con ${info.tools[0]}`);
  });
  lines.push("Instrucción para opencode: analiza sintaxis de cada archivo, detecta errores, propón corrección y comando compatible con Linux (POCO F3, proot Ubuntu). Si es Windows-only (.cmd/.bat), sugiere equivalente Linux o ejecución vía wine si está disponible. Indica dependencias (pip, npm, apt).");
  return lines.join("\n");
}

// ---- voz bidireccional: switch Texto/Pulsar vs Conversación Continua dúplex
const voiceModeLabelPush = document.querySelector('.mode-label[data-mode="push"]');
const voiceModeLabelDuplex = document.querySelector('.mode-label[data-mode="duplex"]');
function updateVoiceModeLabels(){
  const isDuplex = state.voiceMode==="duplex";
  voiceModeLabelPush?.classList.toggle("active", !isDuplex);
  voiceModeLabelDuplex?.classList.toggle("active", isDuplex);
  if(voiceModeToggle) voiceModeToggle.checked = isDuplex;
  if(btnMic) btnMic.title = isDuplex ? "Conversación continua — escucha activa" : "Pulsar para hablar";
}
function setVoiceMode(mode, persist=true){
  state.voiceMode = mode==="duplex" ? "duplex" : "push";
  if (persist) localStorage.setItem("occ.voiceMode", state.voiceMode);
  updateVoiceModeLabels();
  // Notify native wake gate (fix 2) — native reads duplex flag via bridge
  try { if (window.NativeBridge && window.NativeBridge.onVoiceModeChanged) window.NativeBridge.onVoiceModeChanged(state.voiceMode==="duplex"); } catch(_){}
  // reinit recognition with new continuous flag
  initRecognition();
  if(state.voiceMode==="duplex"){
    showPill("Modo conversación — escucha tras TTS", "thinking");
    // Only on explicit toggle — add 600ms debounce to avoid immediate mic pop (fix 4)
    setTimeout(()=> { if(state.voiceMode==="duplex" && !state.listening && !speaking) startListening(); }, 600);
  } else {
    showPill("Modo texto — pulsar para hablar", "read");
    stopListening();
  }
}
voiceModeToggle?.addEventListener("change", ()=>{
  setVoiceMode(voiceModeToggle.checked ? "duplex" : "push");
});
// On load: respect stored preference only if it was duplex (explicit opt-in persisted); otherwise force Texto (fix 1)
(function initVoiceModeOnLoad(){
  const stored = localStorage.getItem("occ.voiceMode");
  if (stored === "duplex") {
    state.voiceMode = "duplex";
    updateVoiceModeLabels();
  } else {
    state.voiceMode = "push";
    if (voiceModeToggle) voiceModeToggle.checked = false;
    updateVoiceModeLabels();
  }
})();

// TTS: chunks + Web Audio API low latency + interrupción dúplex
let ttsQueue=[], speaking=false;
let audioCtx=null;
function ensureAudioContext(){
  try{
    if(!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if(audioCtx && audioCtx.state==="suspended") audioCtx.resume();
  }catch{}
}
function queueTts(text){
  if(!text) return;
  if (typeof speechSynthesis === 'undefined') return; // silent in WebView without Web Speech API — native TTS handles voice
  ensureAudioContext();
  const chunks = text.match(/[^.!?¡¿\n]+[.!?¡¿\n]+|[^.!?¡¿\n]+$/g) || [text];
  const limited=[];
  chunks.forEach(c=>{
    if(c.length>200){
      c.match(/.{1,200}(?:\s|$)/g)?.forEach(s=> limited.push(s.trim()));
    } else limited.push(c.trim());
  });
  ttsQueue.push(...limited.filter(Boolean));
  if(!speaking) drainTts();
}
function cancelTts(){
  if (typeof speechSynthesis !== 'undefined') { try{ speechSynthesis.cancel(); }catch{} }
  ttsQueue=[]; speaking=false;
}
function drainTts(){
  if(!ttsQueue.length){ speaking=false; hidePill(); if(state.voiceMode==="duplex"){ setTimeout(()=> { if(state.voiceMode==="duplex" && !state.listening && !speaking) startListening(); }, 600); } return; }
  if (typeof speechSynthesis === 'undefined' || typeof SpeechSynthesisUtterance === 'undefined') { console.warn("[drainTts] no speechSynthesis/Utterance"); speaking=false; hidePill(); return; }
  speaking=true;
  const chunk=ttsQueue.shift();
  showPill(`🔊 ${chunk.slice(0,42)}… (${ttsQueue.length})`, "thinking");
  let ut; try { ut=new SpeechSynthesisUtterance(chunk); } catch(e){ console.error("[drainTts] Utterance", e); speaking=false; setTimeout(drainTts, 70); return; }
  // pick es voice
  let pref=null; try { const vs=speechSynthesis.getVoices(); pref=vs.find(v=> v.lang.toLowerCase().startsWith("es-419")||v.lang.toLowerCase()==="es-us") || vs.find(v=> v.lang.toLowerCase().startsWith("es")); } catch{}
  if(pref) ut.voice=pref;
  ut.lang= pref?.lang || "es-ES";
  ut.rate=1; ut.pitch=1;
  ut.onend=()=> setTimeout(drainTts, 70);
  ut.onerror=()=> setTimeout(drainTts, 70);
  try { speechSynthesis.speak(ut); } catch(e){ console.error("[drainTts] speak", e); speaking=false; setTimeout(drainTts, 70); }
}
function initVoices(){
  if (typeof speechSynthesis === 'undefined') {
    console.warn("[initVoices] speechSynthesis not available in WebView — TTS via Web will be silent, native TTS still works");
    return;
  }
  const load=()=>{
    try { const vs=speechSynthesis.getVoices(); state.voices=vs; } catch(e){ console.warn("[initVoices] getVoices", e); }
  };
  try { load(); speechSynthesis.onvoiceschanged=load; } catch(e){ console.warn("[initVoices] onvoiceschanged", e); }
}
try { initVoices(); } catch(e){ console.error("[initVoices] uncaught", e); }

// STT: Web Speech API + Android bridge compatible, conmutado por voiceMode
let recognition=null;
let finalsBuf="";
function initRecognition(){
  const SR=window.SpeechRecognition || window.webkitSpeechRecognition;
  if(!SR){ if(btnMic) btnMic.disabled=true; return; }
  if(recognition){ try{ recognition.onend=null; recognition.onresult=null; recognition.onerror=null; recognition.abort(); }catch{} }
  recognition = new SR();
  recognition.lang="es-ES";
  recognition.continuous = state.voiceMode==="duplex";
  recognition.interimResults = true;
  recognition.maxAlternatives=1;
  finalsBuf="";
  recognition.onstart=()=>{
    state.listening=true; btnMic?.classList.add("on");
    showPill(state.voiceMode==="duplex" ? "Escuchando continuo…" : "Escuchando… habla", "thinking");
  };
  recognition.onresult=(ev)=>{
    let interim="", fin="";
    for(let i=ev.resultIndex;i<ev.results.length;i++){
      const res=ev.results[i];
      const txt=res[0].transcript;
      if(res.isFinal) fin+= txt+" ";
      else interim+= txt+" ";
    }
    // interrupción dúplex: si TTS hablando y hay interim, corta TTS
    if(speaking && interim.trim().length>1){
      cancelTts();
      showPill("Interrumpido — te escucho…", "thinking");
    }
    if(fin) finalsBuf+= fin;
    if(interim) showPill(`…${interim.slice(0,56)}`, "thinking");
    else if(fin) showPill(`✓ ${fin.slice(0,56)}`, "read");
  };
  // Debounced duplex restart — prevents tight mic on/off loop (fix 4)
  function scheduleDuplexRestart(delay=500) {
    if (state.voiceMode !== "duplex") return;
    if (speaking) return; // stay silent while TTS plays
    if (state.listening) return;
    setTimeout(()=>{ if(state.voiceMode==="duplex" && !state.listening && !speaking) startListening(); }, delay);
  }
  recognition.onend=()=>{
    btnMic?.classList.remove("on");
    state.listening=false;
    const t=finalsBuf.trim();
    finalsBuf="";
    if(t){
      // Before sending to opencode, check voice command registry (spec 2) — avoids LLM for device actions
      // Wrap to not break duplex flow; if handled, skip sendPrompt and re-listen with debounce
      (async ()=> {
        const handled = await voiceHandleText(t);
        if (handled) {
          hidePill();
          scheduleDuplexRestart(800);
          return;
        }
        if(state.voiceMode==="duplex"){
          promptEl.value = t;
          autoGrow();
          sendPrompt();
        } else {
          promptEl.value = (promptEl.value ? promptEl.value+" " : "") + t;
          autoGrow(); promptEl.focus(); hidePill();
        }
        scheduleDuplexRestart(900);
      })();
      return;
    } else {
      hidePill();
    }
    scheduleDuplexRestart(700);
  };
  recognition.onerror=(e)=>{
    btnMic?.classList.remove("on");
    state.listening=false;
    if(e.error==="no-speech"){
      // No speech — back off before retry to avoid tight loop (fix 4)
      showPill(state.voiceMode==="duplex" ? `Escuchando…` : `STT: ${e.error||"error"}`, "thinking");
      if(state.voiceMode==="duplex") scheduleDuplexRestart(1200);
      else hidePill();
    } else {
      showPill(`STT: ${e.error||"error"}`, "thinking");
      if(state.voiceMode==="duplex") scheduleDuplexRestart(1500);
    }
  };
  state.recognition=recognition;
}
function startListening(){
  ensureAudioContext();
  if(!recognition) initRecognition();
  if(!recognition) return;
  if(state.listening) return;
  // si TTS está hablando y duplex quiere escuchar, sigue escuchando igual (dúplex permite interrumpir)
  try{ recognition.start(); }catch(e){ showPill(String(e).slice(0,80),"thinking"); }
}
function stopListening(){
  if(!recognition) return;
  try{ recognition.stop(); }catch{}
}
btnMic?.addEventListener("click", ()=>{
  ensureAudioContext();
  if(state.voiceMode==="duplex"){
    // toggle continuo
    if(state.listening) stopListening();
    else startListening();
  } else {
    // push
    if(state.listening) stopListening();
    else startListening();
  }
});
initRecognition();

// expose cancelTts for external (e.g., sendPrompt should not cancel, but user speech does)
window.__cancelTts = cancelTts;
window.__queueTts = queueTts;
window.startVoiceSession = function() {
  // Wake word entry: switch to duplex and start listening explicitly
  if (state.voiceMode !== "duplex") setVoiceMode("duplex");
  startVoiceSession();
};

// ---- Voice helpers: TTS confirm + log (spec 5-6) ----
function voiceTtsConfirm(msg) {
  // Speak confirmation for every executed voice command (spec 5)
  try { queueTts(msg); } catch {}
  // Also append as system message for visibility
  addMsg("system", msg);
}
async function voiceLog(recognizedText, matchedCommand, result) {
  try {
    await jpost("/api/voice/log", {
      recognizedText, matchedCommand,
      result: typeof result === "string" ? result : JSON.stringify(result).slice(0, 1000),
      projectId: state.currentProjectId || null,
      sessionId: state.currentSessionId || null
    });
  } catch {}
}
function normalizeVoiceText(s) { return String(s || "").trim().toLowerCase().replace(/\s+/g, " ").replace(/[.,!?;:]+$/g, ""); }
function fuzzyFindProject(query) {
  const q = normalizeVoiceText(query);
  const projs = state.projects || [];
  let best = null, bestScore = 0;
  for (const p of projs) {
    const name = normalizeVoiceText(p.name);
    if (name === q) return p;
    if (name.includes(q) || q.includes(name)) {
      const score = Math.min(name.length, q.length) / Math.max(name.length, q.length);
      if (score > bestScore) { bestScore = score; best = p; }
    }
    // Levenshtein-ish: count shared tokens
    const qt = q.split(" "); const nt = name.split(" ");
    const shared = qt.filter(t => nt.some(n => n.startsWith(t) || t.startsWith(n))).length;
    const score2 = shared / Math.max(qt.length, nt.length);
    if (score2 > bestScore) { bestScore = score2; best = p; }
  }
  return bestScore > 0.3 ? best : null;
}
async function voiceHandleText(recognizedText) {
  const norm = normalizeVoiceText(recognizedText);
  // Strip leading wake word if present (viernes escucha / hola viernes etc.)
  const wakeStripped = norm.replace(/^(viernes\s+(escucha|atenta)|hola\s+viernes)\s+/, "").trim() || norm;

  // 1) nuevo proyecto [nombre]
  let m = wakeStripped.match(/^nuevo proyecto\s+(.+)$/);
  if (m) {
    const nombre = m[1].trim();
    try {
      const r = await jpost("/api/projects", { name: nombre, description: `Creado por voz: ${recognizedText}` });
      const proj = r.data || r;
      // Unwrap if envelope
      if (r && !r.data && r.id) { /* bare */ }
      state.currentProjectId = (proj && proj.id) ? proj.id : (r.data && r.data.id ? r.data.id : null);
      await refreshProjects();
      if (state.currentProjectId) {
        persistUi({ projectId: state.currentProjectId });
        projectExpanded.add(state.currentProjectId);
        persistExpanded();
      }
      const msg = `Proyecto "${nombre}" creado`;
      voiceTtsConfirm(msg);
      await voiceLog(recognizedText, "nuevo proyecto", msg);
    } catch (e) { const msg = `No pude crear proyecto "${nombre}": ${e.message}`; voiceTtsConfirm(msg); await voiceLog(recognizedText, "nuevo proyecto", msg); }
    return true;
  }

  // 2) abrir proyecto [nombre] — fuzzy match
  m = wakeStripped.match(/^abrir proyecto\s+(.+)$/);
  if (m) {
    const nombre = m[1].trim();
    const proj = fuzzyFindProject(nombre);
    if (!proj) { const msg = `No encontré proyecto "${nombre}"`; voiceTtsConfirm(msg); await voiceLog(recognizedText, "abrir proyecto", msg); return true; }
    state.currentProjectId = proj.id;
    state.currentProject = proj.name;
    persistUi({ projectId: proj.id, project: proj.name });
    projectExpanded.add(proj.id);
    persistExpanded();
    renderDrawer();
    const msg = `Proyecto "${proj.name}" abierto`;
    voiceTtsConfirm(msg);
    await voiceLog(recognizedText, "abrir proyecto", msg);
    await refreshSkillsUI();
    return true;
  }

  // 3) nueva sesión
  m = wakeStripped.match(/^nueva sesi[oó]n\s*$/);
  if (m) {
    if (!state.currentProjectId) { const msg = "Selecciona un proyecto primero para crear sesión"; voiceTtsConfirm(msg); await voiceLog(recognizedText, "nueva sesión", msg); return true; }
    try {
      const sid = await createSession();
      const msg = sid ? `Nueva sesión creada` : "No pude crear sesión";
      voiceTtsConfirm(msg);
      await voiceLog(recognizedText, "nueva sesión", msg + (sid ? ` ${sid.slice(0,8)}` : ""));
    } catch (e) { const msg = `Error creando sesión: ${e.message}`; voiceTtsConfirm(msg); await voiceLog(recognizedText, "nueva sesión", msg); }
    return true;
  }

  // 4) abrir [app]
  m = wakeStripped.match(/^abrir\s+(.+)$/);
  if (m) {
    const app = m[1].trim();
    // Avoid colliding with "abrir proyecto" already handled; fallback to device launch
    try {
      const r = await jpost("/api/device/launch", { pkg: app });
      const ok = r && (r.code === 0 || r.ok || String(r.stdout || "").includes("Starting:"));
      const msg = ok ? `Abriendo ${app}` : `Intenté abrir ${app}: ${JSON.stringify(r).slice(0,200)}`;
      voiceTtsConfirm(msg);
      await voiceLog(recognizedText, "abrir app", msg);
    } catch (e) { const msg = `No pude abrir ${app}: ${e.message}`; voiceTtsConfirm(msg); await voiceLog(recognizedText, "abrir app", msg); }
    return true;
  }

  // 5) tomar captura
  if (/^(tomar captura|hacer captura|captura de pantalla)/.test(wakeStripped)) {
    try {
      const r = await jget("/api/device/screenshot");
      const data = r && r.data ? r.data : r;
      const b64 = data.b64 || data.data || "";
      if (b64) {
        const img = document.createElement("img");
        img.src = "data:image/png;base64," + b64;
        img.style.maxWidth = "100%"; img.style.borderRadius = "12px"; img.style.marginTop = "8px";
        img.alt = "captura por voz";
        msgsEl.appendChild(img);
        msgsEl.scrollTop = msgsEl.scrollHeight;
      }
      const msg = b64 ? `Captura tomada ${Math.round(b64.length*0.75/1024)}KB` : "Captura vacía";
      voiceTtsConfirm(msg);
      await voiceLog(recognizedText, "tomar captura", msg);
    } catch (e) { const msg = `Error captura: ${e.message}`; voiceTtsConfirm(msg); await voiceLog(recognizedText, "tomar captura", msg); }
    return true;
  }

  // 6) estado del sistema — fetch + TTS
  if (/^(estado del sistema|como esta el sistema|estado)/.test(wakeStripped)) {
    try {
      const s = await jget("/api/system/status");
      const ready = s.ready ? "listo" : "no listo";
      const ownership = s.sessionOwnership || s.ownership || "desconocido";
      const pid = s.sessionInfo && s.sessionInfo.pid ? ` pid ${s.sessionInfo.pid}` : "";
      const msg = `Sistema ${ready}, propiedad ${ownership}${pid}, hub ${s.hub || ""}`;
      voiceTtsConfirm(msg);
      addMsg("system", JSON.stringify(s, null, 2).slice(0, 1200));
      await voiceLog(recognizedText, "estado del sistema", msg);
    } catch (e) { const msg = `No pude leer estado: ${e.message}`; voiceTtsConfirm(msg); await voiceLog(recognizedText, "estado del sistema", msg); }
    return true;
  }

  // 7) enviar a [contacto] por whatsapp [mensaje]
  m = wakeStripped.match(/^enviar a\s+(.+?)\s+por whatsapp\s+(.+)$/);
  if (m) {
    const contacto = m[1].trim(), mensaje = m[2].trim();
    try {
      // Use direct intent then execute flow — parse contact to slot
      const exec = await jpost("/api/assistant/execute", { action: "whatsapp_send", slots: { contact: contacto, text: mensaje } });
      const msg = exec && exec.ok ? `WhatsApp a ${contacto} enviado` : `WhatsApp falló: ${exec.error || exec.message || JSON.stringify(exec).slice(0,300)}`;
      voiceTtsConfirm(msg);
      if (exec && exec.result && exec.result.b64) { /* no image */ }
      await voiceLog(recognizedText, "enviar whatsapp", msg);
      handleAssistantExecResult(exec, { action: "whatsapp_send" });
    } catch (e) { const msg = `Error WhatsApp: ${e.message}`; voiceTtsConfirm(msg); await voiceLog(recognizedText, "enviar whatsapp", msg); }
    return true;
  }
  // Alternate phrasing: "manda whatsapp a X Y"
  m = wakeStripped.match(/^(manda|env[ií]a)\s+whatsapp\s+a\s+(.+?)\s+(.+)$/);
  if (m) {
    const contacto = m[2].trim(), mensaje = m[3].trim();
    try {
      const exec = await jpost("/api/assistant/execute", { action: "whatsapp_send", slots: { contact: contacto, text: mensaje } });
      const msg = exec && exec.ok ? `WhatsApp a ${contacto} enviado` : `WhatsApp falló: ${exec.error || JSON.stringify(exec).slice(0,200)}`;
      voiceTtsConfirm(msg);
      await voiceLog(recognizedText, "enviar whatsapp", msg);
      handleAssistantExecResult(exec, { action: "whatsapp_send" });
    } catch (e) { const msg = `Error WhatsApp: ${e.message}`; voiceTtsConfirm(msg); await voiceLog(recognizedText, "enviar whatsapp", msg); }
    return true;
  }

  return false;
}
function startVoiceSession() {
  // Called by APK wake word injection or manual voice mode — starts listening and shows UI cue
  try { showPill("Escuchando… di tu comando", "thinking"); } catch {}
  // Ensure listening for next STT cycle
  try { if (state.voiceMode === "duplex" && !state.listening) startListening(); else if (!state.listening) startListening(); } catch {}
  voiceTtsConfirm("Te escucho");
}

// ---- Asistente Root: intent → execute → disambiguation tarjetas + chip píldora
async function tryAssistantIntent(text){
  try{
    const intent = await jpost("/api/assistant/intent", {text});
    if(!intent || !intent.action) return {handled:false};
    if(intent.action==="llm_classify") return {handled:false};
    if(intent.type==="disambiguation"){
      renderDisambiguation(intent);
      return {handled:true};
    }
    if(intent.action){
      showPill(`Acción Root: ${intent.action}…`, "bash");
      statusPill?.classList.add("root");
      const execRes = await jpost("/api/assistant/execute", {action: intent.action, slots: intent.slots||{}});
      handleAssistantExecResult(execRes, intent);
      return {handled:true};
    }
    return {handled:false};
  }catch(e){ console.warn("[assistant] intent err", e); return {handled:false}; }
}
function handleAssistantExecResult(r, original){
  statusPill?.classList.remove("root");
  if(!r) return;
  if(r.type==="disambiguation"){
    renderDisambiguation(r);
    return;
  }
  if(r.ok){
    const msg = formatAssistantSuccess(r);
    addMsg("assistant", msg);
    if(r.action==="screenshot" && r.result?.b64){
      const img=document.createElement("img");
      img.src="data:image/png;base64,"+r.result.b64;
      img.style.maxWidth="100%"; img.style.borderRadius="12px"; img.style.marginTop="8px";
      img.alt="captura";
      msgsEl.appendChild(img);
      msgsEl.scrollTop = msgsEl.scrollHeight;
    }
    showPill("✓ Acción completada","read");
    setTimeout(hidePill, 2200);
    return;
  }
  if(r.need==="phone" || r.need==="text" || r.need==="contact"){
    renderDisambiguation(r);
    return;
  }
  addMsg("system", `Error acción Root: ${r.error||r.message||JSON.stringify(r).slice(0,500)}`);
  showPill("Error acción Root","thinking");
}
function formatAssistantSuccess(r){
  const s=r.slots||{};
  switch(r.action){
    case "launch": return `✓ App abierta: ${s.pkg || s.app || ""} — ${r.result?.stdout?.includes("Events injected")?"OK":"revisa"}`;
    case "screenshot": return `✓ Captura tomada (${r.result?.b64 ? Math.round(r.result.b64.length*0.75/1024)+"KB" : ""})`;
    case "volume_up": return "✓ Volumen subido";
    case "volume_down": return "✓ Volumen bajado";
    case "volume_mute": return "✓ Silenciado";
    case "lock_screen": return "✓ Pantalla bloqueada";
    case "unlock_screen": return "✓ Pantalla desbloqueada (gesto enviado)";
    case "wifi_on": return "✓ WiFi activado";
    case "wifi_off": return "✓ WiFi desactivado";
    case "bluetooth_on": return "✓ Bluetooth activado";
    case "bluetooth_off": return "✓ Bluetooth desactivado";
    case "whatsapp_send": return `✓ WhatsApp a ${s.contactName || s.contact || s.phone}: ${s.text ? `"${s.text.slice(0,80)}"` : ""} — ${r.result?.autoSend?.result?.ok ? "enviado" : "abierto, pulsa Enviar si no se envió solo"}`;
    case "call": return `✓ Llamando a ${s.contactName || s.contact || s.phone}`;
    default: return `✓ Acción ${r.action} completada`;
  }
}
function renderDisambiguation(data){
  const card = el("div","assistant-card");
  const title = el("div","card-title", data.message || "Elige una opción");
  const desc = el("div","card-desc", `Acción: ${data.action} · ${data.kind||data.need||""}`);
  const opts = el("div","card-options");
  const options = Array.isArray(data.options) ? data.options : [];
  if(options.length){
    options.forEach(opt=>{
      const btn = el("button","card-option");
      const label = opt.label || opt.name || opt.pkg || `Opción ${opt.idx}`;
      const sub = opt.phones ? opt.phones.join(", ") : opt.pkg || "";
      btn.innerHTML = `<span><b>${label}</b><small>${sub}</small></span><span>→</span>`;
      btn.onclick = async ()=>{
        btn.disabled=true;
        showPill("Acción Root en progreso…","bash");
        statusPill?.classList.add("root");
        try{
          const execRes = await jpost("/api/assistant/execute", {action: data.action, slots: data.slots||{}, selectedIndex: opt.idx});
          card.remove();
          handleAssistantExecResult(execRes, data);
        }catch(e){ addMsg("system","Error: "+String(e).slice(0,400)); }
        statusPill?.classList.remove("root");
        hidePill();
      };
      opts.appendChild(btn);
    });
  } else if(data.kind==="whatsapp_text" || data.need==="text"){
    const input = el("input","card-input"); input.placeholder="Escribe el mensaje…";
    const sendBtn = el("button","btn primary","Enviar WhatsApp"); sendBtn.style.marginTop="8px";
    opts.appendChild(input); opts.appendChild(sendBtn);
    setTimeout(()=> input.focus(), 120);
    sendBtn.onclick = async ()=>{
      const text = input.value.trim(); if(!text) return;
      sendBtn.disabled=true;
      showPill("Acción Root en progreso…","bash"); statusPill?.classList.add("root");
      try{
        const execRes = await jpost("/api/assistant/execute", {action: data.action, slots: {...(data.slots||{}), text}});
        card.remove(); handleAssistantExecResult(execRes, data);
      }catch(e){ addMsg("system","Error: "+String(e).slice(0,400)); }
      statusPill?.classList.remove("root"); hidePill();
    };
    input.addEventListener("keydown", e=>{ if(e.key==="Enter") sendBtn.click(); });
  } else if(data.need==="phone"){
    const input = el("input","card-input"); input.placeholder="+51999…";
    const btn = el("button","btn primary","Usar número"); btn.style.marginTop="8px";
    opts.appendChild(input); opts.appendChild(btn);
    setTimeout(()=> input.focus(), 120);
    btn.onclick = async ()=>{
      const phone=input.value.trim(); if(!phone) return;
      btn.disabled=true; showPill("Acción Root en progreso…","bash"); statusPill?.classList.add("root");
      try{
        const execRes = await jpost("/api/assistant/execute", {action: data.action, slots:{...(data.slots||{}), phone}, forcePhone:true});
        card.remove(); handleAssistantExecResult(execRes, data);
      }catch(e){ addMsg("system","Error: "+String(e).slice(0,400)); }
      statusPill?.classList.remove("root"); hidePill();
    };
  } else {
    const hint = el("div","muted", data.message || JSON.stringify(data).slice(0,400));
    hint.style.fontSize="12px"; opts.appendChild(hint);
  }
  card.appendChild(title); card.appendChild(desc); card.appendChild(opts);
  msgsEl.appendChild(card); msgsEl.scrollTop = msgsEl.scrollHeight;
}

// ---- send (multimodal) — con bypass Root directo sin pasar por LLM programático
async function sendPrompt(){
  // bypass directo: si es comando de voz directo (abre X, captura, volumen, whatsapp*) sin adjuntos, no crear sesión LLM
  const rawText = promptEl.value.trim();
  // solo si hay texto y no hay archivos adjuntos pesados; si hay adjuntos, siempre va a LLM
  if(rawText && !state.attachedFiles.length){
    try{
      const intentCheck = await jpost("/api/assistant/intent", {text: rawText});
      const isDirect = intentCheck && intentCheck.action && intentCheck.action!=="llm_classify";
      if(isDirect){
        // muestra burbuja usuario igualmente
        if(state.currentProject && rawText.length < 3000 && !rawText.includes(state.currentProject)){
          // opcional: mantener contexto visual pero no contaminar intent
        }
        addMsg("user", rawText);
        promptEl.value=""; autoGrow();
        showPill("Acción Root en progreso…","bash");
        statusPill?.classList.add("root");
        if(intentCheck.type==="disambiguation"){
          renderDisambiguation(intentCheck);
          statusPill?.classList.remove("root"); hidePill();
          return;
        }
        const execRes = await jpost("/api/assistant/execute", {action: intentCheck.action, slots: intentCheck.slots||{}});
        handleAssistantExecResult(execRes, intentCheck);
        statusPill?.classList.remove("root");
        // duplex: re-escucha
        if(state.voiceMode==="duplex" && !speaking && !state.listening) setTimeout(()=> startListening(), 500);
        return;
      }
    }catch(e){ /* fallback a LLM */ }
  }

  // no es intent directo → va a LLM (multimodal)
  // limpiar estado root pill por si venía de intent directo previo fallido
  statusPill?.classList.remove("root");
  let text = rawText;
  const hasFiles = state.attachedFiles.length>0;
  if(!text && !hasFiles) return;
  let sid = state.currentSessionId;
  if(!sid){ sid = await createSession(); if(!sid) return; }
  if(state.currentProject && text.length < 3000 && text && !text.includes(state.currentProject)){
    text = `[contexto proyecto: ${state.currentProject} en /sdcard/projects/${state.currentProject}]\n` + text;
  }
  // build parts con streaming: imágenes Base64 sin límite, docs como texto
  const parts=[{ type:"text", text: text || "(solo archivos adjuntos)" }];
  // análisis docs
  const hint = getExecAnalysisHint(state.attachedFiles);
  if(hint) parts.unshift({ type:"text", text: hint });
  for(const a of state.attachedFiles){
    if(a.b64 && (a.kind==="image" || a.kind==="video" || a.kind==="audio" || a.kind==="pdf" || a.kind==="docx")){
      // opencode espera file parts; usamos type file con base64
      // para imágenes también enviamos como image para compatibilidad
      if(a.kind==="image"){
        parts.push({ type:"image", image: a.b64, mime: a.type, filename: a.name });
      }
      parts.push({ type:"file", mime: a.type, filename: a.name, data: a.b64 });
    } else if(a.text){
      const lang = a.ext==="py" ? "python" : a.ext==="sh" ? "bash" : a.ext==="js" ? "javascript" : a.ext==="cmd" ? "batch" : a.ext;
      const header = a.name ? `Archivo: ${a.name} (${a.kind}/${a.ext}, ${humanSize(a.size)})` : `Adjunto ${a.ext}`;
      parts.push({ type:"text", text: `${header}\n\`\`\`${lang}\n${a.text.slice(0,120000)}\n\`\`\`` });
    } else if(a.b64){
      parts.push({ type:"file", mime: a.type, filename: a.name, data: a.b64 });
    }
  }
  // clear chips
  state.attachedFiles=[]; renderChips(); if(filePicker) filePicker.value="";

  promptEl.value=""; autoGrow();
  addMsg("user", text + (hasFiles ? ` \n[${hasFiles ? (state.attachedFiles.length||0) + " adjuntos" : ""}]` : ""));
  showPill("Pensando…", "thinking");
  const agent = agentSel.value || undefined;
  const ac = new AbortController(); state.abortCtrl = ac;
  // stop listening while thinking? In duplex, keep listening for interruption but pause? duplex keeps listening, push pauses.
  const wasListening = state.listening;
  if(state.voiceMode==="push" && wasListening) stopListening();

  try{
    const body = { parts };
    if(agent) body.agent = agent;
    // streaming via chunks: fetch will chunk automatically (no limit) — server.js hace req.pipe(pr) sin truncar
    const r = await fetch(`/opencode/session/${sid}/message`, { method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify(body), signal: ac.signal });
    if(!r.ok){
      const t = await r.text();
      if(r.status===400 || r.status===422){
        showPill("Reintentando…", "thinking");
        const r2 = await fetch(`/opencode/session/${sid}/message`, { method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify({ parts: [{ text }] }), signal: ac.signal });
        if(!r2.ok) throw new Error(`opencode ${r.status}: ${t.slice(0,600)} | retry ${r2.status}: ${(await r2.text()).slice(0,600)}`);
        const d2 = await r2.json().catch(()=> null);
        handleMessageResponse(d2, t);
      } else throw new Error(`opencode ${r.status}: ${t.slice(0,700)}`);
    } else {
      const data = await r.json().catch(async ()=> ({ raw: await r.text() }));
      handleMessageResponse(data);
    }
  }catch(e){
    if(e.name==="AbortError") { addMsg("system","Envío abortado."); showPill("Abortado", "thinking"); }
    else { addMsg("system","Error enviando: "+String(e).slice(0,800)); showPill("Error enviando", "thinking"); }
  }finally{
    if(btnSend) btnSend.disabled=false; state.abortCtrl=null;
    await refreshSessions();
    // duplex: TTS ya se encargará de re-escuchar al terminar; push: no auto
    if(state.voiceMode==="duplex" && !speaking && !state.listening){
      setTimeout(()=> startListening(), 600);
    }
  }
}
function handleMessageResponse(data, rawFallback){
  if(!data) { showPill("Sin respuesta", "thinking"); return; }
  let text="";
  if(data.parts){ const p=data.parts; if(Array.isArray(p)) text=p.map(x=> x.text||x.content||"").join("\n"); else if(typeof p==="string") text=p; else text=p.text||""; }
  if(!text && data.info) text=data.info.text||data.info.content||"";
  if(!text && data.raw) text=data.raw;
  if(!text && typeof data==="string") text=data;
  if(!text) text=JSON.stringify(data,null,2).slice(0,6000);
  if(text.trim().length<2 && rawFallback) text=rawFallback.slice(0,6000);
  addMsg("assistant", text.slice(0,12000));
}

// ---- agents
async function refreshAgents(){
  try{
    const a = await jget("/opencode/agent");
    const list = Array.isArray(a)? a : (a.agents||a.data||[]);
    state.agents=list;
    agentSel.innerHTML=`<option value="">auto</option>`;
    list.forEach(ag=>{ const id=ag.id||ag.name||String(ag); const lab=ag.name||ag.id||String(ag).slice(0,30); const o=document.createElement("option"); o.value=id; o.textContent=lab; agentSel.appendChild(o); });
  }catch{ agentSel.innerHTML=`<option value="">auto</option>`; }
}

// Arranque delegado exclusivamente a MainActivity.kt (overlay nativo) — web arranca directo en chat sin welcome

// ---- wiring chat — delegated bindings to survive refreshDrawer innerHTML rebuilds (fix 2)
// Use delegated listener on drawer-body for session rows and project rows? Primary buttons are static header elements, but we rebind defensively.
function bindPrimaryButtons() {
  // Remove-and-rebind guard: clone or use {once:false} with dedup via dataset flag
  const bind = (sel, evt, handler) => {
    const el = document.querySelector(sel);
    if (!el || el.dataset.bound === "1") return;
    el.addEventListener(evt, handler);
    el.dataset.bound = "1";
  };
  // Also bind via direct on delegated root as fallback for any late-rendered elements
  bind("#btn-new-session", "click", createSession);
  bind("#btn-send", "click", sendPrompt);
  // Search filter
  const searchEl = document.querySelector("#session-search");
  if (searchEl && searchEl.dataset.bound !== "1") {
    searchEl.addEventListener("input", e=>{
      const q = e.target.value.toLowerCase();
      document.querySelectorAll(".session-row").forEach(r=>{
        const t = r.textContent.toLowerCase();
        r.style.display = t.includes(q) ? "" : "none";
      });
    });
    searchEl.dataset.bound = "1";
  }
  // File + mic bindings (also guarded)
  if (btnAttach && btnAttach.dataset.bound !== "1") {
    btnAttach.addEventListener("click", ()=> filePicker?.click());
    btnAttach.dataset.bound = "1";
  }
  if (btnMic && btnMic.dataset.bound !== "1") {
    btnMic.addEventListener("click", ()=>{
      ensureAudioContext();
      if(state.voiceMode==="duplex"){
        if(state.listening) stopListening();
        else startListening();
      } else {
        if(state.listening) stopListening();
        else startListening();
      }
    });
    btnMic.dataset.bound = "1";
  }
}
// Handle prompt Enter without shift (also guarded)
if (promptEl && promptEl.dataset.bound !== "1") {
  promptEl.addEventListener("keydown", e=>{
    if(e.key==="Enter" && !e.shiftKey){
      e.preventDefault();
      sendPrompt();
    }
  });
  promptEl.dataset.bound = "1";
}
bindPrimaryButtons();
// Also delegate on drawer body for any session rows that might be recreated without explicit onclick (extra safety)
document.getElementById("drawer-body")?.addEventListener("click", e => {
  const row = (e.target instanceof Element) ? e.target.closest(".session-row") : null;
  if (row && row.dataset.sessionId) {
    e.preventDefault();
    const id = row.dataset.sessionId;
    // Let existing row.onclick handle via its own listener; this is fallback only if onclick was lost
    if (!row.dataset.hasHandler) {
      selectSession(id);
    }
  }
});

  // Wire new project form + skills UI before init
setupNewProjectForm();
setupSkillsUI();

// ---- init: carga backend ui-state primero para no sobrescribir (spec 5: projectId + sessionId)
(async ()=>{
  try{
    const ui = await jget("/api/ui/state").catch(()=> null);
    if(ui){
      if(ui.project) state.currentProject = ui.project;
      if(ui.projectId) state.currentProjectId = ui.projectId;
      if(ui.sessionId) state.currentSessionId = ui.sessionId;
      if(ui.project) localStorage.setItem("occ.project", ui.project);
      if(ui.projectId) localStorage.setItem("occ.projectId", ui.projectId);
      if(ui.sessionId) localStorage.setItem("occ.sessionId", ui.sessionId);
    } else {
      state.currentProject = localStorage.getItem("occ.project") || null;
      state.currentProjectId = localStorage.getItem("occ.projectId") || null;
      state.currentSessionId = localStorage.getItem("occ.sessionId") || null;
    }
  }catch{}
  await refreshStatus();
  await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]);
  await refreshSkillsUI();
  if(state.currentSessionId && state.sessions.find(s=> (s.id||s.ID||s.sessionID||s.sessionId)===state.currentSessionId)){
    await selectSession(state.currentSessionId);
  } else if(state.sessions.length){
    const first = state.sessions[0].id || state.sessions[0].ID || state.sessions[0].sessionId;
    if(!state.currentSessionId) persistUi({ sessionId: first });
  }
  setupSSE();
  setInterval(refreshStatus, 15000);
  if(location.protocol==="http:" && location.hostname!=="127.0.0.1" && location.hostname!=="localhost"){
    addMsg("system", "STT chrome requiere HTTPS en LAN http://192.168.x.x — usa APK companion para STT nativo. TTS sí funciona.");
  }
})();
