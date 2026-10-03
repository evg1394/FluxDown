import type { CloudConnectionDto, CloudDevice } from './rpc'

export function cloudPresenceKnown(connection: CloudConnectionDto | undefined, localReady: boolean): boolean {
  return localReady && connection?.state === 'connected'
}

export function cloudConnectionLabelKey(connection: CloudConnectionDto | undefined, localReady: boolean): string {
  if (!localReady) return 'localServiceDisconnected'
  switch (connection?.state) {
    case 'connected': return 'cloudConnectionConnected'
    case 'connecting': return 'cloudConnectionConnecting'
    case 'reconnecting': return 'cloudConnectionReconnecting'
    default: return 'cloudConnectionDisconnected'
  }
}

export function devicePresenceKey(device: Pick<CloudDevice, 'isOnline'>, presenceKnown: boolean): string {
  return presenceKnown ? (device.isOnline ? 'deviceOnline' : 'deviceOffline') : 'devicePresenceUnknown'
}
