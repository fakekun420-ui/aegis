import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url)));
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");
const PROJECTS_ROOT = process.env.PROJECTS_ROOT || (fs.existsSync("/sdcard/projects") ? "/sdcard/projects" : join(BACKEND_DIR, "..", ".."));

let child = null;
let port = 0;
let token = "";

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
  });
}

async function api(pathname, { withToken = true, method = "GET", body } = {}) {
  const headers = {};
  if (withToken) headers["X-Aegis-Token"] = token;
  const init = { method, headers };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const res = await fetch(`http://127.0.0.1:${port}${pathname}`, init);
  let parsed = null;
  try { parsed = await res.json(); } catch (_) { parsed = null; }
  return { status: res.status, body: parsed };
}

async function waitForHub(proc, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let stderr = "";
  proc.stderr.on("data", (c) => { stderr += c.toString(); });
  while (Date.now() < deadline) {
    if (proc.exitCode !== null) {
      throw new Error(`server.js salió antes de estar listo (code=${proc.exitCode})\n${stderr}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) return;
    } catch (_) {}
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`hub no respondió en ${timeoutMs}ms\n${stderr}`);
}

before(async () => {
  try {
    token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  } catch (_) {
    token = "test-token-f4-security";
    fs.writeFileSync(TOKEN_FILE, token, { mode: 0o600 });
  }
  port = await freePort();
  child = spawn(
    process.execPath,
    [join(BACKEND_DIR, "server.js"), "--port", String(port), "--opencode-port", "49374"],
    {
      cwd: BACKEND_DIR,
      env: { ...process.env, AEGIS_RATE_LIMIT: "0", NODE_ENV: "test", PROJECTS_ROOT: PROJECTS_ROOT },
      stdio: ["ignore", "ignore", "pipe"]
    }
  );
  await waitForHub(child);
});

after(async () => {
  if (child && child.exitCode === null) {
    child.kill("SIGTERM");
    await new Promise((r) => setTimeout(r, 500));
    if (child.exitCode === null) child.kill("SIGKILL");
  }
});

test("T19: Project Folder and Ponytail auto-generation", async () => {
  const testProjectName = `Test Auto Folder ${Date.now()}`;
  const safeFolderName = testProjectName.toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9_-]/g, "");
  const expectedFolder = join(PROJECTS_ROOT, safeFolderName);
  const expectedPonytail = join(expectedFolder, ".ponytail.md");
  const expectedHubJson = join(expectedFolder, ".hub", "project.json");

  const res = await api("/api/projects", {
    method: "POST",
    body: {
      name: testProjectName,
      description: "Automated test project for trading bot verification",
      provider: "opencode"
    }
  });

  assert.equal(res.status, 201, "POST /api/projects should return 201");
  assert.equal(res.body.ok, true);
  assert.equal(res.body.data.folder, expectedFolder);
  assert.equal(res.body.data.ponytail, expectedPonytail);

  // Check physical folder existence
  assert.ok(fs.existsSync(expectedFolder), `folder should exist: ${expectedFolder}`);
  assert.ok(fs.existsSync(expectedPonytail), `.ponytail.md should exist: ${expectedPonytail}`);
  assert.ok(fs.existsSync(expectedHubJson), `.hub/project.json should exist: ${expectedHubJson}`);

  // Check .hub/project.json content
  const hubMeta = JSON.parse(fs.readFileSync(expectedHubJson, "utf8"));
  assert.equal(hubMeta.id, res.body.data.id);
  assert.equal(hubMeta.name, testProjectName);
  assert.equal(hubMeta.provider, "opencode");

  // Check .ponytail.md content
  const ponytailContent = fs.readFileSync(expectedPonytail, "utf8");
  assert.match(ponytailContent, new RegExp(`PONYTAIL — ${testProjectName}`));
  assert.match(ponytailContent, /trading system/);

  // Check GET /api/projects includes folder and ponytail
  const listRes = await api("/api/projects");
  const found = listRes.body.data.find(p => p.id === res.body.data.id);
  assert.ok(found, "created project should be found in list");
  assert.equal(found.folder, expectedFolder);
  assert.equal(found.ponytail, expectedPonytail);

  // Duplicate name returns 409
  const dupRes = await api("/api/projects", {
    method: "POST",
    body: { name: testProjectName }
  });
  assert.equal(dupRes.status, 409);

  // Cleanup test folder
  try {
    fs.rmSync(expectedFolder, { recursive: true, force: true });
  } catch (_) {}
});
