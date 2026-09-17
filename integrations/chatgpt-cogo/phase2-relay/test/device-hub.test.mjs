import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { DeviceHub } from '../src/device-hub.mjs';
const token = randomBytes(32).toString('hex');
const deviceTokenSha256 = createHash('sha256').update(token).digest('hex');
const deviceId = 'test-device';
const settings = { remoteEnabled: true, allowRemoteReads: true, allowRemoteWrites: true, allowRemoteBuilds: false, allowRemoteRun: false, allowRemoteDelete: false };
const principal = { subject: 'authenticated-user', scopes: ['cogo:read','cogo:write'] };
function prepare() {
  const sent = []; let closed = 0;
  const hub = new DeviceHub({ deviceId, deviceTokenSha256, settings });
  const connect = () => {
    const sessionId = hub.connect({ deviceId, token, send: msg => sent.push(msg), close: () => { closed++; } });
    hub.hello(sessionId, { protocol: 1, type: 'device_hello', device_id: deviceId, protocol_version: 1, capabilities: ['read_file', 'apply_patch'] });
    return sessionId;
  };
  return { hub, sent, connect, get closed() {return closed;} };
}
test('invalid device credential is rejected; no unauthenticated transport', () => {
  const hub = new DeviceHub({ deviceId, deviceTokenSha256, settings });
  assert.throws(() => hub.connect({ deviceId, token: randomBytes(32).toString('hex'), send: () => {} }), { code:'DEVICE_AUTH_FAILED' });
  assert.equal(hub.status, 'DEVICE_OFFLINE');
});
test('no offline queue, no tool use before authenticated hello', () => {
  const hub = new DeviceHub({ deviceId, deviceTokenSha256, settings });
  const call = () => hub.dispatch({ tool: 'cogo_read_file', arguments: {path:'app/Main.kt'}, principal });
  assert.throws(call, { code: 'DEVICE_OFFLINE' });
  hub.connect({deviceId, token, send: () => {}});
  assert.throws(call, { code: 'DEVICE_OFFLINE' });
});
test('dispatches to authenticated device, maps same request ID back, and deduplicates retry', async () => {
  const p = prepare(); const session = p.connect(); const requestId = randomUUID();
  const args = {path:'app/Main.kt'};
  const first = p.hub.dispatch({ requestId, tool:'cogo_read_file', arguments: args, principal });
  assert.equal(p.hub.dispatch({ requestId, tool:'cogo_read_file', arguments: args, principal }), first);
  assert.equal(p.sent.length, 1);
  assert.equal(p.sent[0].tool, 'read_file');
  assert.equal(p.sent[0].request_id, requestId);
  const reply = {protocol:1, type:'tool_result', request_id:requestId, status:'ok', result:{text:'hello'}};
  assert.equal(p.hub.receive(session, reply), true);
  assert.equal((await first).result.text, 'hello');
  assert.equal((await p.hub.dispatch({ requestId, tool:'cogo_read_file', arguments:args, principal })).result.text,'hello');
  assert.equal(p.sent.length, 1);
  assert.throws(() => p.hub.dispatch({requestId,tool:'cogo_read_file',arguments:{path:'other.kt'},principal}), { code:'REQUEST_ID_CONFLICT' });
  p.hub.disconnect(session);
});
test('session replacement rejects in-flight request, ignores stale result, and does not replay', async () => {
  const p = prepare(); const old = p.connect(); const requestId = randomUUID();
  const promise = p.hub.dispatch({requestId,tool:'cogo_apply_patch',arguments:{path:'a.kt',patch:'x'},principal});
  const replacement = p.connect();
  await assert.rejects(promise, {code:'DEVICE_REPLACED'});
  assert.equal(p.closed, 1);
  assert.equal(p.hub.receive(old,{protocol:1,type:'tool_result',request_id:requestId,status:'ok',result:{text:'stale'}}),false);
  assert.equal(p.hub.status,'CONNECTED');
  await assert.rejects(p.hub.dispatch({requestId,tool:'cogo_apply_patch',arguments:{path:'a.kt',patch:'x'},principal}), {code:'DEVICE_REPLACED'});
  assert.equal(p.sent.length,1);
  p.hub.disconnect(replacement);
});
test('explicit cancellation emits one cancel and suppresses late second result', async () => {
  const p = prepare(); const session = p.connect(); const requestId = randomUUID();
  const promise = p.hub.dispatch({requestId,tool:'cogo_read_file',arguments:{path:'a.kt'},principal});
  assert.equal(p.hub.cancel(requestId),true);
  await assert.rejects(promise,{code:'CANCELLED_UNKNOWN'});
  assert.equal(p.sent.at(-1).type,'tool_cancel');
  assert.equal(p.hub.receive(session,{protocol:1,type:'tool_result',request_id:requestId,status:'ok',result:{text:'late'}}),false);
  p.hub.disconnect(session);
});
test('unauthorized writes and capabilities not advertised are rejected before network send', () => {
  const p = prepare(); const session = p.connect();
  assert.throws(() => p.hub.dispatch({tool:'cogo_apply_patch',arguments:{},principal:{subject:'reader',scopes:['cogo:read']}}), {code:'INSUFFICIENT_SCOPE'});
  assert.throws(() => p.hub.dispatch({tool:'cogo_project_info',arguments:{},principal}), {code:'UNSUPPORTED_TOOL'});
  assert.equal(p.sent.length,0);
  p.hub.disconnect(session);
});
