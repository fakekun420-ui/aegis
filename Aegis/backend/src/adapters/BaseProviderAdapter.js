// BaseProviderAdapter.js — Abstract Base Interface for AI Provider Adapters
// Extracted from providers.js (Strangler Fig Step 3)
// Zero behavior changes — exact copy of BaseProviderAdapter class

/**
 * BaseProviderAdapter — Abstract interface that all provider adapters must implement.
 * Providers: "serve" (OpenCode HTTP) | "cli" (Antigravity agy)
 * 
 * All methods throw NotImplementedError by default — concrete adapters MUST override.
 */
export class BaseProviderAdapter {
  constructor(id, name, type = "cli") {
    this.id = id;
    this.name = name;
    this.type = type; // "serve" | "cli"
  }

  // Health check — returns { up: boolean, healthy: boolean, version?: string, error?: string }
  async isHealthy() {
    throw new Error(`isHealthy() not implemented on ${this.name}`);
  }

  // List all sessions — returns [{ id, title, createdAt, updatedAt, provider, raw? }]
  async listSessions() {
    throw new Error(`listSessions() not implemented on ${this.name}`);
  }

  // Create new session — returns { id, title, createdAt, provider, raw? }
  async createSession(opts = {}) {
    throw new Error(`createSession() not implemented on ${this.name}`);
  }

  // Get messages for a session — returns normalized Message[]
  async getMessages(sessionId, opts = {}) {
    throw new Error(`getMessages() not implemented on ${this.name}`);
  }

  // Send message to session — returns normalized Message (or streaming via callbacks)
  async sendMessage(sessionId, payload = {}, opts = {}) {
    throw new Error(`sendMessage() not implemented on ${this.name}`);
  }

  // Optional: rename session — default no-op
  async renameSession(sessionId, title) {
    return { id: sessionId, title };
  }

  // Optional: delete session — default no-op
  async deleteSession(sessionId) {
    return { id: sessionId, deleted: true };
  }

  // Optional: list available models — returns [{ id, name, description }]
  async listModels() {
    return [];
  }
}