// Inline SVG icons. The shared ones are copied from the launcher's icon set
// (breeze launcher/src/ui/icons.jsx) so both surfaces speak the same visual
// language; the rest are drawn to the same 24px grid and 1.7 stroke.
import type { SVGProps } from 'react'

type P = SVGProps<SVGSVGElement>
const line = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.7, strokeLinecap: 'round', strokeLinejoin: 'round' } as const

export const Icon = {
  Home: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M3 10.5 12 3l9 7.5" /><path d="M5 9.5V20a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9.5" /></svg>,
  Play: (p: P) => <svg viewBox="0 0 24 24" fill="currentColor" {...p}><path d="M8 5.14v14l11-7z" /></svg>,
  Globe: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" /></svg>,
  Server: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><rect x="3" y="4" width="18" height="6" rx="2" /><rect x="3" y="14" width="18" height="6" rx="2" /><path d="M7 7h.01M7 17h.01M11 7h6M11 17h6" /></svg>,
  Hanger: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M9.5 6.5a2.5 2.5 0 1 1 3.4 2.33c-.55.21-.9.73-.9 1.32V11" /><path d="m12 11 8.4 5.6a1.4 1.4 0 0 1-.78 2.56H4.38a1.4 1.4 0 0 1-.78-2.56Z" /></svg>,
  Users: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></svg>,
  Grid: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /></svg>,
  Sliders: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6" /></svg>,
  Gear: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z" /></svg>,
  Power: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M18.36 6.64a9 9 0 1 1-12.73 0M12 2v10" /></svg>,
  Back: (p: P) => <svg viewBox="0 0 24 24" {...line} strokeWidth={2} {...p}><path d="M15 18l-6-6 6-6" /></svg>,
  Chevron: (p: P) => <svg viewBox="0 0 24 24" {...line} strokeWidth={2} {...p}><path d="m9 18 6-6-6-6" /></svg>,
  X: (p: P) => <svg viewBox="0 0 24 24" {...line} strokeWidth={2} {...p}><path d="M18 6 6 18M6 6l12 12" /></svg>,
  Check: (p: P) => <svg viewBox="0 0 24 24" {...line} strokeWidth={2} {...p}><path d="m20 6-11 11-5-5" /></svg>,
  Plus: (p: P) => <svg viewBox="0 0 24 24" {...line} strokeWidth={2} {...p}><path d="M12 5v14M5 12h14" /></svg>,
  Search: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" /></svg>,
  Reset: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M3 12a9 9 0 1 0 2.64-6.36L3 8" /><path d="M3 3v5h5" /></svg>,
  Layout: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M9 21V9" /></svg>,
  Monitor: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><rect x="2" y="3" width="20" height="14" rx="2" /><path d="M8 21h8M12 17v4" /></svg>,
  Eye: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8Z" /><circle cx="12" cy="12" r="3" /></svg>,
  Chat: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z" /></svg>,
  Bolt: (p: P) => <svg viewBox="0 0 24 24" {...line} strokeWidth={2} {...p}><path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" /></svg>,
  Sword: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M14.5 17.5 3 6V3h3l11.5 11.5M13 19l6-6M16 16l4 4M19 21l2-2" /></svg>,
  Wrench: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76Z" /></svg>,
  Package: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="m16.5 9.4-9-5.19M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z" /><path d="M3.27 6.96 12 12.01l8.73-5.05M12 22.08V12" /></svg>,
  External: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14 21 3" /></svg>,
  Signal: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M2 20h.01M7 20v-4M12 20v-8M17 20V8M22 4v16" /></svg>,
  Alert: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><circle cx="12" cy="12" r="9" /><path d="M12 8v4M12 16h.01" /></svg>,
  Keyboard: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><rect x="2" y="6" width="20" height="12" rx="2" /><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M8 14h8" /></svg>,
  UserPlus: (p: P) => <svg viewBox="0 0 24 24" {...line} {...p}><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M19 8v6M22 11h-6" /></svg>,
  WindCharge: (p: P) => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" {...p}><circle cx="12" cy="12" r="9" opacity=".28" /><path d="M4.5 9.5h9a2.5 2.5 0 1 0-2.5-2.5" /><path d="M3.5 14h11a2.5 2.5 0 1 1-2.5 2.5" /><path d="M17.5 11.8h2.8" opacity=".55" /></svg>,
  Spin: (p: P) => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} className="spin" {...p}><path d="M21 12a9 9 0 1 1-6.2-8.6" /></svg>,
}

export type IconName = keyof typeof Icon
