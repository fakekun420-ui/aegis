// GitAdapter.js — Git Operations Adapter
// Phase 3

import { spawn } from "node:child_process";
import { getProjectAbsPath } from "../core/pathResolver.js";

export class GitAdapter {
  constructor() {}

  async _runGit(args, projectId) {
    const cwd = getProjectAbsPath(projectId);
    return new Promise((resolve, reject) => {
      const p = spawn("git", args, { cwd });
      let stdout = "";
      let stderr = "";
      p.stdout.on("data", c => stdout += c);
      p.stderr.on("data", c => stderr += c);
      p.on("close", code => {
        if (code === 0) resolve(stdout.trim());
        else reject(new Error(`Git error (${code}): ${stderr.trim()}`));
      });
      p.on("error", err => reject(err));
    });
  }

  async cloneRepo(url, projectId) {
    // Cloning requires running in the workspace root usually, or creating the dir
    const cwd = "/sdcard/projects";
    return new Promise((resolve, reject) => {
      const p = spawn("git", ["clone", url, projectId], { cwd });
      let stdout = "";
      let stderr = "";
      p.stdout.on("data", c => stdout += c);
      p.stderr.on("data", c => stderr += c);
      p.on("close", code => {
        if (code === 0) resolve(stdout.trim());
        else reject(new Error(`Git clone error (${code}): ${stderr.trim()}`));
      });
      p.on("error", err => reject(err));
    });
  }

  async getStatus(projectId) {
    return this._runGit(["status", "-s"], projectId);
  }

  async commit(projectId, message) {
    await this._runGit(["add", "."], projectId);
    return this._runGit(["commit", "-m", message], projectId);
  }

  async pull(projectId) {
    return this._runGit(["pull"], projectId);
  }

  async push(projectId) {
    return this._runGit(["push"], projectId);
  }

  async listBranches(projectId) {
    return this._runGit(["branch"], projectId);
  }

  async checkout(projectId, branch) {
    return this._runGit(["checkout", branch], projectId);
  }
}
