import { useEffect, useState } from 'react'
import { useApp } from '../app/state'
import type { ModuleIcon } from '../bridge/types'
import { Icon, type IconName } from './icons'

/**
 * Each module's Minecraft icon, asked for once and kept for the session: the
 * textures do not change while the game runs, and every card of the Mods page
 * would otherwise ask again on each visit.
 */
let cached: Record<string, ModuleIcon> | null = null
let pending: Promise<Record<string, ModuleIcon>> | null = null

export function useModuleIcons(): Record<string, ModuleIcon> {
  const { call } = useApp()
  const [icons, setIcons] = useState<Record<string, ModuleIcon>>(cached ?? {})
  useEffect(() => {
    if (cached) return
    let live = true
    pending ??= call('modules.icons')
      .then((r) => (cached = r.icons ?? {}))
      .catch(() => {
        // The category symbol stays; the next visit asks again.
        pending = null
        return {}
      })
    pending.then((i) => {
      if (live) setIcons(i)
    })
    return () => {
      live = false
    }
  }, [call])
  return icons
}

/**
 * A module's picture: its Minecraft icon when the game has one, or its
 * category's symbol. The icon is a square of a texture, cut out by sizing the
 * whole texture inside a clipping box, and drawn pixel for pixel, as
 * Minecraft draws items.
 */
export function ModuleGlyph({ name, tone, symbol, icon, large = false }: {
  name: string
  tone: string
  symbol: IconName
  icon?: ModuleIcon
  large?: boolean
}) {
  if (!icon) {
    const I = Icon[symbol]
    return <span className={`module-icon tone-${tone}${large ? ' large' : ''}`}><I /></span>
  }
  const pct = (n: number) => `${(n / icon.size) * 100}%`
  return (
    <span className={`module-icon mc tone-${tone}${large ? ' large' : ''}`} data-testid={`module-icon-${name}`}>
      <span className="mc-frame">
        <img
          src={icon.src}
          alt=""
          draggable={false}
          style={{ width: pct(icon.w), height: pct(icon.h), left: pct(-icon.x), top: pct(-icon.y) }}
        />
      </span>
    </span>
  )
}
