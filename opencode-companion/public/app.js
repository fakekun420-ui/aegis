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
  currentProject: null,
  agents: [], voices: [],
  lastAssistantText: "",
  abortCtrl: null,
  listening: false, recognition: null,
  attachedFiles: [], // {name,type,size,kind,ext,b64,text,previewUrl}
  voiceMode: localStorage.getItem("occ.voiceMode") || "push", // push | duplex
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
  if("project" in patch){ state.currentProject = patch.project; localStorage.setItem("occ.project", patch.project || ""); }
  if("sessionId" in patch){ state.currentSessionId = patch.sessionId; if(patch.sessionId) localStorage.setItem("occ.sessionId", patch.sessionId); else localStorage.removeItem("occ.sessionId"); }
  jpatch("/api/ui/state", patch).catch(()=>{});
}
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
function persistExpanded(){ localStorage.setItem("occ.expandedProjects", JSON.stringify([...projectExpanded])); }
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
  }catch(e){
    if(standaloneList) standaloneList.innerHTML = `<div class="muted" style="padding:8px">opencode off</div>`;
  }
}
function sessionsForProject(projectName){
  const p = (projectName||"").toLowerCase();
  return state.sessions.filter(s=>{
    const t = String(s.title||s.name||"").toLowerCase();
    const dir = String(s.directory||s.projectID||"").toLowerCase();
    return t.includes(p) || dir.includes(p);
  });
}
function renderDrawer(){
  if(!projectsList || !standaloneList) return;
  projectsList.innerHTML = "";
  const usedIds = new Set();
  state.projects.forEach(proj=>{
    const group = el("div","project-group");
    const isExpanded = projectExpanded.has(proj.name);
    const header = el("button","project-row");
    header.innerHTML = `<span style="flex:1; min-width:0"><span class="project-name">${proj.name}</span><br><span class="project-meta">${proj.hasPackage?"pkg · ":""}${proj.git?"git":""}</span></span><span class="chevron" style="transform: rotate(${isExpanded?180:0}deg)">⌃</span>`;
    header.classList.toggle("active", state.currentProject===proj.name);
    header.setAttribute("aria-expanded", String(isExpanded));
    header.onclick = ()=>{
      state.currentProject = proj.name;
      persistUi({ project: proj.name });
      [...projectsList.querySelectorAll(".project-row")].forEach(n=> n.classList.toggle("active", n===header));
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
      list.appendChild(sessionRowEl(s));
    });
    if(sess.length===0){
      const empty = el("div","muted", "sin chats"); empty.style.padding="4px 8px"; empty.style.fontSize="12px";
      list.appendChild(empty);
    }
    group.appendChild(list);
    projectsList.appendChild(group);
  });
  if(projectsCountEl) projectsCountEl.textContent = String(state.projects.length);
  standaloneList.innerHTML = "";
  const free = state.sessions.filter(s=>{
    const id = s.id||s.ID||s.sessionID||s.sessionId;
    return !usedIds.has(id);
  }).slice(0, 60);
  free.forEach(s=> standaloneList.appendChild(sessionRowEl(s)));
  if(free.length===0){
    standaloneList.innerHTML = `<div class="muted" style="padding:8px; font-size:12px">sin chats sueltos</div>`;
  }
  document.querySelectorAll(".session-row").forEach(r=>{
    r.classList.toggle("active", r.dataset.sessionId===state.currentSessionId);
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
  row.onclick = ()=> { selectSession(id); drawerCtl?.setOpen?.(false); };
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
function setVoiceMode(mode){
  state.voiceMode = mode==="duplex" ? "duplex" : "push";
  localStorage.setItem("occ.voiceMode", state.voiceMode);
  updateVoiceModeLabels();
  // reinit recognition with new continuous flag
  initRecognition();
  if(state.voiceMode==="duplex"){
    showPill("Modo conversación — escucha tras TTS", "thinking");
    // if not listening, start after short delay
    setTimeout(()=> { if(state.voiceMode==="duplex" && !state.listening) startListening(); }, 400);
  } else {
    showPill("Modo texto — pulsar para hablar", "read");
    stopListening();
  }
}
voiceModeToggle?.addEventListener("change", ()=>{
  setVoiceMode(voiceModeToggle.checked ? "duplex" : "push");
});
updateVoiceModeLabels();

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
  try{ speechSynthesis.cancel(); }catch{}
  ttsQueue=[]; speaking=false;
}
function drainTts(){
  if(!ttsQueue.length){ speaking=false; hidePill(); if(state.voiceMode==="duplex"){ setTimeout(()=> { if(state.voiceMode==="duplex" && !state.listening) startListening(); }, 420); } return; }
  speaking=true;
  const chunk=ttsQueue.shift();
  showPill(`🔊 ${chunk.slice(0,42)}… (${ttsQueue.length})`, "thinking");
  const ut=new SpeechSynthesisUtterance(chunk);
  // pick es voice
  const vs=speechSynthesis.getVoices();
  const pref=vs.find(v=> v.lang.toLowerCase().startsWith("es-419")||v.lang.toLowerCase()==="es-us") || vs.find(v=> v.lang.toLowerCase().startsWith("es"));
  if(pref) ut.voice=pref;
  ut.lang= pref?.lang || "es-ES";
  ut.rate=1; ut.pitch=1;
  ut.onend=()=> setTimeout(drainTts, 70);
  ut.onerror=()=> setTimeout(drainTts, 70);
  speechSynthesis.speak(ut);
}
function initVoices(){
  const load=()=>{
    const vs=speechSynthesis.getVoices();
    state.voices=vs;
  };
  load(); speechSynthesis.onvoiceschanged=load;
}
initVoices();

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
  recognition.onend=()=>{
    btnMic?.classList.remove("on");
    state.listening=false;
    const t=finalsBuf.trim();
    finalsBuf="";
    if(t){
      if(state.voiceMode==="duplex"){
        // dúplex: auto-enviar
        promptEl.value = t;
        autoGrow();
        sendPrompt();
      } else {
        // push: transcribir al input, no auto-enviar
        promptEl.value = (promptEl.value ? promptEl.value+" " : "") + t;
        autoGrow(); promptEl.focus(); hidePill();
      }
    } else {
      hidePill();
    }
    // dúplex: re-escucha tras cada turno
    if(state.voiceMode==="duplex" && !speaking){
      setTimeout(()=>{ if(state.voiceMode==="duplex" && !state.listening) startListening(); }, 500);
    }
  };
  recognition.onerror=(e)=>{
    btnMic?.classList.remove("on");
    state.listening=false;
    // no-speech en duplex -> reintenta
    if(e.error==="no-speech" && state.voiceMode==="duplex"){
      setTimeout(()=>{ if(state.voiceMode==="duplex") startListening(); }, 700);
    } else {
      showPill(`STT: ${e.error||"error"}`, "thinking");
      if(state.voiceMode==="duplex") setTimeout(()=> startListening(), 900);
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

// ---- welcome overlay: POST /api/system/start → poll /api/system/status → chat
const welcomeOverlay = $("#welcome-overlay"), btnStartSystem = $("#btn-start-system"), welcomeStatus = $("#welcome-status"), welcomeSteps = $("#welcome-steps");
let welcomePolling=false;
function setWelcome(msg,kind=""){ if(!welcomeStatus) return; welcomeStatus.textContent=msg; welcomeStatus.className="welcome-status "+(kind||""); }
function hideWelcome(){ if(!welcomeOverlay) return; welcomeOverlay.classList.add("out"); setTimeout(()=> welcomeOverlay.classList.add("hidden"), 460); }
function hideWelcomeOverlay(){ hideWelcome(); }
async function checkStatus(){
  try{
    const s = await jget("/api/system/status");
    if(s.ready) { hideWelcomeOverlay(); return s; }
    return s;
  }catch(e){ throw e; }
}
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
    // fallback instantáneo: si /api/system/status ya está 200, oculta overlay sin quedarse esperando
    try{
      const pre = await jget("/api/system/status");
      if(pre.ready){
        setWelcome("✓ Sistema ya listo — entrando…", "ok");
        hideWelcomeOverlay();
        try{ await refreshStatus(); await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]); }catch(_){}
        btnStartSystem.textContent="✓ Iniciado";
        return;
      }
    }catch(_){}
    const r = await fetch("/api/system/start", {method:"POST", headers:{"Content-Type":"application/json"}, body:"{}"});
    const j = await r.json().catch(()=> ({}));
    const steps = Array.isArray(j.steps)? j.steps.join("\n") : "";
    if(welcomeSteps) welcomeSteps.textContent = (steps?steps+"\n\n":"") + `→ HTTP ${r.status} healthy=${j.healthy}\nPolling GET /api/system/status…`;
    if(!r.ok && r.status!==202) throw new Error(j.error || `HTTP ${r.status}`);
    if(r.ok && j.healthy) {
      setWelcome("✓ Sistema listo — entrando…", "ok"); hideWelcomeOverlay();
      try{ await refreshStatus(); await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]); }catch(_){}
      btnStartSystem.textContent="✓ Iniciado";
      // poll en background sin bloquear entrada
      pollSystemStatus().catch(()=>{});
      return;
    }
    setWelcome("Sistema lanzado — verificando…", "busy");
    const readyState = await pollSystemStatus();
    const isReady = readyState?.ready ?? readyState?.healthy ?? j.healthy;
    if(isReady){
      setWelcome("✓ Listo — entrando al chat", "ok"); btnStartSystem.textContent="✓ Iniciado";
      try{ await refreshStatus(); await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]); }catch(_){}
      setTimeout(hideWelcome, 680);
    } else throw new Error("No alcanzó ready:true");
  }catch(e){
    console.error("[btn-start-system] ", e);
    // fallback checkStatus inmediato antes de mostrar error
    try{
      const chk = await checkStatus();
      if(chk && chk.ready){ setWelcome("✓ Listo (fallback) — entrando…", "ok"); hideWelcomeOverlay(); return; }
    }catch(_){}
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
btnSend?.addEventListener("click", sendPrompt);
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
      state.currentProject = localStorage.getItem("occ.project") || null;
      state.currentSessionId = localStorage.getItem("occ.sessionId") || null;
    }
  }catch{}
  await refreshStatus();
  await Promise.all([refreshProjects(), refreshSessions(), refreshAgents()]);
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
