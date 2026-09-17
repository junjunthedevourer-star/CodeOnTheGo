import { TOOLS, ProtocolError } from './contract.mjs';
/** This is a preliminary relay gate, NOT a replacement for the phone's local approval. */
export const DEFAULT_REMOTE_SETTINGS = Object.freeze({ remoteEnabled: false, allowRemoteReads: true, allowRemoteWrites: false, allowRemoteBuilds: false, allowRemoteRun: false, allowRemoteDelete: false });
const permissionFor = Object.freeze({ READ: 'allowRemoteReads', WRITE: 'allowRemoteWrites', BUILD: 'allowRemoteBuilds', RUN: 'allowRemoteRun', DELETE: 'allowRemoteDelete' });
export function authorizeRelayTool(toolName, { scopes = [], settings = DEFAULT_REMOTE_SETTINGS } = {}) {
  if (!Object.hasOwn(TOOLS, toolName)) throw new ProtocolError('UNKNOWN_TOOL', 'Tool is not allowlisted');
  const metadata = TOOLS[toolName];
  if (settings.remoteEnabled !== true) throw new ProtocolError('REMOTE_DISABLED', 'Remote bridge is disabled');
  if (!Array.isArray(scopes) || !scopes.includes(metadata.scope)) throw new ProtocolError('INSUFFICIENT_SCOPE', 'Caller lacks tool scope');
  if (settings[permissionFor[metadata.category]] !== true) throw new ProtocolError('TOOL_DISABLED', 'Tool category is disabled');
  return Object.freeze({ deviceName: metadata.deviceName, category: metadata.category, requiresLocalApproval: metadata.category !== 'READ', requiresDeviceSensitiveReadCheck: metadata.category === 'READ', requiresJobOwnership: metadata.requiresJobOwnership });
}
