/** Phase 2 protocol v1. Transport only: all project operations belong to Phase 1. */
export const PROTOCOL_VERSION = 1;
export const MAX_ARGUMENT_BYTES = 64 * 1024;
export const MAX_RESULT_BYTES = 1024 * 1024;
export const TTL_MS = Object.freeze({ READ: 120_000, WRITE: 120_000, BUILD: 600_000, RUN: 600_000, DELETE: 120_000 });
const groups = {
  READ: ['project_info', 'project_tree', 'list_directory', 'search_files', 'search_text', 'read_file', 'read_file_range', 'get_open_files', 'get_modified_files', 'get_build_output', 'get_build_errors', 'job_status'],
  WRITE: ['write_file', 'create_file', 'apply_patch', 'move_file', 'rollback_last_change'],
  BUILD: ['gradle_execute', 'gradle_sync', 'cancel_job'],
  RUN: ['run_app'],
  DELETE: ['delete_file']
};
export const TOOLS = Object.freeze(Object.fromEntries(Object.entries(groups).flatMap(([category, names]) => names.map(name => [
  `cogo_${name}`,
  Object.freeze({ category, scope: `cogo:${category.toLowerCase() === 'delete' ? 'delete' : category.toLowerCase()}`, deviceName: name, requiresJobOwnership: name === 'job_status' || name === 'cancel_job' })
]))));
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const forbidden = new Set(['__proto__', 'constructor', 'prototype']);
export class ProtocolError extends Error {
  constructor(code, message) { super(message); this.name = 'ProtocolError'; this.code = code; }
}
function check(ok, code, message) { if (!ok) throw new ProtocolError(code, message); }
export const isRecord = v => v !== null && typeof v === 'object' && !Array.isArray(v) && (Object.getPrototypeOf(v) === Object.prototype || Object.getPrototypeOf(v) === null);
export function boundedJson(value, maxBytes = MAX_ARGUMENT_BYTES) {
  const visited = new Set();
  function visit(v, depth) {
    check(depth <= 12, 'INVALID_ARGUMENTS', 'JSON nesting exceeds limit');
    if (v && typeof v === 'object') {
      check(!visited.has(v), 'INVALID_ARGUMENTS', 'Circular JSON value'); visited.add(v);
      check(Array.isArray(v) || isRecord(v), 'INVALID_ARGUMENTS', 'Non-JSON object');
      const keys = Object.keys(v);
      check(keys.length <= 2000, 'INVALID_ARGUMENTS', 'Too many JSON entries');
      for (const key of keys) {
        check(!forbidden.has(key), 'INVALID_ARGUMENTS', 'Forbidden JSON property');
        visit(v[key], depth + 1);
      }
      visited.delete(v);
    } else {
      check(v === null || typeof v === 'boolean' || typeof v === 'string' || (typeof v === 'number' && Number.isFinite(v)), 'INVALID_ARGUMENTS', 'Non-JSON scalar');
    }
  }
  visit(value, 0);
  const encoded = JSON.stringify(value);
  check(encoded !== undefined && Buffer.byteLength(encoded, 'utf8') <= maxBytes, 'PAYLOAD_TOO_LARGE', 'JSON payload exceeds byte limit');
  return encoded;
}
export function validateToolRequest(input, now = Date.now()) {
  check(isRecord(input), 'INVALID_REQUEST', 'Request must be a JSON object');
  check(input.protocol === PROTOCOL_VERSION && input.type === 'tool_request', 'INVALID_REQUEST', 'Unsupported protocol or message type');
  check(UUID.test(input.message_id) && UUID.test(input.request_id), 'INVALID_REQUEST', 'Invalid message or request UUID');
  check(Number.isSafeInteger(input.timestamp) && Math.abs(now - input.timestamp) <= 300_000, 'INVALID_REQUEST', 'Invalid or distant timestamp');
  check(typeof input.tool === 'string' && Object.hasOwn(TOOLS, `cogo_${input.tool}`), 'UNKNOWN_TOOL', 'Tool is not allowlisted');
  const tool = TOOLS[`cogo_${input.tool}`];
  check(Number.isSafeInteger(input.expires_at) && input.expires_at > now && input.expires_at >= input.timestamp && input.expires_at - input.timestamp <= TTL_MS[tool.category], 'EXPIRED_REQUEST', 'Request expired or TTL exceeds tool limit');
  check(isRecord(input.arguments), 'INVALID_ARGUMENTS', 'Arguments must be an object');
  boundedJson(input.arguments);
  check(isRecord(input.actor) && typeof input.actor.subject === 'string' && input.actor.subject.length > 0 && input.actor.subject.length <= 256, 'INVALID_REQUEST', 'Missing authenticated actor subject');
  return Object.freeze({ tool, requestId: input.request_id });
}
export function validateToolResult(input) {
  check(isRecord(input) && input.protocol === PROTOCOL_VERSION && input.type === 'tool_result' && UUID.test(input.request_id), 'INVALID_RESULT', 'Invalid result envelope');
  check(input.status === 'ok' || input.status === 'error', 'INVALID_RESULT', 'Invalid result status');
  if (input.status === 'ok') {
    check(isRecord(input.result), 'INVALID_RESULT', 'Successful result must be an object');
    boundedJson(input.result, MAX_RESULT_BYTES);
  } else {
    check(isRecord(input.error) && /^[A-Z][A-Z0-9_]{0,63}$/.test(input.error.code) && typeof input.error.message === 'string' && input.error.message.length <= 1024, 'INVALID_RESULT', 'Invalid sanitized error');
    check(!Object.hasOwn(input.error, 'stack'), 'INVALID_RESULT', 'Never send stack traces');
    boundedJson(input.error, 4096);
  }
  return input;
}
