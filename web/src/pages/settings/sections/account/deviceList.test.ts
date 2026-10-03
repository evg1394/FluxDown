import { expect, test } from 'bun:test'
import type { CloudDevice } from '../../../../lib/rpc'
import { sortedDevices } from './deviceList'

test('current device precedes online devices, then sort by case-insensitive name without mutating snapshot', () => {
  const devices = [
    { name: 'Zulu', isOnline: false, isCurrent: false },
    { name: 'beta', isOnline: true, isCurrent: false },
    { name: 'Alpha', isOnline: true, isCurrent: false },
    { name: 'Current', isOnline: false, isCurrent: true },
  ] as CloudDevice[]
  expect(sortedDevices(devices, true).map((device) => device.name)).toEqual(['Current', 'Alpha', 'beta', 'Zulu'])
  expect(devices.map((device) => device.name)).toEqual(['Zulu', 'beta', 'Alpha', 'Current'])
})

test('unknown presence does not prioritize cached online devices', () => {
  const devices = [
    { name: 'zeta', isOnline: true, isCurrent: false },
    { name: 'alpha', isOnline: false, isCurrent: false },
    { name: 'me', isOnline: false, isCurrent: true },
  ] as CloudDevice[]
  expect(sortedDevices(devices, false).map((device) => device.name)).toEqual(['me', 'alpha', 'zeta'])
})
