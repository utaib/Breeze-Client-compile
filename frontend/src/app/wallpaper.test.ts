import { describe, expect, it } from 'vitest'
import { wallpaperUrl } from './wallpaper'

describe('wallpaperUrl', () => {
  it('takes a JPEG or PNG data URI', () => {
    expect(wallpaperUrl('data:image/jpeg;base64,/9j/4AAQ')).toBe('data:image/jpeg;base64,/9j/4AAQ')
    expect(wallpaperUrl('data:image/png;base64,iVBORw0KGgo=')).toBe('data:image/png;base64,iVBORw0KGgo=')
  })

  it('refuses anything that could break out of url() or load something else', () => {
    for (const bad of [
      null, undefined, '', 'https://example.com/a.jpg', 'data:image/svg+xml;base64,PHN2Zz4=',
      'data:image/jpeg;base64,abc) ; background:red', "data:image/jpeg;base64,ab'c", 'data:text/html;base64,PGI+',
    ]) {
      expect(wallpaperUrl(bad as string | null | undefined)).toBeNull()
    }
  })
})
