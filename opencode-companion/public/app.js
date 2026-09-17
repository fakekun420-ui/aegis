// opencode companion — minimalista Claude/ChatGPT
// drawer + pillow + composer limpio + SSE bidireccional + persistencia backend
const $ = s => document.querySelector(s);
const msgsEl = $("#msgs"), promptEl = $("#prompt");
const projectsList = $("#projects-list"), standaloneList = $("#standalone-list");
const agentSel = $("#agent-select");
const hubStatusEl = $("#hub-status"), ocStatusEl = $("#oc-status"), dotHub = $("#dot-hub"), dotOc = $("#dot-oc");
const sysInfo = $("#sys-info"), hubFoot = $("#hub-pill-foot");
const statusPill = $("#status-pill"), statusPillText = $("#status-pill-text");
const drawer = $("#drawer"), drawerBackdrop = $("#drawer-backdrop"), btnHamburger = $("#btn-hamburger");
const filePicker = $("#file-picker"), attachHint = $("#attach-hint");
const projectsCountEl = $("#projects-count");

let state = {
  sessions: [], projects: [],
  currentSessionId: null,
  currentProject: null,
  agents: [], voices: [],
  lastAssistantText: "",
  abortCtrl: null,
  listening: false, recognition: null,
  attachedFiles: [], // {name, textBase64?}
};

// ---- helpers
function el(tag, cls, text){ const e=document.createElement(tag); if(cls) e.className=cls; if(text!==undefined) e.textContent=text; return e; }
function addMsg(role, text, meta=""){
  const w = el("div","msg "+role); w.textContent = text;
  msgsEl.appendChild(w);
  if(meta){ const m=el("div","muted",meta); m.style.fontSize="11px"; m.style.alignSelf = role==="user" ? "flex-end" : "flex-start"; msgsEl.appendChild(m); }
  msgsEl.scrollTop = msgsEl.scrollHeight;
  if(role==="assistant"){ state.lastAssistantText = text; }
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
  // local fast + backend sin reload
  if("project" in patch){ state.currentProject = patch.project; localStorage.setItem("occ.project", patch.project || ""); }
  if("sessionId" in patch){ state.currentSessionId = patch.sessionId; if(patch.sessionId) localStorage.setItem("occ.sessionId", patch.sessionId); else localStorage.removeItem("occ.sessionId"); }
  jpatch("/api/ui/state", patch).catch(()=>{});
}

// ---- drawer
function setupDrawer(){
  const toggle = (id, force) => {
    const body = document.getElementById(id);
    const btn = document.querySelector(`.section-toggle[data-target="${id}"]`);
    if(!body || !btn) return;
    const collapsed = body.classList.contains("collapsed");
    const nextCollapsed = typeof force === "boolean" ? force : !collapsed;
    // force=false means expand
    const expand = !nextCollapsed;
    // we store collapsed=false when expanded
    if(expand){ body.classList.remove("collapsed"); btn.setAttribute("aria-expanded","true"); }
    else { body.classList.add("collapsed"); btn.setAttribute("aria-expanded","false"); }
  };
  document.querySelectorAll(".section-toggle").forEach(b=>{
    b.addEventListener("click", ()=> toggle(b.dataset.target));
  });
  // hamburger
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
  // close on project/session pick on mobile
  document.addEventListener("keydown", e=>{ if(e.key==="Escape") setOpen(false); });
  return { setOpen, toggle };
}
const drawerCtl = setupDrawer();
function chevronForProject(projectName){
  return `<span class="chevron" aria-hidden="true">⌃</span>`;
}

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
          // mirror to top pills if wizard activity
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
  // also sync pill on fetch: expose showPill for app.js internals
  window.__companionShowPill = showPill;
}

// ---- status bar
async function refreshStatus(){
  try{
    const s = await jget("/api/status");
    const oc = s.opencode;
    const hubOk = s.hub==="ok";
    const ocOk = oc && (oc.healthy || oc.version);
    if(hubStatusEl) hubStatusEl.textContent = hubOk ? `hub :${s.hub_port}` : "hub off";
    if(ocStatusEl) ocStatusEl.textContent = ocOk ? (oc.version ? `v${oc.version}` : "ok") : "off";
    setDot(dotHub, hubOk); setDot(dotOc, !!ocOk);
    if(s.projects && projectsCountEl) projectsCountEl.textContent = String(s.projects.length);
    if(sysInfo) sysInfo.textContent = `hub :${s.hub_port} · opencode ${ocOk ? "OK" : "—"} ${oc?.version||""}\n${(s.root||"").slice(0,280)}`;
    if(hubFoot) hubFoot.textContent = hubOk ? `:${s.hub_port}` : "off";
    return ocOk;
  }catch(e){
    if(hubStatusEl) hubStatusEl.textContent="err"; if(ocStatusEl) ocStatusEl.textContent="err";
    setDot(dotHub,false); setDot(dotOc,false);
    if(sysInfo) sysInfo.textContent = "hub no responde: "+String(e).slice(0,400);
    return false;
  }
}

// ---- projects + sessions in drawer (colapsable proyectos → chats)
let projectExpanded = new Set(JSON.parse(localStorage.getItem("occ.expandedProjects") || "[]"));

function persistExpanded(){
  localStorage.setItem("occ.expandedProjects", JSON.stringify([...projectExpanded]));
}

async function refreshProjects(){
  try{
    const list = await jget("/api/projects");
    state.projects = list;
    renderDrawer();
  }catch(e){
    if(projectsList) projectsList.innerHTML = `<div class="muted" style="padding:8px">err: ${String(e).slice(0,200)}</div>`;
  }
}
async function refreshSessions(){
  try{
    const data = await jget("/opencode/session");
    const list = Array.isArray(data) ? data : (data.sessions || data.data || []);
    list.sort((a,b)=> new Date(b.updatedAt||b.updated_at||b.createdAt||0) - new Date(a.updatedAt||a.updated_at||a.createdAt||0));
    state.sessions = list;
    renderDrawer();
    // no auto-select here: lo hace init() con /api/ui/state
  }catch(e){
    if(standaloneList) standaloneList.innerHTML = `<div class="muted" style="padding:8px">opencode off</div>`;
  }
}

function sessionsForProject(projectName){
  // heurística: title contiene projectName o directory/project-related
  const p = (projectName||"").toLowerCase();
  return state.sessions.filter(s=>{
    const t = String(s.title||s.name||"").toLowerCase();
    const dir = String(s.directory||s.projectID||"").toLowerCase();
    return t.includes(p) || dir.includes(p);
  });
}

function renderDrawer(){
  if(!projectsList || !standaloneList) return;
  // projects section: cada proyecto colapsable
  projectsList.innerHTML = "";
  const standalone = [];
  const usedIds = new Set();

  state.projects.forEach(proj=>{
    const group = el("div","project-group");
    const isExpanded = projectExpanded.has(proj.name);
    const header = el("button","project-row");
    header.innerHTML = `<span style="flex:1; min-width:0"><span class="project-name">${proj.name}</span><br><span class="project-meta">${proj.hasPackage?"pkg · ":""}${proj.git?"git":""}</span></span><span class="chevron" style="transform: rotate(${isExpanded?180:0}deg)">${"⌃"}</span>`;
    header.classList.toggle("active", state.currentProject===proj.name);
    header.setAttribute("aria-expanded", String(isExpanded));
    header.onclick = ()=>{
      state.currentProject = proj.name;
      persistUi({ project: proj.name });
      [...projectsList.querySelectorAll(".project-row")].forEach(n=> n.classList.toggle("active", n===header));
      // toggle expand
      if(projectExpanded.has(proj.name)) projectExpanded.delete(proj.name); else projectExpanded.add(proj.name);
      persistExpanded();
      renderDrawer();
      showPill(`Proyecto: ${proj.name}`, "read");
    };
    group.appendChild(header);

    const list = document.createElement("div");
    list.style.display = isExpanded ? "grid" : "none";
    list.style.gap = "6px";
    const sess = sessionsForProject(proj.name).slice(0, 10);
    sess.forEach(s=>{
      const id = s.id || s.ID || s.sessionID || s.sessionId;
      usedIds.add(id);
      const row = sessionRowEl(s);
      list.appendChild(row);
    });
    if(sess.length===0){
      const empty = el("div","muted", "sin chats"); empty.style.padding="4px 8px"; empty.style.fontSize="12px";
      list.appendChild(empty);
    }
    group.appendChild(list);
    projectsList.appendChild(group);
  });
  if(projectsCountEl) projectsCountEl.textContent = String(state.projects.length);

  // standalone: sesiones no asociadas a proyecto
  standaloneList.innerHTML = "";
  const free = state.sessions.filter(s=>{
    const id = s.id||s.ID||s.sessionID||s.sessionId;
    return !usedIds.has(id);
  }).slice(0, 60);
  free.forEach(s=> standaloneList.appendChild(sessionRowEl(s)));
  if(free.length===0){
    standaloneList.innerHTML = `<div class="muted" style="padding:8px; font-size:12px">sin chats sueltos</div>`;
  }

  // also update active session highlight globally
  document.querySelectorAll(".session-row").forEach(r=>{
    r.classList.toggle("active", r.dataset.sessionId===state.currentSessionId);
  });
  document.querySelectorAll(".project-row").forEach(r=>{
    // active already handled
  });
}

function sessionRowEl(s){
  const id = s.id || s.ID || s.sessionID || s.sessionId;
  const title = s.title || s.name || id.slice(0,8);
  const row = el("button","session-row");
  row.dataset.sessionId = id;
  row.title = id;
  row.innerHTML = `<span class="session-title">${title}</span><span class="session-sub">${(s.model?.id||s.model||"").toString().slice(0,10) || new Date(s.createdAt||Date.now()).toLocaleDateString()}</span>`;
  if(state.currentSessionId===id) row.classList.add("active");
  row.onclick = ()=> {
    selectSession(id);
    drawerCtl?.setOpen?.(false);
  };
  row.oncontextmenu = async (e)=>{ e.preventDefault(); if(confirm(`Borrar sesión ${title}?`)){ try{ await fetch(`/opencode/session/${id}`,{method:"DELETE"}); await refreshSessions(); }catch(err){ alert(String(err).slice(0,400)); } } };
  return row;
}

// ---- sessions load + select (persistencia backend)
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
    if(id) await selectSession(id);
    hidePill();
    return id;
  }catch(e){ addMsg("system","Error creando sesión: "+String(e).slice(0,600)); showPill("Error creando chat", "thinking"); return null; }
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
$("#btn-attach")?.addEventListener("click", ()=> filePicker?.click());
filePicker?.addEventListener("change", async ()=>{
  const files = [...(filePicker.files||[])];
  if(!files.length) return;
  state.attachedFiles = [];
  for(const f of files.slice(0,4)){
    const buf = await f.arrayBuffer().catch(()=> null);
    if(!buf) continue;
    // keep as base64 for image, or text snippet
    const b64 = f.type.startsWith("image/") ? btoa(String.fromCharCode(...new Uint8Array(buf.slice(0, 2_500_000)))) : null;
    state.attachedFiles.push({ name:f.name, type:f.type, size:f.size, b64: b64 || null });
  }
  if(attachHint){
    attachHint.textContent = state.attachedFiles.length ? `adjuntos: ${state.attachedFiles.map(a=> a.name).join(", ")}` : "";
    attachHint.classList.toggle("hidden", !state.attachedFiles.length);
  }
  showPill(state.attachedFiles.length ? `Adjuntos: ${state.attachedFiles.length}` : "Sin adjuntos", "read");
});

// ---- STT (simple, sin etiquetas) — mic hace speech → prompt
let recognition = null;
function initStt(){
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if(!SR) return;
  recognition = new SR();
  recognition.lang = "es-ES";
  recognition.interimResults = false;
  recognition.continuous = false;
  let finals = "";
  recognition.onstart = ()=> { state.listening=true; $("#btn-mic")?.classList.add("on"); showPill("Escuchando…", "thinking"); };
  recognition.onresult = e=>{
    let t=""; for(let i=e.resultIndex;i<e.results.length;i++) if(e.results[i].isFinal) t += e.results[i][0].transcript + " ";
    finals += t;
  };
  recognition.onend = ()=>{
    $("#btn-mic")?.classList.remove("on");
    state.listening=false;
    if(finals.trim()){
      promptEl.value = (promptEl.value ? promptEl.value+" " : "") + finals.trim();
      autoGrow(); hidePill();
    } else {
      hidePill();
    }
    finals="";
  };
  recognition.onerror = ()=> { $("#btn-mic")?.classList.remove("on"); hidePill(); };
}
initStt();
$("#btn-mic")?.addEventListener("click", ()=>{
  if(!recognition) { showPill("STT no soportado en este navegador", "thinking"); return; }
  if(state.listening) { try{ recognition.stop(); }catch{} return; }
  try{ recognition.start(); }catch(e){ showPill(String(e).slice(0,80), "thinking"); }
});

// ---- send
async function sendPrompt(){
  let text = promptEl.value.trim();
  if(!text && !state.attachedFiles.length) return;
  // si no hay sesión, crear
  let sid = state.currentSessionId;
  if(!sid){ sid = await createSession(); if(!sid) return; }
  if(state.currentProject && text.length < 3000 && !text.includes(state.currentProject)){
    text = `[contexto proyecto: ${state.currentProject} en /sdcard/projects/${state.currentProject}]\n` + text;
  }
  // build parts: text + attached images as file parts (opencode handles)
  const parts = [{ type:"text", text }];
  for(const a of state.attachedFiles){
    if(a.b64 && a.type.startsWith("image/")){
      parts.push({ type:"file", mime: a.type, filename: a.name, data: a.b64 });
    } else {
      parts.push({ type:"text", text: `[adjunto ${a.name} — ${a.size} bytes]` });
    }
  }
  state.attachedFiles = []; if(attachHint){ attachHint.textContent=""; attachHint.classList.add("hidden"); } if(filePicker) filePicker.value="";

  promptEl.value=""; autoGrow();
  addMsg("user", text);
  $("#btn-send").disabled=true;
  showPill("Pensando…", "thinking");
  const agent = agentSel.value || undefined;
  const ac = new AbortController(); state.abortCtrl = ac;

  try{
    const body = { parts };
    if(agent) body.agent = agent;
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
    $("#btn-send").disabled=false; state.abortCtrl=null;
    await refreshSessions();
    hidePill();
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

// ---- welcome overlay: POST /api/system/start → poll /api/system/status → chat
const welcomeOverlay = $("#welcome-overlay"), btnStartSystem = $("#btn-start-system"), welcomeStatus = $("#welcome-status"), welcomeSteps = $("#welcome-steps");
let welcomePolling=false;
function setWelcome(msg,kind=""){ if(!welcomeStatus) return; welcomeStatus.textContent=msg; welcomeStatus.className="welcome-status "+(kind||""); }
function hideWelcome(){ if(!welcomeOverlay) return; welcomeOverlay.classList.add("out"); setTimeout(()=> welcomeOverlay.classList.add("hidden"), 460); }
async function pollSystemStatus(maxAttempts=13, interval=2100){
  for(let i=0;i<maxAttempts;i++){
    await new Promise(r=> setTimeout(r, interval));
    try{
      const s = await jget("/api/system/status");
      if(welcomeSteps){ welcomeSteps.textContent += `\n[poll ${i+1}/${maxAttempts}] ready=${s.ready} healthy=${s.opencode?.healthy}`; welcomeSteps.scrollTop = welcomeSteps.scrollHeight; }
      if(s.ready) return s;
      setWelcome(`Esperando opencode… (${i+1}/${maxAttempts})`, "busy");
    }catch(e){ if(welcomeSteps) welcomeSteps.textContent += `\n[poll ${i+1}] err ${String(e).slice(0,120)}`; }
  }
  throw new Error("Timeout esperando /api/system/status ready:true");
}
async function handleStartSystem(){
  if(welcomePolling || !btnStartSystem) return;
  welcomePolling=true; btnStartSystem.disabled=true; const orig=btnStartSystem.textContent; btnStartSystem.textContent="Iniciando…";
  setWelcome("Levantando sistema…", "busy");
  if(welcomeSteps){ welcomeSteps.classList.add("show"); welcomeSteps.textContent="POST /api/system/start …"; }
  try{
    const r = await fetch("/api/system/start", {method:"POST", headers:{"Content-Type":"application/json"}, body:"{}"});
    const j = await r.json().catch(()=> ({}));
    const steps = Array.isArray(j.steps)? j.steps.join("\n") : "";
    if(welcomeSteps) welcomeSteps.textContent = (steps?steps+"\n\n":"") + `→ HTTP ${r.status} healthy=${j.healthy}\nPolling GET /api/system/status…`;
    if(!r.ok && r.status!==202) throw new Error(j.error || `HTTP ${r.status}`);
    if(r.ok && j.healthy) setWelcome("✓ Sistema listo — verificando…", "busy"); else setWelcome("Sistema lanzado — verificando…", "busy");
    const readyState = r.ok && j.healthy ? j : await pollSystemStatus();
    const isReady = readyState?.ready ?? readyState?.healthy ?? j.healthy;
    if(isReady){
      setWelcome("✓ Listo — entrando al chat", "ok"); btnStartSystem.textContent="✓ Iniciado";
      try{ await refreshStatus(); await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]); }catch(_){}
      setTimeout(hideWelcome, 680);
    } else throw new Error("No alcanzó ready:true");
  }catch(e){
    setWelcome("Error: "+String(e).slice(0,400), "err");
    if(welcomeSteps) welcomeSteps.textContent += `\nERR: ${String(e).slice(0,500)}`;
    btnStartSystem.disabled=false; btnStartSystem.textContent="Reintentar Iniciar Sistema";
  }finally{ welcomePolling=false; if(btnStartSystem.textContent==="Iniciando…") btnStartSystem.textContent=orig; }
}
btnStartSystem?.addEventListener("click", handleStartSystem);
jget("/api/system/status").then(s=>{
  if(s.ready){ setWelcome("✓ Sistema operativo — toca para entrar", "ok"); if(btnStartSystem) btnStartSystem.textContent="Entrar al Chat"; if(welcomeSteps){ welcomeSteps.classList.add("show"); welcomeSteps.textContent=`ready=true v${s.opencode?.version||""} bridge a11y=${s.bridge?.a11y}\nToca para entrar.`; } }
  else setWelcome(`Listo para iniciar — healthy=${s.opencode?.healthy}`, "");
}).catch(()=> setWelcome("Toca Iniciar Sistema", ""));

// ---- wiring chat
$("#btn-new-session")?.addEventListener("click", createSession);
$("#btn-send")?.addEventListener("click", sendPrompt);
$("#session-search")?.addEventListener("input", e=>{
  const q = e.target.value.toLowerCase();
  document.querySelectorAll(".session-row").forEach(r=>{
    const t = r.textContent.toLowerCase();
    r.style.display = t.includes(q) ? "" : "none";
  });
});

// ---- init: carga backend ui-state primero para no sobrescribir
(async ()=>{
  try{
    const ui = await jget("/api/ui/state").catch(()=> null);
    if(ui){
      if(ui.project) state.currentProject = ui.project;
      if(ui.sessionId) state.currentSessionId = ui.sessionId;
      if(ui.project) localStorage.setItem("occ.project", ui.project);
      if(ui.sessionId) localStorage.setItem("occ.sessionId", ui.sessionId);
    } else {
      // fallback local
      state.currentProject = localStorage.getItem("occ.project") || null;
      state.currentSessionId = localStorage.getItem("occ.sessionId") || null;
    }
  }catch{}
  // expanded projects restore handled via localStorage (UI only)
  await refreshStatus();
  await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]);
  if(state.currentSessionId && state.sessions.find(s=> (s.id||s.ID||s.sessionID||s.sessionId)===state.currentSessionId)){
    await selectSession(state.currentSessionId);
  } else if(state.sessions.length){
    // keep sessionId from backend if any, else first
    const first = state.sessions[0].id || state.sessions[0].ID || state.sessions[0].sessionId;
    if(!state.currentSessionId) persistUi({ sessionId: first });
  }
  setupSSE();
  setInterval(refreshStatus, 15000);
  if(location.protocol==="http:" && location.hostname!=="127.0.0.1" && location.hostname!=="localhost"){
    addMsg("system", "STT chrome requiere HTTPS en LAN http://192.168.x.x — usa APK companion para STT nativo. TTS sí funciona.");
  }
})();
