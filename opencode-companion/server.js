#!/usr/bin/env node
// opencode-companion hub — proxy opencode + device bridge + TTS/STT host
// Node 18+ only builtins. No npm deps.
// Listens 0.0.0.0:8765 -> serves public/ + /opencode/* proxy + /api/device/*
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { exec, spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// resiliencia: no morir por crash del proxy, pero sí permitir reinicio limpio por keepalive (pkill -f) o por kill -15
process.on('uncaughtException', e => console.error('[hub] uncaughtException', e?.stack || e));
process.on('unhandledRejection', e => console.error('[hub] unhandledRejection', e?.stack || e));
process.on('SIGTERM', () => { console.log('[hub] SIGTERM — cierre limpio (keepalive relanza)'); try { server.close(() => process.exit(0)); } catch (_) { process.exit(0); } setTimeout(()=> process.exit(0), 2000); });
process.on('SIGINT',  () => { console.log('[hub] SIGINT — cierre');  try { server.close(() => process.exit(0)); } catch (_) { process.exit(0); } setTimeout(()=> process.exit(0), 2000); });
process.on('SIGPIPE', () => console.log('[hub] SIGPIPE ignorado'));

function argVal(name, fallback){
  const i = process.argv.indexOf(name);
  return i !== -1 && process.argv[i+1] ? process.argv[i+1] : fallback;
}
const HUB_PORT = parseInt(process.env.HUB_PORT || argVal("--port","8765"), 10);
const OPENCODE_PORT = parseInt(process.env.OPENCODE_PORT || argVal("--opencode-port","4096"), 10);
const OPENCODE_HOST = process.env.OPENCODE_HOST || "127.0.0.1";
const PROJECTS_ROOT = "/sdcard/projects";
const UI_STATE_FILE = path.join(__dirname, "ui-state.json");
const UI_STATE = (() => {
  try { if (fs.existsSync(UI_STATE_FILE)) return JSON.parse(fs.readFileSync(UI_STATE_FILE, "utf8")); } catch {}
  return { project: null, sessionId: null, updatedAt: 0 };
})();
function saveUiState() {
  try { fs.writeFileSync(UI_STATE_FILE, JSON.stringify({ ...UI_STATE, updatedAt: Date.now() }, null, 2)); } catch (e) { console.error("[ui-state] save err", e.message); }
}

const MIME = {
  ".html":"text/html; charset=utf-8", ".js":"text/javascript; charset=utf-8",
  ".css":"text/css; charset=utf-8", ".json":"application/json; charset=utf-8",
  ".png":"image/png", ".svg":"image/svg+xml", ".ico":"image/x-icon",
  ".wav":"audio/wav", ".mp3":"audio/mpeg",
};

function send(res, code, body, headers={}){
  res.writeHead(code, { "Access-Control-Allow-Origin":"*", "Access-Control-Allow-Headers":"*", "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS", ...headers });
  res.end(body);
}
function json(res, code, obj){ send(res, code, JSON.stringify(obj), {"Content-Type":"application/json; charset=utf-8"}); }

function proxyToOpencode(req, res){
  const targetPath = req.url.replace(/^\/opencode/, "") || "/";
  // streaming sin límite ni truncamiento: reenvía body por chunks (64KB) con backpressure
  // soporta payloads multimodales grandes (JPG/PNG/WEBP, MP4, WAV/MP3, PDF/DOCX, .cmd/.sh/.py) en Base64
  const opts = { hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: targetPath, method: req.method, headers: { ...req.headers, host: `${OPENCODE_HOST}:${OPENCODE_PORT}` } };
  delete opts.headers["accept-encoding"];
  // preservar Content-Length si existe; si no, chunked (sin límite)
  const contentLength = req.headers["content-length"] ? parseInt(req.headers["content-length"]) : null;
  if(contentLength && contentLength > 0){
    console.log(`[proxy] ${req.method} ${targetPath} streaming ${Math.round(contentLength/1024)}KB via chunks`);
  } else if(req.method==="POST" || req.method==="PUT" || req.method==="PATCH"){
    console.log(`[proxy] ${req.method} ${targetPath} streaming chunked (sin Content-Length)`);
  }
  const guard = setTimeout(() => {
    if (!res.headersSent) {
      try { json(res, 502, { error: 'opencode timeout', hint: `opencode serve no respondió en 8s en ${OPENCODE_HOST}:${OPENCODE_PORT}` }); } catch (_) {}
    }
    try { pr.destroy(); } catch (_) {}
  }, 8000);
  const pr = http.request(opts, (prRes)=>{
    clearTimeout(guard);
    const h = { ...prRes.headers, "Access-Control-Allow-Origin":"*", "Access-Control-Allow-Headers":"*", "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS" };
    if (!res.headersSent) res.writeHead(prRes.statusCode, h);
    prRes.on('error', e => { console.error('[proxy] prRes error', e.message); try { res.destroy(); } catch (_) {} });
    res.on('error', e => { console.error('[proxy] res error', e.message); try { pr.destroy(); } catch (_) {} });
    req.on('aborted', () => { try { pr.destroy(); } catch (_) {} });
    prRes.pipe(res);
  });
  pr.on("error", e=> {
    clearTimeout(guard);
    console.error('[proxy] error', e.message);
    if(!res.headersSent) {
      try { json(res, 502, { error:"opencode unreachable", detail:String(e), hint:`opencode serve debe estar corriendo en ${OPENCODE_HOST}:${OPENCODE_PORT}. Ejecuta: opencode serve --port ${OPENCODE_PORT} --hostname 0.0.0.0  ó  opencode web --port ${OPENCODE_PORT} --hostname 0.0.0.0` }); } catch(_){}
    } else try{ res.end(); }catch(_){}
  });
  req.on('error', e => { clearTimeout(guard); console.error('[proxy] req error', e.message); try { pr.destroy(); } catch (_) {} });
  // stream por chunks con backpressure — sin límite de tamaño, sin truncar Base64
  try { req.pipe(pr); } catch (e) { clearTimeout(guard); console.error('[proxy] pipe err', e.message); }
  pr.setTimeout(120000, ()=> { clearTimeout(guard); console.error('[proxy] timeout 120s (payload grande)'); try{ pr.destroy(); }catch(_){} });
  res.setTimeout(130000, () => { clearTimeout(guard); console.error('[proxy] res timeout 130s'); try { res.destroy(); } catch (_) {} });
}

// device helpers — portable + Android namespace aware
// En este POCO F3, los comandos Android solo funcionan vía nsenter -t 1 -m (monkey también necesita nsenter)
const ANDROID_CMDS = /\b(pm|input|am|getprop|uiautomator|cmd|dumpsys|settings|monkey)\b/;
const MAX_BUFFER = 50 * 1024 * 1024; // 50 MB para screenshots Base64 1080p/4K
const MAX_JSON_BODY = 55 * 1024 * 1024; // 55 MB límite JSON entrante

function shellExecRaw(cmd, timeout=12000, maxBuffer = MAX_BUFFER){
  return new Promise((resolve)=>{
    exec(cmd, { timeout, maxBuffer }, (err, stdout, stderr)=>{
      // si excede maxBuffer, err.message contiene "maxBuffer"
      if(err && String(err.message).includes("maxBuffer")){
        resolve({ code: 1, stdout: String(stdout||"").slice(0, maxBuffer), stderr: String(stderr||"") + "\n[hub] maxBuffer " + (maxBuffer/1024/1024) + "MB excedido — salida truncada. Usa streaming o reduce calidad.", signal: err.signal || null, killed: !!err.killed, error: err.message, truncated: true });
      } else {
        resolve({ code: err?.code ?? 0, stdout: String(stdout||""), stderr: String(stderr||""), signal: err?.signal || null, killed: !!err?.killed, error: err ? String(err.message) : null });
      }
    });
  });
}
function readJsonBody(req, limit = MAX_JSON_BODY){
  return new Promise((resolve, reject)=>{
    const chunks=[];
    let total=0;
    let aborted=false;
    req.on("data", c=>{
      total += c.length;
      if(total > limit && !aborted){
        aborted=true;
        reject(new Error("JSON body excede " + (limit/1024/1024) + "MB — reduce imagen o usa compresión"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", ()=>{ try{ resolve(Buffer.concat(chunks).toString("utf8")); }catch(e){ reject(e); } });
    req.on("error", reject);
  });
}
async function runShell(cmd, timeout=12000){
  // si es comando Android y existe nsenter, envolver
  const needsNs = ANDROID_CMDS.test(cmd);
  if(needsNs){
    // prueba nsenter primero (más rápido que su)
    const wrapped = `nsenter -t 1 -m -- sh -c ${JSON.stringify(cmd)} 2>&1`;
    const r = await shellExecRaw(wrapped, timeout);
    // si nsenter no existe o falló con "nsenter: not found", fallback a su/sh directo
    if(r.stdout.includes("nsenter:") || r.stderr.includes("nsenter:") || (r.code===127 && r.stdout==="")){
      const fallback = `su -c ${JSON.stringify(cmd)} 2>&1 || sh -c ${JSON.stringify(cmd)} 2>&1`;
      return shellExecRaw(fallback, timeout);
    }
    return r;
  }
  // comando genérico linux: intenta su luego sh
  const wrapped = `su -c ${JSON.stringify(cmd)} 2>&1 || sh -c ${JSON.stringify(cmd)} 2>&1`;
  return shellExecRaw(wrapped, timeout);
}
async function listProjects(){
  try{
    const entries = fs.readdirSync(PROJECTS_ROOT, {withFileTypes:true});
    return entries.filter(d=>d.isDirectory() && !d.name.startsWith(".")).map(d=>d.name);
  }catch{ return []; }
}

const server = http.createServer(async (req, res)=>{
  // timeouts largos para payloads multimodales grandes (video/audio/docs en Base64)
  req.setTimeout(125000, () => { console.error('[hub] req timeout 125s (multimodal)', req.url?.slice(0,140)); try { res.destroy(); } catch (_) {} });
  res.on('close', () => { /* cleanup */ });
  if(req.method==="OPTIONS"){ return send(res, 204, ""); }
  let url;
  try { url = new URL(req.url, `http://${req.headers.host}`); } catch (e) { return json(res, 400, { error: 'bad url', detail: String(e) }); }
  const pathname = url.pathname;

  // 1) opencode proxy
  if(pathname.startsWith("/opencode/") || pathname==="/opencode"){
    return proxyToOpencode(req, res);
  }
  // SSE passthrough convenience: /event and /global/event also proxied? keep alias
  if(pathname==="/event" || pathname==="/global/event"){
    req.url = "/opencode" + pathname;
    return proxyToOpencode(req, res);
  }

  // 2) API hub
  if(pathname==="/api/system/status" && req.method==="GET"){
    const ocHealth = await new Promise(resolve=>{
      http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path:"/global/health", timeout:2000 }, r=>{
        let d=""; r.on("data",c=>d+=c); r.on("end",()=>{ try{ const j=JSON.parse(d); resolve({ up: !!j.healthy, version: j.version || null, healthy: !!j.healthy }); }catch{ resolve({ up: r.statusCode===200, healthy: false }) } });
      }).on("error", ()=> resolve({ up:false, healthy:false })).end();
    });
    const bridgeHealth = await new Promise(resolve=>{
      http.get({ hostname:"127.0.0.1", port:8766, path:"/status", timeout:1500 }, r=>{
        let d=""; r.on("data",c=>d+=c); r.on("end",()=>{ try{ const j=JSON.parse(d); resolve({ up:true, a11y: !!j.a11y }); }catch{ resolve({ up:true, a11y:false }) } });
      }).on("error", ()=> resolve({ up:false, a11y:false })).end();
    });
    const ready = ocHealth.healthy && ocHealth.up;
    return json(res, 200, { ready, hub:`http://127.0.0.1:${HUB_PORT}`, opencode: ocHealth, bridge: bridgeHealth, hint: ready ? "ready" : "starting or opencode not healthy — POST /api/system/start relanza" });
  }
  if(pathname==="/api/system/start" && req.method==="POST"){
    await readJsonBody(req, 64*1024).catch(()=> "{}");
    console.log('[system] POST /api/system/start — invocando keepalive + opencode ensure (host namespace)');
    const steps = [];
    async function ensureOpencode(){
      const health = await new Promise(resolve=>{
        http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path:"/global/health", timeout:2000 }, r=>{
          let d=""; r.on("data",c=>d+=c); r.on("end",()=>{ try{ const j=JSON.parse(d); resolve(j.healthy===true);}catch{ resolve(false) }});
        }).on("error", ()=> resolve(false)).end();
      });
      if(health) { steps.push("opencode: already healthy"); return { already:true }; }
      steps.push("opencode: not healthy — launching via host keepalive.sh");
      // keepalive.sh y opencode deben lanzarse en HOST (donde existe /usr/bin/node), no en system (nsenter -t 1 -m no ve /usr/bin/node)
      const r1 = await shellExecRaw(`nohup sh /sdcard/projects/opencode-companion/keepalive.sh > /sdcard/projects/opencode-companion/keepalive.log 2>&1 & echo keepalive_pid=$!`, 8000, 1024*1024);
      steps.push(`keepalive.sh: ${r1.stdout.trim().slice(0,300)} ${r1.stderr.trim().slice(0,200)}`);
      // intento directo inmediato por si keepalive tarda 10s
      await shellExecRaw(`nohup opencode serve --port ${OPENCODE_PORT} --hostname 0.0.0.0 >> /sdcard/projects/opencode-companion/opencode.log 2>&1 & echo opencode_direct_pid=$!`, 8000, 1024*1024).then(r=> steps.push(`opencode direct: ${r.stdout.trim().slice(0,300)} ${r.stderr.trim().slice(0,200)}`)).catch(e=> steps.push(`opencode direct err ${String(e).slice(0,200)}`));
      await runShell(`dumpsys deviceidle whitelist +com.opencode.companion 2>&1 | head -n 3; cmd deviceidle whitelist +com.opencode.companion 2>&1 | head -n 3; am set-standby-bucket com.opencode.companion active 2>&1 | head -n 3`).then(r=> steps.push(`whitelist: ${r.stdout.trim().slice(0,200)}`)).catch(()=>{});
      return { already:false };
    }
    const ens = await ensureOpencode();
    // poll hasta healthy o timeout 25s
    let healthy=false;
    for(let i=0;i<12;i++){
      await new Promise(r=> setTimeout(r, 2100));
      const h = await new Promise(resolve=>{
        http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path:"/global/health", timeout:2000 }, rr=>{
          let d=""; rr.on("data",c=>d+=c); rr.on("end",()=>{ try{ const j=JSON.parse(d); resolve(!!j.healthy);}catch{ resolve(false)}});
        }).on("error", ()=> resolve(false)).end();
      });
      if(h){ healthy=true; break; }
    }
    steps.push(healthy ? "poll: healthy after retry" : "poll: timeout 25s not healthy");
    return json(res, healthy ? 200 : 202, { started: true, healthy, opencode:`http://${OPENCODE_HOST}:${OPENCODE_PORT}`, steps, alreadyHealthy: ens.already, next:"Poll GET /api/system/status until {ready:true} or GET /api/status for detail" });
  }
  if(pathname==="/api/ui/state" && req.method==="GET"){
    return json(res, 200, { ...UI_STATE });
  }
  if(pathname==="/api/ui/state" && req.method==="PATCH"){
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      if ("project" in body) UI_STATE.project = body.project || null;
      if ("sessionId" in body) UI_STATE.sessionId = body.sessionId || null;
      saveUiState();
      return json(res, 200, { ...UI_STATE });
    } catch (e) { return json(res, 400, { error: String(e).slice(0,400) }); }
  }
  if(pathname==="/api/status" && req.method==="GET"){
    const rootCheck = await runShell("id; su -c id 2>&1 | head -1; getprop ro.build.version.release 2>&1; getprop ro.product.model 2>&1");
    // probe opencode
    const health = await new Promise(resolve=>{
      http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path:"/global/health", timeout:2000 }, r=>{
        let d=""; r.on("data",c=>d+=c); r.on("end",()=>{ try{ resolve(JSON.parse(d)); }catch{ resolve({ raw:d, status:r.statusCode })} });
      }).on("error", e=> resolve({ error:String(e), reachable:false })).end();
    });
    return json(res, 200, { hub:"ok", hub_port: HUB_PORT, opencode: health, projects_root: PROJECTS_ROOT, projects: await listProjects(), root: rootCheck.stdout.slice(0,1200) });
  }
  if(pathname==="/api/projects" && req.method==="GET"){
    const dirs = await listProjects();
    const infos = dirs.map(name=>{
      try{
        const p = path.join(PROJECTS_ROOT, name);
        const pkg = fs.existsSync(path.join(p,"package.json")) ? JSON.parse(fs.readFileSync(path.join(p,"package.json"),"utf8")) : null;
        const git = fs.existsSync(path.join(p,".git"));
        return { name, path:p, hasPackage:!!pkg, description: pkg?.description||"", git };
      }catch{ return { name, path: path.join(PROJECTS_ROOT,name) }; }
    });
    return json(res, 200, infos);
  }
  if(pathname==="/api/device/shell" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, MAX_JSON_BODY);
      const { cmd, timeout, maxBuffer } = JSON.parse(raw||"{}");
      if(!cmd) return json(res, 400, { error:"cmd requerido" });
      console.log(`[shell] ${cmd.slice(0,400)} (maxBuffer ${((maxBuffer||MAX_BUFFER)/1024/1024).toFixed(1)}MB)`);
      const out = await runShell(cmd, timeout || 20000, maxBuffer || MAX_BUFFER);
      json(res, 200, out);
    }catch(e){ json(res, 500, { error:String(e), truncated: String(e).includes("maxBuffer") }); }
    return;
  }
  if(pathname==="/api/device/launch" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { pkg, activity } = JSON.parse(raw||"{}"); if(!pkg) return json(res, 400, { error:"pkg requerido ej: com.bcp.bo.wallet" }); const cmd = activity ? `am start -n ${pkg}/${activity}` : `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${pkg} 2>&1 || monkey -p ${pkg} -c android.intent.category.LAUNCHER 1 2>&1 || cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${pkg} 2>&1`; const out = await runShell(cmd); json(res, 200, { cmd, ...out }); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/tap" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { x, y } = JSON.parse(raw||"{}"); const out = await runShell(`input tap ${parseInt(x)} ${parseInt(y)}`); json(res, 200, out); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/input" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { text } = JSON.parse(raw||"{}"); const esc = String(text||"").replace(/ /g,"%s").replace(/&/g,"\\&"); const out = await runShell(`input text ${esc}`); json(res,200,out); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/key" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { code } = JSON.parse(raw||"{}"); const out = await runShell(`input keyevent ${parseInt(code)}`); json(res,200,out); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/apps" && req.method==="GET"){
    const q = url.searchParams.get("q") || "";
    const out = await runShell(`pm list packages ${q ? `-3 | grep -i ${JSON.stringify(q)}` : ""} 2>&1 | head -n 200; pm list packages -3 2>&1 | head -n 200`);
    // parse
    const pkgs = out.stdout.split("\n").filter(l=>l.includes("package:")).map(l=>l.replace("package:","").trim()).slice(0,200);
    return json(res, 200, { pkgs, raw: out.stdout.slice(0,4000) });
  }
  // a11y + streaming-friendly (reenvía Base64 grande sin truncar)
  if(pathname==="/api/device/a11y" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, MAX_JSON_BODY);
      const body = JSON.parse(raw||"{}");
      // forwarding con buffer grande: usa bytes completos
      const bodyBytes = Buffer.from(raw, "utf8");
      const fwd = await new Promise(resolve=>{
        const pr = http.request({ hostname:"127.0.0.1", port:8766, path:"/a11y", method:"POST", headers:{"Content-Type":"application/json", "Content-Length": bodyBytes.length } }, r=>{
          const chunks=[]; r.on("data",c=>chunks.push(c)); r.on("end",()=> resolve({ ok:true, status:r.statusCode, body: Buffer.concat(chunks).toString("utf8") }));
        });
        pr.on("error", e=> resolve({ ok:false, error:String(e) }));
        pr.write(bodyBytes); pr.end();
      });
      if(fwd.ok) return send(res, fwd.status, fwd.body, {"Content-Type":"application/json"});
      if(body.action==="dump"){
        const out = await runShell(`uiautomator dump /sdcard/window_dump.xml && cat /sdcard/window_dump.xml 2>&1 | head -n 800`, 15000, MAX_BUFFER);
        return json(res, 200, { fallback:"uiautomator", ...out, note:"Para clicks por texto/id instala el APK Companion con AccessibilityService" });
      }
      return json(res, 501, { error:"APK Companion no conectado (puerto 8766). Instala el APK para control por AccessibilityService.", requested: body, forwardError: fwd.error });
    }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  // nuevo: screenshot Base64 streaming-safe — PNG 1080x2400 ~ 800KB-2MB b64, 50MB buffer sobrado
  if(pathname==="/api/device/screenshot" && req.method==="GET"){
    const quality = Math.max(10, Math.min(95, parseInt(url.searchParams.get("quality")||"35")));
    const scale = Math.max(0.2, Math.min(1.0, parseFloat(url.searchParams.get("scale")||"0.5")));
    // pipe directo sin head -c truncador intermedio — maxBuffer controla el límite real
    const cmd = `nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`;
    const out = await runShell(cmd, 25000, MAX_BUFFER);
    const b64 = out.stdout.trim().replace(/\s/g,"");
    if(!b64 || b64.length < 1000){
      return json(res, 500, { error:"screenshot vacío", stdout: out.stdout.slice(0,800), stderr: out.stderr.slice(0,800), code: out.code });
    }
    if(url.searchParams.get("raw")==="1") return send(res, 200, b64, {"Content-Type":"text/plain", "Content-Length": String(Buffer.byteLength(b64))});
    return json(res, 200, { b64, len: b64.length, approx_bytes: Math.floor(b64.length*0.75), quality, scale, note: "b64 completo sin truncar (MAX_BUFFER 50MB). Para ahorrar tokens pasa ?quality=25&scale=0.4 y recorta cliente-side." });
  }
  if(pathname==="/api/device/screenshot" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 2*1024*1024);
      const opts = JSON.parse(raw||"{}");
      const out = await runShell(`nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`, 25000, MAX_BUFFER);
      const b64 = out.stdout.trim().replace(/\s/g,"");
      return json(res, 200, { b64, len: b64.length, opts });
    }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }

  // 2.5) Asistente Root: interpretar intención → acción NSENTER/8766
  // Vocabulario directo sin pasar por LLM si la orden ya es comando del sistema
  const DIRECT_INTENT = [
    { re: /^abre\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^abrir\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^inicia\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^lanza\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^(toma|haz|saca)\s+(una\s+)?captura(\s+de\s+pantalla)?/i, action: "screenshot" },
    { re: /^captura(\s+de\s+pantalla)?/i, action: "screenshot" },
    { re: /^(sube|aumenta)\s+(el\s+)?volumen/i, action: "volume_up" },
    { re: /^(baja|disminuye)\s+(el\s+)?volumen/i, action: "volume_down" },
    { re: /^(silencia|mutea|silenciar)/i, action: "volume_mute" },
    { re: /^(bloquea|bloquear)\s+(la\s+)?pantalla/i, action: "lock_screen" },
    { re: /^(desbloquea|desbloquear)/i, action: "unlock_screen" },
    { re: /^(activa|enciende|prende)\s+(el\s+)?(wifi|wi-?fi)/i, action: "wifi_on" },
    { re: /^(desactiva|apaga)\s+(el\s+)?(wifi|wi-?fi)/i, action: "wifi_off" },
    { re: /^(activa|enciende)\s+(el\s+)?bluetooth/i, action: "bluetooth_on" },
    { re: /^(desactiva|apaga)\s+(el\s+)?bluetooth/i, action: "bluetooth_off" },
    { re: /^pon\s+alarma/i, action: "set_alarm" },
    { re: /^llama\s+a\s+(.+)/i, action: "call", slot: "contact" },
    { re: /^manda\s+(?:un\s+)?mensaje\s+a\s+(.+?)\s*[:\-]\s*(.+)/i, action: "whatsapp_send", slots: ["contact","text"] },
    { re: /^(manda|env[ií]a)\s+(?:un\s+)?(?:whatsapp|mensaje)\s+a\s+(.+?)\s*[:\-]\s*(.+)/i, action: "whatsapp_send", slots: ["contact","text"] },
    { re: /^(manda|env[ií]a)\s+(?:un\s+)?(?:whatsapp|mensaje)\s+a\s+(.+)/i, action: "whatsapp_send_prompt", slot: "contact" },
  ];
  const APP_ALIASES = {
    "whatsapp": "com.whatsapp",
    "wa": "com.whatsapp",
    "telegram": "org.telegram.messenger",
    "youtube": "com.google.android.youtube",
    "chrome": "com.android.chrome",
    "camara": "com.android.camera2",
    "cámara": "com.android.camera2",
    "galeria": "com.google.android.apps.photos",
    "galería": "com.google.android.apps.photos",
    "fotos": "com.google.android.apps.photos",
    "yape": "com.bcp.bo.wallet",
    "bcp": "com.bcp.bo.wallet",
    "proton": "ch.protonmail.android",
    "protonmail": "ch.protonmail.android",
    "gmail": "com.google.android.gm",
    "maps": "com.google.android.apps.maps",
    "spotify": "com.spotify.music",
    "facebook": "com.facebook.katana",
    "instagram": "com.instagram.android",
    "tiktok": "com.zhiliaoapp.musically",
  };
  function directIntentOf(text){
    const t = String(text||"").trim();
    for(const pat of DIRECT_INTENT){
      const m = t.match(pat.re);
      if(m){
        const slots={};
        if(pat.slot) slots[pat.slot] = (m[1]||m[m.length-1]||"").trim();
        if(pat.slots) pat.slots.forEach((k,i)=> slots[k] = (m[i+1]||"").trim());
        return { action: pat.action, slots, raw: t, via: "direct_regex" };
      }
    }
    return null;
  }
  function appPackageFor(name){
    const k = String(name||"").toLowerCase().trim();
    if(!k) return null;
    if(k.includes(".")) return k;
    if(APP_ALIASES[k]) return APP_ALIASES[k];
    for(const [alias,pkg] of Object.entries(APP_ALIASES)){
      if(k.includes(alias)) return pkg;
    }
    return k.replace(/\s+/g, ".");
  }

  // Contactos: query via content provider (READ_CONTACTS) si el permiso existe; fallback vacío
  async function queryContacts(q){
    if(!q || q.trim().length<2) return [];
    // content query contacts — nsenter necesario
    const safe = q.replace(/'/g, "''").slice(0,60);
    const cmd = `content query --uri content://com.android.contacts/contacts --where "display_name LIKE '%${safe}%'" --projection display_name:phone 2>&1 | head -n 20`;
    const out = await runShell(cmd, 8000, 1024*1024);
    // parse líneas tipo "Row: 3 display_name=Juan, phone=..." — heurística
    const lines = out.stdout.split("\n").filter(l=> l.includes("display_name") || l.includes("Row:"));
    const hits=[];
    for(const line of lines){
      const nameMatch = line.match(/display_name=([^,]+)/i);
      const rowMatch = line.match(/Row:\s*\d+\s*([^,=]+)/);
      const name = (nameMatch?.[1] || rowMatch?.[1] || "").trim();
      // intenta obtener teléfono en segunda query si name hallado
      if(name && name.length>1){
        const cmd2 = `content query --uri content://com.android.contacts/data --where "display_name='${name.replace(/'/g,"''")}'" 2>&1 | grep -i -oE "\\+?[0-9][0-9 \\-]{6,}[0-9]" | head -n 3`;
        const out2 = await runShell(cmd2, 6000, 1024*1024);
        const phones = out2.stdout.split("\n").map(s=> s.replace(/[\s\-]/g,"").trim()).filter(s=> s.length>=8);
        hits.push({ name, phones: [...new Set(phones)].slice(0,2), source: "contacts" });
      }
      if(hits.length>=5) break;
    }
    // si no hubo hits por content, al menos devuelve el q como contacto tentativo
    if(hits.length===0){
      const normalized = q.replace(/[^+0-9a-zA-Z ]/g,"").trim();
      if(/^\+?[0-9 ]{8,}$/.test(normalized)) hits.push({ name: normalized, phones:[normalized.replace(/ /g,"")], source:"raw_phone" });
    }
    return hits.slice(0,5);
  }

  async function executeAssistantAction(action, slots={}, opts={}){
    const started = Date.now();
    let result={};
    // para acciones que piden confirmación, no ejecutar directo
    if(action==="whatsapp_send" && slots.contact && !slots.phone && !opts.forcePhone){
      const hits = await queryContacts(slots.contact);
      if(hits.length===0){
        return { ok:false, need:"phone", message:`No encontré "${slots.contact}" en contactos. Indica número con código país (ej: +51999...).`, action, slots };
      }
      if(hits.length>1 && !opts.selectedIndex && hits.some(h=> h.phones.length===0 || h.phones.length>1)){
        return { ok:false, type:"disambiguation", kind:"contacts", action, slots, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones.length? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos coinciden con "${slots.contact}". Elige uno:` };
      }
      const chosen = opts.selectedIndex!=null ? hits[opts.selectedIndex] : hits[0];
      const phone = chosen.phones[0] || slots.phone;
      if(!phone) return { ok:false, need:"phone", message:`Contacto "${chosen.name}" sin teléfono. Indica número.`, action, slots, chosen };
      slots.phone = phone.replace(/[^+0-9]/g,"");
      slots.contactName = chosen.name;
    }
    // también para launch ambiguo: si el nombre no mapea a paquete conocido, pide desambiguación
    if(action==="launch" && slots.app){
      const pkg = appPackageFor(slots.app);
      const out = await runShell(`pm list packages 2>&1 | grep -i -E "${pkg.replace(/\./g,"\\.")}" 2>&1 | head -n 5; echo "---"; pm list packages 2>&1 | grep -i "${String(slots.app).slice(0,12).replace(/"/g,"")}" 2>&1 | head -n 5`, 6000, 1024*1024);
      const pkgs = out.stdout.split("\n").filter(l=> l.includes("package:")).map(l=> l.replace("package:","").trim());
      if(pkgs.length===0){
        return { ok:false, type:"disambiguation", kind:"app", action, slots, options: [], message:`No encontré app "${slots.app}". ¿Quisiste decir WhatsApp, Yape, Cámara, Chrome…?` };
      }
      if(pkgs.length>1){
        const exact = pkgs.find(p=> p===pkg);
        if(exact) slots.pkg = exact; else {
          return { ok:false, type:"disambiguation", kind:"app", action, slots, options: pkgs.slice(0,4).map((p,i)=> ({ idx:i, pkg:p, label:p })), message:`Varias apps coinciden con "${slots.app}". Elige:` };
        }
      } else {
        slots.pkg = pkgs[0] || pkg;
      }
      const pkgFinal = slots.pkg || pkg;
      const amCmd = `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${pkgFinal} 2>&1 || monkey -p ${pkgFinal} -c android.intent.category.LAUNCHER 1 2>&1 || cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${pkgFinal} 2>&1`;
      const r = await runShell(amCmd, 8000);
      const ok = (r.stdout.includes("Events injected") || r.stdout.includes("Starting:"));
      result = { ok, pkg: pkgFinal, stdout: r.stdout.slice(0,1200), stderr:r.stderr.slice(0,400) };
      return { ok, action, slots, result, elapsedMs: Date.now()-started };
    }

    switch(action){
      case "launch":
        return { ok:false, type:"disambiguation", kind:"app", action, slots, options:[], message:"App no especificada" };
      case "screenshot": {
        const r = await runShell(`nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`, 25000, MAX_BUFFER);
        const b64 = r.stdout.trim().replace(/\s/g,"");
        if(b64.length<1000) return { ok:false, action, error:"screenshot vacío", stdout:r.stdout.slice(0,400) };
        return { ok:true, action, result:{ b64, len:b64.length, approx_bytes: Math.floor(b64.length*0.75) }, elapsedMs: Date.now()-started };
      }
      case "volume_up": {
        const r = await runShell(`input keyevent 24; input keyevent 24 2>&1; echo vol_up; dumpsys audio 2>&1 | grep -i "STREAM_MUSIC.*level" | head -n 2`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,800) }, elapsedMs: Date.now()-started };
      }
      case "volume_down": {
        const r = await runShell(`input keyevent 25; input keyevent 25 2>&1; echo vol_down`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "volume_mute": {
        const r = await runShell(`input keyevent 164 2>&1; echo mute; input keyevent 164 2>&1; echo mute2`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "lock_screen": {
        const r = await runShell(`input keyevent 26 2>&1; echo lock`, 4000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,400) }, elapsedMs: Date.now()-started };
      }
      case "unlock_screen": {
        const r = await runShell(`input keyevent 82 2>&1; sleep 0.3; input swipe 540 1800 540 600 300 2>&1; echo unlock_try`, 6000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "wifi_on": { const r = await runShell(`cmd wifi set-wifi-enabled enabled 2>&1; svc wifi enable 2>&1; echo wifi_on`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "wifi_off": { const r = await runShell(`cmd wifi set-wifi-enabled disabled 2>&1; svc wifi disable 2>&1; echo wifi_off`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "bluetooth_on": { const r = await runShell(`cmd bluetooth_manager enable 2>&1; svc bluetooth enable 2>&1; echo bt_on`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "bluetooth_off": { const r = await runShell(`cmd bluetooth_manager disable 2>&1; svc bluetooth disable 2>&1; echo bt_off`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "whatsapp_send_prompt": {
        // si el usuario dijo "manda whatsapp a Juan" sin texto, pide texto
        const hits = await queryContacts(slots.contact||"");
        if(hits.length===0) return { ok:false, need:"contact", message:`No encontré "${slots.contact}". Indica nombre o número.`, action, slots };
        if(hits.length>1) return { ok:false, type:"disambiguation", kind:"contacts", action:"whatsapp_send", slots:{...slots, text:""}, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones[0]? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos coinciden con "${slots.contact}". Elige:` };
        return { ok:false, type:"disambiguation", kind:"whatsapp_text", action:"whatsapp_send", slots:{ contact: hits[0].name, phone: hits[0].phones[0]||"", text:"" }, options:[], message:`¿Qué mensaje le envío a ${hits[0].name}${hits[0].phones[0]? ` (${hits[0].phones[0]})`:""}?` };
      }
      case "whatsapp_send": {
        const phone = String(slots.phone||"").replace(/[^0-9+]/g,"").replace(/^\+0/,"+");
        const text = String(slots.text||"").trim();
        if(!phone) return { ok:false, need:"phone", message:"Falta teléfono", action, slots };
        if(!text) return { ok:false, need:"text", message:"Falta texto del mensaje", action, slots };
        const encoded = encodeURIComponent(text);
        const uri = `https://api.whatsapp.com/send?phone=${phone.replace("+","")}&text=${encoded}`;
        // am start VIEW con uri — abre WhatsApp con chat listo; si a11y está, puede auto-enviar
        let r = await runShell(`am start -a android.intent.action.VIEW -d '${uri}' 2>&1 | head -n 20; echo WA_VIEW`, 8000);
        const ok = r.stdout.includes("Starting:") || r.stdout.includes("WA_VIEW");
        // intento de auto-enviar via a11y si bridge 8766 está
        let autoSend = { tried:false };
        if(ok){
          autoSend.tried = true;
          // espera a que WhatsApp cargue y pulsa "enviar" por a11y clickText
          await new Promise(r2=> setTimeout(r2, 1800));
          const fwd = await new Promise(resolve=>{
            const pr = http.request({ hostname:"127.0.0.1", port:8766, path:"/a11y", method:"POST", headers:{"Content-Type":"application/json"} }, rr=>{
              const c=[]; rr.on("data",x=> c.push(x)); rr.on("end",()=> resolve({ ok:true, body: Buffer.concat(c).toString("utf8") }));
            });
            pr.on("error", e=> resolve({ ok:false, error:String(e) }));
            pr.write(JSON.stringify({ action:"clickText", text:"Enviar" }));
            pr.end();
          });
          if(!fwd.ok){
            autoSend.result = { error:fwd.error, hint:"WhatsApp abierto — pulsa Enviar manualmente si no se envió solo" };
          } else {
            try{ autoSend.result = JSON.parse(fwd.body); }catch{ autoSend.result = { body:fwd.body }; }
          }
        }
        return { ok, action, slots, result:{ uri, stdout:r.stdout.slice(0,800), autoSend }, elapsedMs: Date.now()-started };
      }
      case "call": {
        const hits = await queryContacts(slots.contact||"");
        if(hits.length===0) return { ok:false, need:"contact", message:`No encontré "${slots.contact}"`, action, slots };
        if(hits.length>1) return { ok:false, type:"disambiguation", kind:"contacts", action, slots, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones[0]? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos para "${slots.contact}". Elige:` };
        const phone = hits[0].phones[0] || "";
        if(!phone) return { ok:false, need:"phone", message:`Contacto ${hits[0].name} sin teléfono`, action, slots };
        const r = await runShell(`am start -a android.intent.action.CALL -d tel:${phone.replace(/[^+0-9]/g,"")} 2>&1 | head -n 10; echo CALL`, 6000);
        return { ok:true, action, slots:{...slots, phone, contactName:hits[0].name}, result:{ stdout:r.stdout.slice(0,600)}, elapsedMs: Date.now()-started };
      }
      default: {
        return { ok:false, error:`acción no soportada: ${action}`, action, slots };
      }
    }
  }

  if(pathname==="/api/assistant/intent" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 64*1024);
      const { text, lang } = JSON.parse(raw||"{}");
      if(!text || !String(text).trim()) return json(res, 400, { error:"text requerido" });
      const direct = directIntentOf(text);
      if(direct){
        // para acciones que necesitan parámetros faltantes, delegar a execute con disambiguation
        if(direct.action==="whatsapp_send_prompt" || (direct.action==="whatsapp_send" && !direct.slots.text)){
          // responde disambiguation inmediata sin llamar execute
          const execRes = await executeAssistantAction(direct.action, direct.slots);
          if(execRes.type==="disambiguation") return json(res, 200, execRes);
          return json(res, 200, direct);
        }
        // si launch tiene app muy genérica, verificar si necesita disambiguation
        if(direct.action==="launch"){
          const check = await executeAssistantAction(direct.action, direct.slots);
          if(check.type==="disambiguation" && (check.options||[]).length>0) return json(res, 200, check);
          return json(res, 200, { ...direct, resolved: check });
        }
        return json(res, 200, direct);
      }
      // ambiguo: pide al LLM que clasifique (si opencode está sano, no gastamos tokens aquí — devolvemos estructura para que frontend llame a LLM)
      return json(res, 200, { action:"llm_classify", slots:{ text: String(text).slice(0,600) }, hint: "Texto ambiguo — envíalo a opencode como mensaje con contexto assistant_mode. Si el LLM devuelve JSON {action, slots}, reenvía a /api/assistant/execute.", raw: text, lang: lang||"es" });
    }catch(e){ return json(res, 500, { error:String(e).slice(0,600) }); }
  }
  if(pathname==="/api/assistant/execute" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 64*1024);
      const { action, slots, options, selectedIndex, forcePhone } = JSON.parse(raw||"{}");
      if(!action) return json(res, 400, { error:"action requerido" });
      // chip en progreso: log + header para que pill lo detecte
      console.log(`[assistant] execute ${action} ${JSON.stringify(slots||{}).slice(0,300)}`);
      res.setHeader("X-Assistant-Action", action);
      const r = await executeAssistantAction(action, slots||{}, { selectedIndex: selectedIndex ?? options?.selectedIndex, forcePhone: forcePhone ?? !!slots?.phone });
      // si es disambiguation, responde 200 con type para que frontend renderice tarjetas
      const code = r.ok===false && r.type==="disambiguation" ? 200 : (r.ok ? 200 : 400);
      return json(res, code, r);
    }catch(e){ return json(res, 500, { error:String(e).slice(0,800) }); }
  }

  // 3) static
  let fp = path.join(__dirname, "public", pathname==="/" ? "index.html" : pathname.slice(1));
  // path traversal guard
  if(!fp.startsWith(path.join(__dirname,"public"))) return send(res,403,"forbidden");
  if(fs.existsSync(fp) && fs.statSync(fp).isDirectory()) fp = path.join(fp, "index.html");
  if(!fs.existsSync(fp)){
    // spa fallback para rutas /app etc: serve index
    const idx = path.join(__dirname,"public","index.html");
    if(fs.existsSync(idx)) fp = idx; else return json(res,404,{error:"not found", path:pathname});
  }
  const ext = path.extname(fp).toLowerCase();
  const mime = MIME[ext] || "application/octet-stream";
  // simple etag/cache disable for dev
  res.writeHead(200, {"Content-Type": mime, "Access-Control-Allow-Origin":"*", "Cache-Control":"no-cache"});
  fs.createReadStream(fp).pipe(res);
});

server.on('error', e => {
  if (String(e.code) === 'EADDRINUSE') {
    console.error(`[hub] puerto ${HUB_PORT} ocupado — deja el existente y salgo con 0`);
    process.exit(0);
  }
  console.error('[hub] server error', e);
});
server.on('clientError', (err, socket) => {
  console.error('[hub] clientError', String(err).slice(0,300));
  try { socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); } catch (_) {}
});
server.keepAliveTimeout = 125000;
server.headersTimeout = 130000;
server.requestTimeout = 135000;
server.maxHeadersCount = 100;
server.listen(HUB_PORT, "0.0.0.0", ()=>{
  console.log(`\n[opencode-companion] hub listening http://0.0.0.0:${HUB_PORT}`);
  console.log(`  local  : http://127.0.0.1:${HUB_PORT}`);
  console.log(`  proxy  : /opencode/* -> http://${OPENCODE_HOST}:${OPENCODE_PORT}`);
  console.log(`  api    : /api/status  /api/device/*`);
  console.log(`  pid    : ${process.pid}  node ${process.version}  keepAlive 125s (multimodal streaming)`);
  // try auto-start opencode if not healthy
  http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path:"/global/health", timeout:2000 }, r=>{
    let d=""; r.on("data",c=>d+=c); r.on("end",()=>{
      console.log(`  opencode: OK ${d.slice(0,120)}`);
    });
  }).on("error", ()=>{
    console.log(`  opencode: no responde en ${OPENCODE_HOST}:${OPENCODE_PORT} — inicia:  opencode serve --port ${OPENCODE_PORT} --hostname 0.0.0.0`);
    // opcional auto-spawn si se pasa --auto-opencode
    if(process.argv.includes("--auto-opencode")){
      console.log("  auto-start opencode serve...");
      const child = spawn("opencode", ["serve","--port", String(OPENCODE_PORT),"--hostname","0.0.0.0"], { detached:true, stdio:"ignore" });
      child.unref();
    }
  }).end();

  // print LAN IPs via shell (nsenter-aware)
  runShell(`nsenter -t 1 -m -- ip addr 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; nsenter -t 1 -m -- getprop 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; ip addr 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; getprop 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5`).then(o=>{
    const ips = [...new Set((o.stdout.match(/192\.168\.\d+\.\d+/g) || []))];
    if(ips.length) console.log(`  LAN    : ${ips.map(ip=>`http://${ip}:${HUB_PORT}`).join("  |  ")}`);
    else console.log(`  LAN    : (no se detectó IP, usa el IP de WiFi en Ajustes > Acerca del teléfono)`);
    console.log("");
  });
});
