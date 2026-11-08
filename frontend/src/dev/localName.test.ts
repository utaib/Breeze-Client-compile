import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { cleanLocalName, LOCAL_NAME_MAX } from './localName'

// The same examples the game's Java is tested against.
const contract = JSON.parse(readFileSync(resolve(process.cwd(), '../contract/local-name.json'), 'utf8'))

describe('local display name rule (preview fixture)', () => {
  it('keeps and tidies what the game keeps', () => {
    expect(LOCAL_NAME_MAX).toBe(contract.max)
    for (const [raw, clean] of contract.accepted as [string, string][]) expect(cleanLocalName(raw)).toBe(clean)
  })

  it('refuses what the game refuses, with the same words', () => {
    for (const [raw, message] of contract.refused as [string, string][]) {
      expect(() => cleanLocalName(raw), JSON.stringify(raw)).toThrow(message)
    }
  })
})
