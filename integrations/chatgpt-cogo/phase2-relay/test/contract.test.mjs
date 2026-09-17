import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { PROTOCOL_VERSION, TOOLS, boundedJson, validateToolRequest, validateToolResult } from '../src/contract.mjs';
import { authorizeRelayTool, DEFAULT_REMOTE_SETTINGS } from '../src/policy.mjs';
const now = 1_800_000_000_000;
const makeRequest = (tool = 'read_file', args = { path: 'app/Main.kt' }) => ({protocol: PROTOCOL_VERSION, type: 'tool_request', message_id: randomUUID(), request_id: randomUUID(), timestamp: now, expires_at: now + 30_000, tool, arguments: args, actor: { subject: 'verified-subject' }});
const settings = { ...DEFAULT_REMOTE_SETTINGS, remoteEnabled: true };
test('all twenty-two planned tools are allowlisted with explicit classifications', () => {
  assert.equal(Object.keys(TOOLS).length, 22);
  assert.equal(TOOLS.cogo_delete_file.category, 'DELETE');
  assert.equal(TOOLS.cogo_gradle_execute.category, 'BUILD');
  assert.equal(TOOLS.cogo_cancel_job.requiresJobOwnership, true);
});
test('accepts read request and refuses unknown tool', () => {
  assert.equal(validateToolRequest(makeRequest(), now).tool.deviceName, 'read_file');
  assert.throws(() => validateToolRequest(makeRequest('execute_shell'), now), { code: 'UNKNOWN_TOOL' });
});
test('refuses expired, oversized TTL, invalid actor and traversal-like unsafe JSON properties', () => {
  const expired = makeRequest(); expired.expires_at = now; assert.throws(() => validateToolRequest(expired, now), { code: 'EXPIRED_REQUEST' });
  const oversized = makeRequest(); oversized.expires_at = now + 180_000; assert.throws(() => validateToolRequest(oversized, now), { code: 'EXPIRED_REQUEST' });
  const actor = makeRequest(); actor.actor = { subject: '' }; assert.throws(() => validateToolRequest(actor, now), { code: 'INVALID_REQUEST' });
  const poisoned = JSON.parse('{"__proto__":{"admin":true}}'); assert.throws(() => boundedJson(poisoned), { code: 'INVALID_ARGUMENTS' });
});
test('checks payload byte size and invalid numeric values', () => {
  assert.throws(() => boundedJson({ body: 'a'.repeat(70_000) }), { code: 'PAYLOAD_TOO_LARGE' });
  assert.throws(() => boundedJson({ bad: Number.NaN }), { code: 'INVALID_ARGUMENTS' });
});
test('does not serialize stack traces and enforces output size', () => {
  const good = { protocol: 1, type: 'tool_result', request_id: randomUUID(), status: 'ok', result: { text: 'ok' } };
  assert.equal(validateToolResult(good), good);
  assert.throws(() => validateToolResult({ ...good, status: 'error', error: { code: 'FAILED', message: 'oops', stack: 'secret' } }), { code: 'INVALID_RESULT' });
  assert.throws(() => validateToolResult({ ...good, result: { content: 'x'.repeat(1_100_000) } }), { code: 'PAYLOAD_TOO_LARGE' });
});
test('remote is off by default; scopes and category gates are independent', () => {
  assert.throws(() => authorizeRelayTool('cogo_read_file', {scopes: ['cogo:read']}), { code: 'REMOTE_DISABLED' });
  assert.throws(() => authorizeRelayTool('cogo_read_file', { settings, scopes: [] }), { code: 'INSUFFICIENT_SCOPE' });
  assert.equal(authorizeRelayTool('cogo_read_file', { settings, scopes: ['cogo:read'] }).requiresDeviceSensitiveReadCheck, true);
  assert.throws(() => authorizeRelayTool('cogo_write_file', { settings, scopes: ['cogo:write'] }), { code: 'TOOL_DISABLED' });
  assert.equal(authorizeRelayTool('cogo_apply_patch', { settings: {...settings, allowRemoteWrites:true}, scopes: ['cogo:write'] }).requiresLocalApproval, true);
  assert.equal(authorizeRelayTool('cogo_delete_file', { settings: {...settings, allowRemoteDelete:true}, scopes: ['cogo:delete'] }).requiresLocalApproval, true);
});
