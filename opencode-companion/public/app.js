// opencode-companion app.js — proxy opencode + STT/TTS + device bridge
// sin dependencias, funciona en Chrome Android (Web Speech API) y desktop
const $ = s => document.querySelector(s);
const msgsEl = $("#msgs");
const promptEl = $("#prompt");
const sessionsEl = $("#sessions");
const projectsEl = $("#projects");
const agentSel = $("#agent-select");
const modelPill = $("#model-pill");
const sttState = $("#stt-state");
const micBtn = $("#mic-btn");
const voiceSel = $("#voice-select");
const ttsChip = $("#tts-chip");
const ttsPreview = $("#tts-preview");
const hubStatusEl = $("#hub-status"), ocStatusEl = $("#oc-status");
const dotHub = $("#dot-hub"), dotOc = $("#dot-oc");
const lanPill = $("#lan-pill");
const sysInfo = $("#sys-info");

let state = {
  sessions: [],
  currentSessionId: localStorage.getItem("occ.sessionId") || null,
  currentProject: localStorage.getItem("occ.project") || null,
  agents: [],
  voices: [],
  lastAssistantText: "",
  abortCtrl: null,
  listening: false,
  recognition: null,
  ttsAuto: true,
};

// ---------- helpers ----------
function el(tag, cls, text){ const e=document.createElement(tag); if(cls) e.className=cls; if(text!==undefined) e.textContent=text; return e; }
function addMsg(role, text, meta=""){
  const w = el("div","msg "+role);
  w.textContent = text;
  msgsEl.appendChild(w);
  if(meta){ const m=el("div","meta",meta); msgsEl.appendChild(m); }
  msgsEl.scrollTop = msgsEl.scrollHeight;
  if(role==="assistant"){ state.lastAssistantText = text; ttsPreview.textContent = text.slice(0,1200); if($("#tts-auto").checked) speakQueue(text); }
}
function setDot(elDot, ok){ elDot.className = "dot "+(ok?"ok":"bad"); }
async function jget(url){ const r=await fetch(url); if(!r.ok) throw new Error(url+" -> "+r.status+" "+await r.text().catch(()=>"")); return r.json(); }
async function jpost(url, body){
  const r=await fetch(url,{method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify(body)});
  if(!r.ok) throw new Error(url+" -> "+r.status+" "+await r.text().catch(()=>""));
  const ct=r.headers.get("content-type")||""; return ct.includes("json") ? r.json() : r.text();
}

// ---------- status ----------
async function refreshStatus(){
  try{
    const s = await jget("/api/status");
    const oc = s.opencode;
    const hubOk = s.hub==="ok";
    const ocOk = oc && (oc.healthy || oc.version || oc.providers || Array.isArray(oc));
    hubStatusEl.textContent = hubOk ? `ok :${s.hub_port}` : "off";
    ocStatusEl.textContent = ocOk ? (oc.version ? `v${oc.version}` : "ok") : (oc?.error ? "off" : "off");
    setDot(dotHub, hubOk);
    setDot(dotOc, !!ocOk);
    // LAN
    if(s.projects) $("#projects-count").textContent = s.projects.length;
    // derive LAN ips from hub console? use location.host
    const host = location.hostname;
    if(host!=="127.0.0.1" && host!=="localhost") { lanPill.style.display=""; lanPill.textContent="LAN "+host+":"+location.port; }
    else {
      // try to guess from status root output? not reliable; show hint
      lanPill.style.display="none";
    }
    sysInfo.textContent = `hub :${s.hub_port}  |  opencode ${ocOk? "OK":"NO"} ${oc?.version||""}\nprojects: ${(s.projects||[]).slice(0,12).join(", ")}\n${(s.root||"").slice(0,600)}`;
    // keep oc health in model pill if needed
    if(oc && oc.error) modelPill.textContent = "opencode: no corre — inicia: opencode serve --port 4096 --hostname 0.0.0.0";
    else modelPill.textContent = oc?.version ? `opencode v${oc.version}` : "model: auto";
    return ocOk;
  }catch(e){
    hubStatusEl.textContent="err"; ocStatusEl.textContent="err";
    setDot(dotHub,false); setDot(dotOc,false);
    sysInfo.textContent = "hub no responde: "+String(e).slice(0,800);
    return false;
  }
}

// ---------- projects ----------
async function refreshProjects(){
  try{
    const list = await jget("/api/projects");
    projectsEl.innerHTML="";
    list.forEach(p=>{
      const row = el("div","item"+(state.currentProject===p.name?" active":""));
      row.innerHTML = `<span><b>${p.name}</b> <span style="opacity:.6;font-size:11px">${p.hasPackage?"pkg":""} ${p.git?"git":""}</span></span><span class="chip">${p.path.replace("/sdcard/projects/","")}</span>`;
      row.onclick = ()=>{
        state.currentProject = p.name;
        localStorage.setItem("occ.project", p.name);
        // visual
        [...projectsEl.children].forEach(c=>c.classList.remove("active")); row.classList.add("active");
        addMsg("system", `Proyecto activo: ${p.name}  (${p.path}) — tus prompts ahora se enviarán con contexto de este proyecto.`, new Date().toLocaleTimeString());
        // quick file list
        fetchFind(p.name.slice(0,3)); // warm
      };
      // long-press: shell cd?
      projectsEl.appendChild(row);
    });
    $("#projects-count").textContent = list.length;
  }catch(e){ projectsEl.innerHTML=`<div class="meta">err: ${String(e).slice(0,300)}</div>`; }
}

// ---------- agents ----------
async function refreshAgents(){
  try{
    const a = await jget("/opencode/agent");
    const list = Array.isArray(a) ? a : (a.agents || a.data || []);
    state.agents = list;
    agentSel.innerHTML = `<option value="">agent: auto</option>`;
    list.forEach(ag=>{
      const id = ag.id || ag.name || ag.slug || String(ag);
      const label = ag.name || ag.id || String(ag).slice(0,40);
      const o=document.createElement("option"); o.value=id; o.textContent=label;
      agentSel.appendChild(o);
    });
    if(list.length) modelPill.textContent += ` · ${list.length} agents`;
  }catch(e){ /* opencode maybe off */ agentSel.innerHTML=`<option value="">agent: auto (opencode off)</option>`; }
}

// ---------- sessions ----------
async function refreshSessions(){
  try{
    const data = await jget("/opencode/session");
    const list = Array.isArray(data) ? data : (data.sessions || data.data || []);
    // sort desc by updatedAt/createdAt
    list.sort((a,b)=> new Date(b.updatedAt||b.updated_at||b.createdAt||0) - new Date(a.updatedAt||a.updated_at||a.createdAt||0));
    state.sessions = list;
    renderSessions();
    if(!state.currentSessionId && list.length) selectSession(list[0].id || list[0].ID || list[0].sessionID || list[0].sessionId);
  }catch(e){
    sessionsEl.innerHTML = `<div class="meta">opencode off — no hay sesiones. Inicia: opencode serve --port 4096 --hostname 0.0.0.0<br><span style="font-size:11px;opacity:.7">${String(e).slice(0,400)}</span></div>`;
  }
}
function renderSessions(filter=""){
  const q = filter.toLowerCase();
  sessionsEl.innerHTML="";
  const list = q ? state.sessions.filter(s=> (s.title||s.id||"").toLowerCase().includes(q)) : state.sessions;
  if(!list.length){ sessionsEl.innerHTML=`<div class="meta" style="padding:8px;opacity:.6">sin sesiones${q?" para filtro":""} — crea una nueva</div>`; return; }
  list.slice(0,60).forEach(s=>{
    const id = s.id || s.ID || s.sessionID || s.sessionId;
    const title = s.title || s.name || id.slice(0,8);
    const row = el("div","item"+(state.currentSessionId===id?" active":""));
    row.innerHTML = `<span style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:170px">${title}</span><span class="chip">${(s.model||s.provider||"").slice(0,14) || new Date(s.createdAt||s.created_at||Date.now()).toLocaleDateString()}</span>`;
    row.title = id;
    row.onclick = ()=> selectSession(id);
    // right-click delete
    row.oncontextmenu = async (e)=>{ e.preventDefault(); if(confirm(`Borrar sesión ${title}?`)){ try{ await fetch(`/opencode/session/${id}`,{method:"DELETE"}); await refreshSessions(); }catch(err){ alert(String(err).slice(0,500)); } } };
    sessionsEl.appendChild(row);
  });
}
async function selectSession(id){
  state.currentSessionId = id;
  localStorage.setItem("occ.sessionId", id);
  [...sessionsEl.children].forEach(c=> c.title===id ? c.classList.add("active") : c.classList.remove("active"));
  msgsEl.innerHTML="";
  addMsg("system", `Sesión: ${id} — cargando historial…`, new Date().toLocaleTimeString());
  try{
    const data = await jget(`/opencode/session/${id}/message`);
    const items = Array.isArray(data) ? data : (data.messages || data.data || []);
    msgsEl.innerHTML="";
    if(!items.length) addMsg("system","Sesión vacía — escribe abajo.");
    items.forEach(m=>{
      const info = m.info || m.message || m;
      const parts = m.parts || m.content || [];
      const role = info.role || info.type || (info.id && info.role) || "assistant";
      let text = "";
      if(typeof parts==="string") text=parts;
      else if(Array.isArray(parts)) text = parts.map(p=> p.text || p.content || p.value || (p.type==="text"? p.text:"") || JSON.stringify(p).slice(0,800)).join("\n");
      else if(parts?.text) text=parts.text;
      else text = info.content || info.text || JSON.stringify(m).slice(0,800);
      if(!text.trim()) return;
      const r = role==="user"||role==="human" ? "user" : (role==="system"?"system":"assistant");
      addMsg(r, text.slice(0,8000));
    });
  }catch(e){
    addMsg("system","No se pudo cargar historial: "+String(e).slice(0,600));
  }
}
async function createSession(){
  try{
    const title = state.currentProject ? `companion:${state.currentProject}:${Date.now()%100000}` : `companion:${new Date().toISOString().slice(0,16)}`;
    const s = await jpost("/opencode/session", { title });
    const id = s.id || s.ID || s.sessionId || s.sessionID;
    await refreshSessions();
    if(id) selectSession(id);
    addMsg("system",`Nueva sesión creada: ${id||title}`);
    return id;
  }catch(e){ addMsg("system","Error creando sesión: "+String(e).slice(0,700)); return null; }
}

// ---------- send ----------
async function sendPrompt(textOverride){
  let text = (textOverride ?? promptEl.value).trim();
  if(!text) return;
  // si no hay sesion, crear
  let sid = state.currentSessionId;
  if(!sid){
    sid = await createSession();
    if(!sid) return;
  }
  // contexto proyecto
  if(state.currentProject && !text.includes(state.currentProject)){
    // no inyectar ruido si ya es largo; solo hint sutil via prefix si user quiere? dejamos proyecto como system hint separado
    // enviamos como texto con prefix invisible: [proyecto: X]
    // mejor: enviar dos parts: system hint + user text — pero opencode parts solo soporta text? probamos enviar con prefix
    // para no confundir al modelo, agregamos al inicio una linea corta
    // solo si prompt < 4000 chars
    if(text.length < 3000) text = `[contexto proyecto: ${state.currentProject} en /sdcard/projects/${state.currentProject}]\n` + text;
  }

  promptEl.value="";
  addMsg("user", text);
  $("#btn-send").disabled=true; $("#btn-abort").style.display="";
  $("#stream-status").textContent="enviando a opencode…";
  const agent = agentSel.value || undefined;

  // abort controller
  const ac = new AbortController();
  state.abortCtrl = ac;

  try{
    // primary: POST /opencode/session/:id/message  (sync)
    // Algunos opencode requieren parts: [{type:"text", text}]
    const body = { parts: [{ type:"text", text }] };
    if(agent) body.agent = agent;
    // also try model auto
    const r = await fetch(`/opencode/session/${sid}/message`, {
      method:"POST",
      headers:{"Content-Type":"application/json"},
      body: JSON.stringify(body),
      signal: ac.signal,
    });
    if(!r.ok){
      const t = await r.text();
      // try fallback shape: { message: text }
      if(r.status===400 || r.status===422){
        $("#stream-status").textContent="reintentando con formato alternativo…";
        const r2 = await fetch(`/opencode/session/${sid}/message`, {
          method:"POST", headers:{"Content-Type":"application/json"},
          body: JSON.stringify({ parts: [{ text }] }),
          signal: ac.signal,
        });
        if(!r2.ok) throw new Error(`opencode ${r.status}: ${t.slice(0,700)}  | retry ${r2.status}: ${(await r2.text()).slice(0,700)}`);
        const data2 = await r2.json().catch(()=>null);
        handleMessageResponse(data2, t);
      } else throw new Error(`opencode ${r.status}: ${t.slice(0,800)}`);
    } else {
      const data = await r.json().catch(async ()=> ({ raw: await r.text() }));
      handleMessageResponse(data);
    }
  }catch(e){
    if(e.name==="AbortError") addMsg("system","Envío abortado.");
    else addMsg("system","Error enviando: "+String(e).slice(0,900) + "\n\nVerifica: opencode serve corre en 127.0.0.1:4096 ?  Ejecuta en terminal:  opencode serve --port 4096 --hostname 0.0.0.0 --cors '*'");
    $("#stream-status").textContent = "error — revisa opencode serve";
  }finally{
    $("#btn-send").disabled=false; $("#btn-abort").style.display="none"; state.abortCtrl=null;
    if($("#chk-stream").checked) refreshSessions(); // update list
  }
}
function handleMessageResponse(data, rawFallback){
  if(!data) { $("#stream-status").textContent="sin respuesta"; return; }
  // data could be { info, parts } or { message } or array
  let text="";
  if(data.parts) {
    const p = data.parts;
    if(Array.isArray(p)) text = p.map(x=> x.text || x.content || "").join("\n");
    else if(typeof p==="string") text=p;
    else text = p.text || "";
  }
  if(!text && data.info) text = data.info.text || data.info.content || "";
  if(!text && data.raw) text = data.raw;
  if(!text && typeof data==="string") text=data;
  if(!text && Array.isArray(data)) text = data.map(d=> d.text||"").join("\n");
  if(!text) text = JSON.stringify(data,null,2).slice(0,6000);
  if(text.trim().length < 2 && rawFallback) text = rawFallback.slice(0,6000);
  $("#stream-status").textContent="respuesta recibida";
  addMsg("assistant", text.slice(0,12000));
}

// streaming via SSE (opcional, si el servidor expone /event)
let evtSource=null;
function setupSSE(){
  if(evtSource) evtSource.close();
  if(!$("#chk-stream").checked) return;
  try{
    evtSource = new EventSource("/opencode/event");
    evtSource.onopen = ()=> $("#stream-status").textContent="SSE conectado";
    evtSource.onmessage = e=>{
      try{
        const d = JSON.parse(e.data);
        // si llega evento de mensaje, podríamos append incremental — por ahora log
        if(d.type && d.type.includes("message")) $("#stream-status").textContent = `evento: ${d.type}`;
      }catch{}
    };
    evtSource.onerror = ()=> { $("#stream-status").textContent="SSE desconectado"; evtSource.close(); };
  }catch{}
}

// ---------- file find ----------
async function fetchFind(q){
  if(!q || q.length<2){ $("#find-results").innerHTML=""; return; }
  try{
    const r = await fetch(`/opencode/find/file?query=${encodeURIComponent(q)}&limit=20`);
    if(!r.ok) throw new Error();
    const list = await r.json();
    const arr = Array.isArray(list) ? list : (list.files||list.data||[]);
    $("#find-results").innerHTML="";
    arr.slice(0,20).forEach(p=>{
      const fp = typeof p==="string"? p : (p.path||p.file||JSON.stringify(p));
      const row = el("div","item"); row.textContent=fp; row.title=fp;
      row.onclick = async ()=>{
        try{
          const c = await jget(`/opencode/file/content?path=${encodeURIComponent(fp)}`);
          const txt = c.content || c.text || c.data || JSON.stringify(c).slice(0,2000);
          $("#diff-box").style.display=""; $("#diff-box").textContent = `--- ${fp} ---\n` + String(txt).slice(0,6000);
        }catch(e){ $("#diff-box").style.display=""; $("#diff-box").textContent = "err: "+String(e).slice(0,600); }
      };
      $("#find-results").appendChild(row);
    });
    if(!arr.length) $("#find-results").innerHTML=`<div class="meta" style="padding:6px">sin resultados para "${q}"</div>`;
  }catch(e){ $("#find-results").innerHTML=`<div class="meta">opencode off</div>`; }
}

// ---------- welcome: Iniciar Sistema → POST /api/system/start → poll /api/system/status → chat ----------
const welcomeOverlay = $("#welcome-overlay");
const btnStartSystem = $("#btn-start-system");
const welcomeStatus = $("#welcome-status");
const welcomeSteps = $("#welcome-steps");
let welcomePolling = false;
function setWelcome(msg, kind=""){
  if(!welcomeStatus) return;
  welcomeStatus.textContent = msg;
  welcomeStatus.className = "welcome-status " + (kind||"");
}
function hideWelcome(){
  if(!welcomeOverlay) return;
  welcomeOverlay.classList.add("out");
  setTimeout(()=> welcomeOverlay.classList.add("hidden"), 460);
  setTimeout(()=> { try{ $("#prompt")?.focus(); }catch(_){} }, 620);
}
function showWelcome(){
  if(!welcomeOverlay) return;
  welcomeOverlay.classList.remove("hidden","out");
}
async function pollSystemStatus(maxAttempts=13, interval=2100){
  for(let i=0;i<maxAttempts;i++){
    await new Promise(r=> setTimeout(r, interval));
    try{
      const s = await jget("/api/system/status");
      if(welcomeSteps){
        welcomeSteps.textContent += `\n[poll ${i+1}/${maxAttempts}] ready=${s.ready} opencode_healthy=${s.opencode?.healthy} bridge_a11y=${s.bridge?.a11y}`;
        welcomeSteps.scrollTop = welcomeSteps.scrollHeight;
      }
      if(s.ready) return s;
      setWelcome(`Esperando opencode… (${i+1}/${maxAttempts})`, "busy");
    }catch(e){
      if(welcomeSteps) welcomeSteps.textContent += `\n[poll ${i+1}] err ${String(e).slice(0,140)}`;
    }
  }
  throw new Error(`Timeout ${maxAttempts*interval/1000}s esperando /api/system/status {ready:true}`);
}
async function handleStartSystem(){
  if(welcomePolling || !btnStartSystem) return;
  welcomePolling = true;
  btnStartSystem.disabled = true;
  const origText = btnStartSystem.textContent;
  btnStartSystem.textContent = "Iniciando…";
  setWelcome("Levantando Ubuntu y opencode con nsenter…", "busy");
  if(welcomeSteps){ welcomeSteps.classList.add("show"); welcomeSteps.textContent = "POST /api/system/start …"; }
  try{
    const r = await fetch("/api/system/start", { method:"POST", headers:{ "Content-Type":"application/json" }, body:"{}" });
    const j = await r.json().catch(()=> ({}));
    const steps = Array.isArray(j.steps) ? j.steps.join("\n") : "";
    if(welcomeSteps) welcomeSteps.textContent = (steps ? steps + "\n\n" : "") + `→ HTTP ${r.status} healthy=${j.healthy} alreadyHealthy=${j.alreadyHealthy}\nPolling GET /api/system/status (2.1s)…`;
    if(!r.ok && r.status!==202) throw new Error(j.error || `HTTP ${r.status} ${JSON.stringify(j).slice(0,400)}`);
    if(r.ok && j.healthy){
      setWelcome("✓ Sistema listo — verificando salud…", "busy");
    } else {
      setWelcome("Sistema lanzado — verificando salud (polling)…", "busy");
    }
    const readyState = r.ok && j.healthy ? j : await pollSystemStatus();
    // readyState is either j from POST (if healthy) or s from poll
    const isReady = readyState?.ready ?? readyState?.healthy ?? j.healthy;
    if(isReady){
      setWelcome("✓ Sistema listo — entrando al chat", "ok");
      btnStartSystem.textContent = "✓ Iniciado";
      // refresh main UI before transition so chat has data instantly
      try{ await refreshStatus(); await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]); }catch(_){}
      setTimeout(hideWelcome, 680);
    } else {
      throw new Error("Sistema no alcanzó ready=true");
    }
  }catch(e){
    setWelcome("Error: "+String(e).slice(0,420), "err");
    if(welcomeSteps) welcomeSteps.textContent += `\nERR: ${String(e).slice(0,600)}`;
    btnStartSystem.disabled = false;
    btnStartSystem.textContent = "Reintentar Iniciar Sistema";
  }finally{
    welcomePolling = false;
    if(btnStartSystem && btnStartSystem.textContent==="Iniciando…") btnStartSystem.textContent = origText;
  }
}
if(btnStartSystem){
  btnStartSystem.addEventListener("click", handleStartSystem);
  // hint inicial: chequea si ya está ready para cambiar label
  jget("/api/system/status").then(s=>{
    if(s.ready){
      setWelcome("✓ Sistema ya operativo — toca Iniciar Sistema para entrar al chat", "ok");
      btnStartSystem.textContent = "Entrar al Chat";
      if(welcomeSteps){ welcomeSteps.classList.add("show"); welcomeSteps.textContent = `Pre-check: ready=true opencode v${s.opencode?.version||""} bridge a11y=${s.bridge?.a11y}\nToca el botón para entrar.`; }
    } else {
      setWelcome(`Listo para iniciar — opencode healthy=${s.opencode?.healthy} bridge a11y=${s.bridge?.a11y}`, "");
    }
  }).catch(()=> setWelcome("Toca Iniciar Sistema para levantar Ubuntu + opencode", ""));
}

// ---------- device ----------
async function deviceShell(cmd){
  const log=$("#device-log"); log.style.display=""; log.textContent="⏳ "+cmd+" …";
  try{
    const r = await jpost("/api/device/shell", { cmd });
    log.textContent = `$ ${cmd}\n` + (r.stdout||"") + (r.stderr? "\nERR: "+r.stderr:"") + (r.error? "\n"+r.error:"");
  }catch(e){ log.textContent="err: "+String(e).slice(0,1000); }
}

// ---------- TTS ----------
function initVoices(){
  function load(){
    const vs = speechSynthesis.getVoices();
    state.voices = vs;
    voiceSel.innerHTML="";
    vs.forEach((v,i)=>{
      const o=document.createElement("option"); o.value=i; o.textContent=`${v.name} (${v.lang})${v.default?" ★":""}`;
      voiceSel.appendChild(o);
    });
    // prefer es-419 / es-ES
    let idx = vs.findIndex(v=> v.lang.toLowerCase().startsWith("es-419") || v.lang.toLowerCase()==="es-us");
    if(idx<0) idx = vs.findIndex(v=> v.lang.toLowerCase().startsWith("es"));
    if(idx>=0) voiceSel.value = String(idx);
  }
  load();
  speechSynthesis.onvoiceschanged = load;
  $("#tts-rate").oninput = e=> ttsChip.textContent = `rate ${e.target.value}`;
}
let ttsQueue = [];
let speaking=false;
function speakQueue(text){
  if(!text || !$("#tts-auto").checked) return;
  // split long text into sentences for better UX + allow interim
  const chunks = text.match(/[^.!?¡¿\n]+[.!?¡¿\n]+|[^.!?¡¿\n]+$/g) || [text];
  // if interim disabled, just queue whole? still chunk to avoid 200+ char utterance limits
  const limited = [];
  chunks.forEach(c=>{
    if(c.length>220){
      // split by comma
      c.match(/.{1,200}(?:\s|$)/g)?.forEach(s=> limited.push(s.trim()));
    } else limited.push(c.trim());
  });
  // optional: if interim off, we still queue but user can skip with stop button
  ttsQueue.push(...limited.filter(Boolean));
  if(!speaking) drainTTS();
}
function drainTTS(){
  if(!ttsQueue.length){ speaking=false; ttsChip.textContent="TTS listo"; return; }
  speaking=true;
  const chunk = ttsQueue.shift();
  ttsChip.textContent = `🔊 ${chunk.slice(0,40)}… (${ttsQueue.length} restantes)`;
  const ut = new SpeechSynthesisUtterance(chunk);
  const idx = parseInt(voiceSel.value);
  if(state.voices[idx]) ut.voice = state.voices[idx];
  ut.rate = parseFloat($("#tts-rate").value) || 1;
  ut.pitch = parseFloat($("#tts-pitch").value) || 1;
  ut.lang = ut.voice?.lang || "es-ES";
  ut.onend = ()=> setTimeout(drainTTS, 80);
  ut.onerror = ()=> setTimeout(drainTTS, 80);
  speechSynthesis.speak(ut);
}
function stopTTS(){ speechSynthesis.cancel(); ttsQueue=[]; speaking=false; ttsChip.textContent="TTS detenido"; }

// ---------- STT ----------
function initSTT(){
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if(!SR){
    sttState.textContent="STT: no soportado (usa Chrome)";
    sttState.style.background="#3a2a2a"; return;
  }
  const rec = new SR();
  rec.lang = "es-ES";
  rec.continuous = false;
  rec.interimsResults = true; // webkit uses interimResults
  rec.interimResults = true;
  rec.maxAlternatives = 1;
  state.recognition = rec;

  let finalBuf="";
  let interimBuf="";

  rec.onstart = ()=>{ state.listening=true; micBtn.classList.add("on"); sttState.textContent="STT: escuchando… habla"; sttState.style.background="rgba(239,68,68,.2)"; };
  rec.onend = ()=>{
    state.listening=false; micBtn.classList.remove("on");
    const auto = $("#stt-auto").checked;
    if(finalBuf.trim()){
      // put into prompt or auto-send if ends with "enviar" or "enter"?
      const t = finalBuf.trim();
      // commission: if user said "enviar" at end, auto-send
      const lower = t.toLowerCase();
      if(lower.endsWith("enviar") || lower.endsWith("envía") || lower.endsWith("send")){
        promptEl.value = t.replace(/(enviar|envía|send)\.?$/i,"").trim();
        if(promptEl.value) sendPrompt();
      } else {
        promptEl.value = (promptEl.value ? promptEl.value+" " : "") + t;
        promptEl.focus();
      }
      // TTS echo? no
    } else if(interimBuf.trim()){
      promptEl.value = (promptEl.value ? promptEl.value+" " : "") + interimBuf.trim();
    }
    finalBuf=""; interimBuf="";
    if(auto && !state.listening){
      // restart after short delay (continuous mode)
      setTimeout(()=>{ try{ if($("#stt-auto").checked) rec.start(); }catch{} }, 400);
    } else {
      sttState.textContent="STT: inactivo";
      sttState.style.background="";
    }
  };
  rec.onerror = e=>{
    sttState.textContent=`STT: error ${e.error||""}`;
    // auto-retry on no-speech
    if(e.error==="no-speech" && $("#stt-auto").checked) setTimeout(()=>{ try{ rec.start(); }catch{} }, 800);
  };
  rec.onresult = ev=>{
    let interim="";
    let final="";
    for(let i=ev.resultIndex; i<ev.results.length; i++){
      const res = ev.results[i];
      const txt = res[0].transcript;
      if(res.isFinal) final += txt + " ";
      else interim += txt + " ";
    }
    if(final) finalBuf += final;
    interimBuf = interim;
    if(interim) sttState.textContent = `STT: …${interim.slice(0,60)}`;
    if(final) sttState.textContent = `STT: ✓ ${final.slice(0,60)}`;
  };
}
function toggleMic(){
  const rec = state.recognition;
  if(!rec) return;
  if(state.listening){ try{ rec.stop(); }catch{} }
  else { try{ rec.start(); }catch(e){ sttState.textContent="STT: "+String(e).slice(0,80); } }
}

// ---------- wiring ----------
$("#btn-new-session").onclick = createSession;
$("#btn-status").onclick = async ()=>{ await refreshStatus(); await refreshSessions(); await refreshAgents(); };
$("#btn-clear").onclick = ()=> msgsEl.innerHTML="";
$("#btn-send").onclick = ()=> sendPrompt();
$("#btn-abort").onclick = ()=> state.abortCtrl?.abort();
$("#btn-stop-speech").onclick = stopTTS;
$("#btn-replay").onclick = ()=> { if(state.lastAssistantText) { ttsQueue=[ state.lastAssistantText ]; // replay whole
  // split again
  const t=state.lastAssistantText; ttsQueue=[]; speakQueue(t); } };
$("#btn-copy-last").onclick = async ()=>{ if(state.lastAssistantText) await navigator.clipboard.writeText(state.lastAssistantText); };

promptEl.addEventListener("keydown", e=>{
  if(e.key==="Enter" && (e.shiftKey || e.ctrlKey)){ e.preventDefault(); sendPrompt(); }
});
$("#session-search").addEventListener("input", e=> renderSessions(e.target.value));
$("#find-input").addEventListener("input", e=> fetchFind(e.target.value.trim()));
$("#stt-auto").addEventListener("change", e=>{
  if(e.target.checked && !state.listening) try{ state.recognition?.start(); }catch{}
});
$("#tts-auto").addEventListener("change", e=> state.ttsAuto = e.target.checked);
micBtn.addEventListener("click", toggleMic);
// long-press mic for push-to-talk already via click
document.querySelectorAll("[data-key]").forEach(b=> b.onclick = async ()=>{
  const code=b.getAttribute("data-key");
  await jpost("/api/device/key",{ code: parseInt(code) });
});
$("#btn-launch").onclick = async ()=>{
  const pkg=$("#pkg-input").value.trim(); if(!pkg) return;
  try{ const r=await jpost("/api/device/launch",{ pkg }); $("#device-log").style.display=""; $("#device-log").textContent=JSON.stringify(r,null,2).slice(0,2000); }catch(e){ $("#device-log").style.display=""; $("#device-log").textContent=String(e).slice(0,1000); }
};
$("#btn-shell").onclick = ()=>{ const c=$("#shell-input").value.trim(); if(c) deviceShell(c); };
$("#shell-input").addEventListener("keydown", e=>{ if(e.key==="Enter"){ e.preventDefault(); const c=e.target.value.trim(); if(c) deviceShell(c); }});

// init
(async ()=>{
  initVoices();
  initSTT();
  await refreshStatus();
  await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]);
  setupSSE();
  $("#chk-stream").addEventListener("change", setupSSE);
  // poll status 15s
  setInterval(refreshStatus, 15000);
  // warn secure context for STT
  if(location.protocol==="http:" && location.hostname!=="127.0.0.1" && location.hostname!=="localhost"){
    addMsg("system", "⚠️ STT (micrófono) en Chrome requiere HTTPS o localhost. Si el micrófono no funciona en tu LAN (http://192.168.x.x), abre el hub en https o usa el APK Companion (que usa SpeechRecognizer nativo sin esa restricción). TTS sí funciona en http.");
  }
  addMsg("system", "Atajos: Shift+Enter = enviar · Click micrófono = dictar · Di 'enviar' al final para auto-enviar · /api/device/shell permite a la IA controlar el dispositivo si le das el prompt.");
})();
