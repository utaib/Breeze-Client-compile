import { expect, test } from '@playwright/test'
import { calls, open, rail } from './helpers'

// The mod keeps one browser loaded between opens of the menu (WarmBrowser):
// app.hide removes the menu, app.visit draws a fresh one on the route Java
// names, and the page says ui.shown once it has drawn.
test('a kept page draws nothing between visits and a fresh menu on each visit', async ({ page }) => {
  const { assertClean } = await open(page)
  await expect.poll(() => calls(page, 'ui.shown').then((c) => c.length)).toBe(1)

  await rail(page, 'Settings').click()
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible()

  await page.evaluate(() => window.__breezeEvent?.({ type: 'app.hide' }))
  await expect(page.locator('.rail')).toHaveCount(0)
  expect(await page.locator('#root').evaluate((el) => el.childElementCount)).toBe(0)

  await page.evaluate(() => window.__breezeEvent?.({ type: 'app.visit' }))
  await expect(page.locator('.rail')).toBeVisible()
  await expect.poll(() => calls(page, 'ui.shown').then((c) => c.length)).toBe(2)
  // Each visit asks Java again for its context and starting route.
  expect((await calls(page, 'app.hello')).length).toBeGreaterThanOrEqual(2)
  assertClean()
})
