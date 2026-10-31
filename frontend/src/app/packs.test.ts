import { describe, expect, it } from 'vitest'
import type { PackInfo } from '../bridge/types'
import { move, offPacks, sameOrder, turnOff, turnOn } from './packs'

const pack = (id: string, over: Partial<PackInfo> = {}): PackInfo => ({
  id, title: id, description: '', compatible: true, compatibility: 'compatible',
  required: false, fixed: false, top: true, folder: id.startsWith('file/'), on: false, picture: null, ...over,
})

const PACKS = [
  pack('vanilla', { required: true, fixed: true, top: false }),
  pack('server', { fixed: true, top: true }),
  pack('file/A.zip', { description: 'Faithful 32x' }),
  pack('file/B.zip'),
  pack('programmer_art', { title: 'Programmer Art' }),
]

describe('resource pack list', () => {
  it('turns a pack on at the top, under a fixed top pack', () => {
    expect(turnOn(['file/A.zip', 'vanilla'], 'file/B.zip', PACKS)).toEqual(['file/B.zip', 'file/A.zip', 'vanilla'])
    expect(turnOn(['server', 'file/A.zip', 'vanilla'], 'file/B.zip', PACKS)).toEqual(['server', 'file/B.zip', 'file/A.zip', 'vanilla'])
    expect(turnOn(['file/A.zip'], 'file/A.zip', PACKS)).toEqual(['file/A.zip'])
  })

  it('never turns a required pack off', () => {
    expect(turnOff(['file/A.zip', 'vanilla'], 'vanilla', PACKS)).toEqual(['file/A.zip', 'vanilla'])
    expect(turnOff(['file/A.zip', 'vanilla'], 'file/A.zip', PACKS)).toEqual(['vanilla'])
  })

  it('moves only past packs that may move', () => {
    const order = ['server', 'file/A.zip', 'file/B.zip', 'vanilla']
    expect(move(order, 'file/B.zip', -1, PACKS)).toEqual(['server', 'file/B.zip', 'file/A.zip', 'vanilla'])
    expect(move(order, 'file/A.zip', -1, PACKS)).toBe(order)
    expect(move(order, 'file/B.zip', 1, PACKS)).toBe(order)
    expect(move(order, 'vanilla', -1, PACKS)).toBe(order)
  })

  it('lists the packs that are off, searchable', () => {
    expect(offPacks(PACKS, ['vanilla', 'server'], '').map((p) => p.id)).toEqual(['file/A.zip', 'file/B.zip', 'programmer_art'])
    expect(offPacks(PACKS, ['vanilla'], 'faithful').map((p) => p.id)).toEqual(['file/A.zip'])
    expect(offPacks(PACKS, ['vanilla'], 'PROGRAMMER').map((p) => p.id)).toEqual(['programmer_art'])
    expect(sameOrder(['a', 'b'], ['a', 'b'])).toBe(true)
    expect(sameOrder(['a', 'b'], ['b', 'a'])).toBe(false)
  })
})
