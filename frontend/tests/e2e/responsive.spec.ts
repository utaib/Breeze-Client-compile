import { expect, test } from '@playwright/test'
import { assertNoOverflow, calls, open, rail, snap } from './helpers'

// Minecraft's default window, common desktop sizes, and a 4:3 and ultrawide.
const SIZES: [string, number, number][] = [
  ['854x480', 854, 480],
  ['1280x720', 1280, 720],
  ['1920x1080', 1920, 1080],
  ['2560x1440', 2560, 1440],
  ['1024x768', 1024, 768],
  ['3440x1440', 3440, 1440],
]

const PAGES = ['Home', 'Mods', 'Wardrobe', 'Friends', 'Hosting', 'Settings']

for (const [label, width, height] of SIZES) {
  test(`no clipping or sideways scroll at ${label}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height })
    const { assertClean } = await open(page)
    for (const name of PAGES) {
      await rail(page, name).click()
      await expect(page.locator('main.content')).toBeVisible()
      await assertNoOverflow(page)
      // Every rail button stays inside the window and clickable.
      for (const b of await page.locator('nav.rail button').all()) {
        const box = (await b.boundingBox())!
        expect(box.y + box.height, `${name} rail button at ${label}`).toBeLessThanOrEqual(height + 1)
      }
      if (label === '854x480' || label === '1920x1080') await snap(page, info, `${label}-${name.toLowerCase()}`)
    }
    assertClean()
  })
}

test('resizing the window rescales without reloading', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 })
  await open(page)
  await rail(page, 'Mods').click()
  const scale = () => page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--ui-scale').trim())
  expect(await scale()).toBe('1.05')
  await page.setViewportSize({ width: 1920, height: 1080 })
  await expect.poll(scale).toBe('1.6')
  await page.setViewportSize({ width: 854, height: 480 })
  await expect.poll(scale).toBe('0.85')
  // Still on the same page: a resize is not a navigation.
  await expect(page.getByRole('heading', { level: 1, name: 'Mods' })).toBeVisible()
  await assertNoOverflow(page)
})

test('navigation stays cheap: no long tasks while moving between pages', async ({ page }) => {
  await open(page)
  await page.evaluate(() => {
    ;(window as unknown as { __long: number[] }).__long = []
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) (window as unknown as { __long: number[] }).__long.push(e.duration)
    }).observe({ type: 'longtask', buffered: false })
  })
  for (let round = 0; round < 3; round++) {
    for (const name of PAGES) await rail(page, name).click()
  }
  await page.waitForTimeout(300)
  const long = await page.evaluate(() => (window as unknown as { __long: number[] }).__long)
  // Recorded for the evidence log; a single task over 200 ms fails the test.
  info(`long tasks: ${long.length ? long.map((d) => d.toFixed(0)).join(', ') + ' ms' : 'none'}`)
  expect(long.filter((d) => d > 200)).toEqual([])

  function info(msg: string) {
    test.info().annotations.push({ type: 'perf', description: msg })
  }
})

test('Armor HUD settings fit the smallest window', async ({ page }, info) => {
  await page.setViewportSize({ width: 854, height: 480 })
  await open(page)
  await rail(page, 'Mods').click()
  await page.getByRole('button', { name: 'Armor Status settings' }).click()
  // Five formats: more than a segmented control holds, so it is a stepper.
  const show = page.getByRole('group', { name: 'Show' })
  await show.scrollIntoViewIfNeeded()
  await expect(show).toContainText('Percent')
  await snap(page, info, 'module-armor-854')
  for (const b of await page.locator('.seg button').all()) {
    expect(await b.evaluate((el) => el.scrollHeight <= el.clientHeight + 1), await b.innerText()).toBe(true)
  }
  for (const row of await page.locator('.row').all()) {
    expect(await row.evaluate((el) => el.scrollWidth <= el.clientWidth + 1), await row.innerText()).toBe(true)
  }
  await show.getByRole('button', { name: 'Previous' }).click()
  await expect(show).toContainText('Bar and percent')
  await expect.poll(async () => (await calls(page, 'modules.setSetting')).at(-1)?.params)
    .toEqual({ name: 'Armor Status', id: 'format', value: 'Bar and percent' })
  await page.getByRole('group', { name: 'Orientation' }).getByRole('button', { name: 'Horizontal' }).click()
  await expect.poll(async () => (await calls(page, 'modules.setSetting')).at(-1)?.params)
    .toEqual({ name: 'Armor Status', id: 'orientation', value: 'Horizontal' })
  // The Hotbar look (2.14.0): the armour in Minecraft's own hotbar slots.
  await page.getByRole('group', { name: 'Look' }).getByRole('button', { name: 'Hotbar' }).click()
  await expect.poll(async () => (await calls(page, 'modules.setSetting')).at(-1)?.params)
    .toEqual({ name: 'Armor Status', id: 'look', value: 'Hotbar' })
})

test('segmented controls never wrap their labels', async ({ page }) => {
  await page.setViewportSize({ width: 854, height: 480 })
  await open(page)
  await rail(page, 'Mods').click()
  // The buttons have a fixed height, so a wrapped label overflows them.
  for (const b of await page.locator('.seg button').all()) {
    expect(await b.evaluate((el) => el.scrollHeight <= el.clientHeight + 1), await b.innerText()).toBe(true)
  }
})
