/**
 * The wallpaper Java sends with app.hello on the title menu goes into a CSS
 * url(); only a base64 data: URI of a JPEG or PNG is accepted, anything else
 * falls back to the bundled backdrop.
 */
export function wallpaperUrl(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null
  return /^data:image\/(jpeg|png);base64,[A-Za-z0-9+/]+={0,2}$/.test(value) ? value : null
}
