import { expect, type Page, type TestInfo } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

/** Opens the page and fails the test on any console error or page exception. */
export async function open(page: Page, query = '') {
  const problems: string[] = []
  page.on('console', (m) => {
    if (m.type() === 'error') problems.push(`console: ${m.text()}`)
  })
  page.on('pageerror', (e) => problems.push(`exception: ${e.message}`))
  page.on('requestfailed', (r) => problems.push(`request failed: ${r.url()}`))
  await page.goto(`/${query}`)
  await expect(page.locator('.rail')).toBeVisible()
  return {
    assertClean: () => expect(problems, problems.join('\n')).toEqual([]),
  }
}

export async function calls(page: Page, action?: string) {
  const all = await page.evaluate(() => window.__breezeCalls ?? [])
  return action ? all.filter((c) => c.action === action) : all
}

export function rail(page: Page, name: string) {
  return page.locator('nav.rail').getByRole('button', { name, exact: true })
}

/** Browser evidence, one folder per engine. Never presented as in-game. */
export async function snap(page: Page, info: TestInfo, name: string) {
  const dir = join('test-results', 'screens', info.project.name)
  mkdirSync(dir, { recursive: true })
  await page.waitForTimeout(400)
  await page.screenshot({ path: join(dir, `${name}.png`) })
}

/** Nothing may scroll sideways or spill past the window. */
export async function assertNoOverflow(page: Page) {
  const overflow = await page.evaluate(() => {
    const doc = document.scrollingElement!
    const out: string[] = []
    if (doc.scrollWidth > window.innerWidth + 1) out.push(`document ${doc.scrollWidth} > ${window.innerWidth}`)
    for (const el of document.querySelectorAll<HTMLElement>('.content, .main, .rail, .bar')) {
      if (el.scrollWidth > el.clientWidth + 1) out.push(`${el.className} scrolls sideways (${el.scrollWidth} > ${el.clientWidth})`)
    }
    const rail = document.querySelector('.rail')!.getBoundingClientRect()
    if (rail.bottom > window.innerHeight + 1) out.push('rail taller than the window')
    return out
  })
  expect(overflow).toEqual([])
}
