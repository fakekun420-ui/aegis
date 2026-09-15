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
function argVal(name, fallback){
  const i = process.argv.indexOf(name);
  return i !== -1 && process.argv[i+1] ? process.argv[i+1] : fallback;
}
const HUB_PORT = parseInt(process.env.HUB_PORT || argVal("--port","8765"), 10);
const OPENCODE_PORT = parseInt(process.env.OPENCODE_PORT || argVal("--opencode-port","4096"), 10);
const OPENCODE_HOST = process.env.OPENCODE_HOST || "127.0.0.1";
const PROJECTS_ROOT = "/sdcard/projects";

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
  // streaming-safe: no buffer body en memoria, pipe directo
  // importante para parts con imágenes Base64 5-15MB — no usar readJsonBody aquí
  const opts = { hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: targetPath, method: req.method, headers: { ...req.headers, host: `${OPENCODE_HOST}:${OPENCODE_PORT}` } };
  // conserva content-length si existe para que opencode no espere chunked
  delete opts.headers["accept-encoding"];
  // si el cliente mandó body grande, Node ya lo está pipeando — no lo bufferices
  const pr = http.request(opts, (prRes)=>{
    // reenvía status + headers (incluye SSE content-type)
    const h = { ...prRes.headers, "Access-Control-Allow-Origin":"*", "Access-Control-Allow-Headers":"*", "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS" };
    res.writeHead(prRes.statusCode, h);
    prRes.pipe(res);
  });
  pr.on("error", e=> {
    if(!res.headersSent) json(res, 502, { error:"opencode unreachable", detail:String(e), hint:`opencode serve debe estar corriendo en ${OPENCODE_HOST}:${OPENCODE_PORT}. Ejecuta: opencode serve --port ${OPENCODE_PORT} --hostname 0.0.0.0  ó  opencode web --port ${OPENCODE_PORT} --hostname 0.0.0.0` });
    else try{ res.end(); }catch(_){}
  });
  // backpressure-safe pipe (mantiene memoria < 64KB chunks)
  req.pipe(pr);
  // timeout largo para streaming LLM con imágenes (60s)
  pr.setTimeout(65000, ()=> { try{ pr.destroy(); }catch(_){} });
}

// device helpers — portable + Android namespace aware
// En este POCO F3, los comandos Android (pm, input, am, getprop, uiautomator) solo funcionan vía nsenter -t 1 -m
const ANDROID_CMDS = /\b(pm|input|am|getprop|uiautomator|cmd|dumpsys|settings)\b/;
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
  if(req.method==="OPTIONS"){ return send(res, 204, ""); }
  const url = new URL(req.url, `http://${req.headers.host}`);
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
    try{ const raw=await readJsonBody(req); const { pkg, activity } = JSON.parse(raw||"{}"); if(!pkg) return json(res, 400, { error:"pkg requerido ej: com.bcp.bo.wallet" }); const cmd = activity ? `am start -n ${pkg}/${activity}` : `monkey -p ${pkg} -c android.intent.category.LAUNCHER 1`; const out = await runShell(cmd); json(res, 200, { cmd, ...out }); }catch(e){ json(res,500,{error:String(e)}); }
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

server.listen(HUB_PORT, "0.0.0.0", ()=>{
  console.log(`\n[opencode-companion] hub listening http://0.0.0.0:${HUB_PORT}`);
  console.log(`  local  : http://127.0.0.1:${HUB_PORT}`);
  console.log(`  proxy  : /opencode/* -> http://${OPENCODE_HOST}:${OPENCODE_PORT}`);
  console.log(`  api    : /api/status  /api/device/*`);
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
