import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ACTIONS } from './actions'
import { ERROR_CODES } from './client'

// The same file the Java router is tested against (common/src/test).
// Vitest runs from frontend/, and jsdom's import.meta.url is not a file URL.
const contract = JSON.parse(readFileSync(resolve(process.cwd(), '../contract/bridge.json'), 'utf8'))

describe('bridge contract', () => {
  it('lists exactly the actions the TypeScript client knows', () => {
    expect([...ACTIONS].sort()).toEqual(Object.keys(contract.actions).sort())
  })

  it('numbers errors in the same order as Java', () => {
    expect(ERROR_CODES).toEqual(contract.errors)
  })

  it('uses protocol 1', () => {
    expect(contract.protocol).toBe(1)
  })

  it('names a thread for every action', () => {
    for (const [name, spec] of Object.entries<{ thread: string }>(contract.actions)) {
      expect(['any', 'client', 'io'], name).toContain(spec.thread)
    }
  })
})
