// pathResolver.js — Workspace Path Resolution
// Extracted from providers.js/server.js patterns (Strangler Fig Step 6)
// Centralizes WORKSPACE_ROOT constant and project path validation

import path from "node:path";

export const WORKSPACE_ROOT = "/sdcard/projects";

/**
 * Validates projectId against safe filesystem naming convention
 * Allowed: alphanumeric, hyphen, underscore only
 * Prevents path traversal and injection
 */
export function isValidProjectId(projectId) {
  if (!projectId || typeof projectId !== "string") return false;
  return /^[a-zA-Z0-9\-_]+$/.test(projectId);
}

/**
 * Resolves absolute path for a project within WORKSPACE_ROOT
 * Validates projectId format before resolution
 * Throws if projectId is invalid (prevents directory traversal)
 * 
 * @param {string} projectId - Project identifier (alphanumeric, hyphen, underscore only)
 * @returns {string} Absolute path to project directory
 * @throws {Error} If projectId contains invalid characters
 */
export function getProjectAbsPath(projectId) {
  if (!isValidProjectId(projectId)) {
    throw new Error(`Invalid projectId: "${projectId}". Must match /^[a-zA-Z0-9\\-_]+$/`);
  }
  return path.join(WORKSPACE_ROOT, projectId);
}

/**
 * Resolves absolute path for a project file/directory within project workspace
 * Validates both projectId and relative path
 * 
 * @param {string} projectId - Project identifier
 * @param {string} relativePath - Path relative to project root
 * @returns {string} Absolute path
 * @throws {Error} If projectId invalid or relativePath attempts traversal
 */
export function resolveProjectPath(projectId, relativePath = "") {
  const projectRoot = getProjectAbsPath(projectId);
  
  if (!relativePath) return projectRoot;
  
  const normalized = path.normalize(relativePath);
  
  // Prevent directory traversal
  if (normalized.startsWith("..") || path.isAbsolute(normalized)) {
    throw new Error(`Invalid relative path: "${relativePath}". Path traversal not allowed.`);
  }
  
  return path.join(projectRoot, normalized);
}

// Re-export path for convenience
export { path };