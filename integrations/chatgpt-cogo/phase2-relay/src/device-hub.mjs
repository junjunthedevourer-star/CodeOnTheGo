import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { boundedJson, isRecord, ProtocolError, PROTOCOL_VERSION, TOOLS, TTL_MS, validateToolRequest, validateToolResult } from './contract.mjs';
import { authorizeRelayTool, DEFAULT_REMOTE_SETTINGS } from './policy.mjs';
const hexSha = text => createHash('sha256').update(text, 'utf8').digest();
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const failure = (code, msg) => new ProtocolError(code, msg);
/**
 * One-device, transport-independent relay dispatch hub. The caller MUST verify
 * OAuth before constructing `principal`; phone execution and approvals are NOT
 * implemented here. No incoming tool request is queued while disconnected.
 */
export class DeviceHub {
  constructor({ deviceId, deviceTokenSha256, settings = DEFAULT_REMOTE_SETTINGS, maxPending = 32, now = Date.now }) {
    if (typeof deviceId !== 'string' || !deviceId || !/^[0-9a-f]{64}$/i.test(deviceTokenSha256 || '')) throw failure('CONFIG_ERROR', 'Configure a device ID and a SHA-256 digest of a high-entropy device token');
    this.deviceId = deviceId;
    this.digest = Buffer.from(deviceTokenSha256, 'hex');
    this.settings = settings;
    this.maxPending = maxPending;
    this.now = now;
    this.session = null;
    this.pending = new Map();
    this.completed = new Map();
  }
  connect({ deviceId, token, send, close = () => {} }) {
    if (deviceId !== this.deviceId || typeof token !== 'string' || token.length < 32 || typeof send !== 'function' || typeof close !== 'function') throw failure('DEVICE_AUTH_FAILED', 'Invalid device identity or transport');
    if (!timingSafeEqual(hexSha(token), this.digest)) throw failure('DEVICE_AUTH_FAILED', 'Invalid device credential');
    this.#invalidate('DEVICE_REPLACED');
    const sessionId = randomUUID();
    this.session = { sessionId, send, close, ready: false, capabilities: new Set() };
    return sessionId;
  }
  hello(sessionId, message) {
    if (!this.session || this.session.sessionId !== sessionId) throw failure('STALE_SESSION', 'Connection is no longer active');
    if (!isRecord(message) || message.protocol !== PROTOCOL_VERSION || message.type !== 'device_hello' || message.device_id !== this.deviceId || message.protocol_version !== PROTOCOL_VERSION || !Array.isArray(message.capabilities) || message.capabilities.length > 100) throw failure('INVALID_HELLO', 'Invalid device hello');
    if (!message.capabilities.every(name => typeof name === 'string' && Object.hasOwn(TOOLS, `cogo_${name}`))) throw failure('INVALID_HELLO', 'Unexpected device capability');
    this.session.capabilities = new Set(message.capabilities);
    this.session.ready = true;
  }
  disconnect(sessionId) {
    if (this.session?.sessionId !== sessionId) return false;
    this.#invalidate('DEVICE_OFFLINE');
    return true;
  }
  #remember(requestId, fingerprint, outcome) {
    this.completed.delete(requestId);
    this.completed.set(requestId, { fingerprint, outcome, until: this.now() + 30 * 60_000 });
    while (this.completed.size > 200) this.completed.delete(this.completed.keys().next().value);
  }
  #finish(requestId, outcome) {
    const item = this.pending.get(requestId);
    if (!item) return;
    this.pending.delete(requestId);
    clearTimeout(item.timer);
    this.#remember(requestId, item.fingerprint, outcome);
    outcome.ok ? item.resolve(outcome.value) : item.reject(outcome.error);
  }
  #invalidate(code) {
    const old = this.session;
    this.session = null;
    for (const requestId of [...this.pending.keys()]) this.#finish(requestId, { ok: false, error: failure(code, 'Device session ended; mutation outcome may be unknown') });
    try { old?.close(); } catch { /* A broken close callback must not keep a stale session active. */ }
  }
  get status() { return this.session?.ready ? 'CONNECTED' : this.session ? 'AWAITING_HELLO' : 'DEVICE_OFFLINE'; }
  dispatch({ requestId = randomUUID(), tool, arguments: args = {}, principal }) {
    if (!uuid.test(requestId)) throw failure('INVALID_REQUEST', 'Request ID must be a UUID');
    if (!isRecord(principal) || typeof principal.subject !== 'string' || !principal.subject) throw failure('UNAUTHENTICATED', 'A verified OAuth principal is required');
    const metadata = authorizeRelayTool(tool, { scopes: principal.scopes, settings: this.settings });
    if (!isRecord(args)) throw failure('INVALID_ARGUMENTS', 'Arguments must be an object');
    const fingerprint = createHash('sha256').update(boundedJson({tool, args, subject: principal.subject})).digest('hex');
    const current = this.pending.get(requestId);
    if (current) {
      if (current.fingerprint !== fingerprint) throw failure('REQUEST_ID_CONFLICT', 'Request ID was reused with different content');
      return current.promise;
    }
    const old = this.completed.get(requestId);
    if (old && old.until > this.now()) {
      if (old.fingerprint !== fingerprint) throw failure('REQUEST_ID_CONFLICT', 'Request ID was reused with different content');
      return old.outcome.ok ? Promise.resolve(old.outcome.value) : Promise.reject(old.outcome.error);
    }
    if (!this.session?.ready) throw failure('DEVICE_OFFLINE', 'No paired device is connected; requests are not queued');
    if (!this.session.capabilities.has(metadata.deviceName)) throw failure('UNSUPPORTED_TOOL', 'Device does not advertise the tool');
    if (this.pending.size >= this.maxPending) throw failure('BUSY', 'Too many outstanding requests');
    const session = this.session;
    const timestamp = this.now();
    const message = { protocol: PROTOCOL_VERSION, type: 'tool_request', message_id: randomUUID(), request_id: requestId, timestamp, expires_at: timestamp + TTL_MS[metadata.category], tool: metadata.deviceName, arguments: args, actor: { subject: principal.subject } };
    validateToolRequest(message, timestamp);
    let resolve;
    let reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    const timer = setTimeout(() => {
      if (this.pending.has(requestId) && this.session?.sessionId === session.sessionId) {
        try { session.send({ protocol: PROTOCOL_VERSION, type: 'tool_cancel', request_id: requestId }); } catch { /* best effort */ }
      }
      this.#finish(requestId, { ok: false, error: failure('TIMEOUT_UNKNOWN', 'Request expired; execution outcome may be unknown and will not be retried automatically') });
    }, TTL_MS[metadata.category]);
    this.pending.set(requestId, { fingerprint, promise, resolve, reject, timer, sessionId: session.sessionId });
    try { session.send(message); } catch {
      this.#finish(requestId, { ok: false, error: failure('TRANSPORT_ERROR', 'Device transport failed; execution outcome may be unknown') });
    }
    return promise;
  }
  receive(sessionId, message) {
    if (!this.session || this.session.sessionId !== sessionId) return false;
    validateToolResult(message);
    const item = this.pending.get(message.request_id);
    if (!item || item.sessionId !== sessionId) return false;
    this.#finish(message.request_id, { ok: true, value: message });
    return true;
  }
  cancel(requestId) {
    const item = this.pending.get(requestId);
    if (!item) return false;
    if (this.session?.sessionId === item.sessionId) {
      try { this.session.send({ protocol: PROTOCOL_VERSION, type: 'tool_cancel', request_id: requestId }); } catch { /* best effort */ }
    }
    this.#finish(requestId, { ok: false, error: failure('CANCELLED_UNKNOWN', 'Cancellation requested; any already executing operation may still complete') });
    return true;
  }
}
