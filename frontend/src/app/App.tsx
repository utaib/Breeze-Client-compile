import { useEffect } from 'react'
import { Friends } from '../pages/Friends'
import { Home } from '../pages/Home'
import { Hosting } from '../pages/Hosting'
import { ModuleDetail } from '../pages/ModuleDetail'
import { Mods } from '../pages/Mods'
import { Settings } from '../pages/Settings'
import { Wardrobe } from '../pages/Wardrobe'
import type { UiSettings } from '../bridge/types'
import { Shell } from './Shell'
import { useApp } from './state'

/**
 * Automatic interface scale: 1 at roughly a 1100x680 window, growing with the
 * window and never shrinking below what stays readable at Minecraft's default
 * 854x480. The browser is sized in real framebuffer pixels, so this is also
 * what adapts to high-DPI screens and fullscreen.
 */
export function autoScale(width: number, height: number): number {
  const raw = Math.min(width / 1100, height / 680)
  const clamped = Math.min(2.5, Math.max(0.85, raw))
  return Math.round(clamped * 20) / 20
}

export function effectiveScale(settings: UiSettings, width: number, height: number): number {
  const base = autoScale(width, height)
  return settings.uiScale > 0 ? Math.round(base * settings.uiScale) / 100 : base
}

/** Apply settings as attributes the token CSS keys off. */
function useDocumentSettings() {
  const { settings, hello } = useApp()
  useEffect(() => {
    const root = document.documentElement
    root.dataset.theme = settings.theme
    root.dataset.accent = settings.accent
    root.dataset.solid = String(settings.reduceTransparency)
    root.dataset.motion = settings.animations ? 'on' : 'off'
    root.classList.toggle('ctx-title', hello.context === 'title')
    root.classList.toggle('ctx-ingame', hello.context === 'ingame')
    const apply = () => root.style.setProperty('--ui-scale', String(effectiveScale(settings, window.innerWidth, window.innerHeight)))
    apply()
    window.addEventListener('resize', apply)
    return () => window.removeEventListener('resize', apply)
  }, [settings, hello.context])
}

export function App() {
  useDocumentSettings()
  const { route } = useApp()
  let page
  switch (route.name) {
    case 'home': page = <Home />; break
    case 'mods': page = <Mods tab={route.tab} />; break
    case 'module': page = <ModuleDetail name={route.module} />; break
    case 'wardrobe': page = <Wardrobe />; break
    case 'friends': page = <Friends />; break
    case 'hosting': page = <Hosting />; break
    case 'settings': page = <Settings />; break
  }
  return <Shell>{page}</Shell>
}
