import { expect, test } from '@playwright/test'
import { calls, open, rail, snap } from './helpers'

test('title menu: home, preview marker, and real play actions', async ({ page }, info) => {
  const { assertClean } = await open(page)
  await expect(page.getByRole('heading', { name: 'PreviewPlayer' })).toBeVisible()
  await expect(page.getByText('Preview data')).toBeVisible()
  await snap(page, info, 'home-title')

  await page.getByTestId('play-singleplayer').click()
  await page.getByTestId('play-multiplayer').click()
  await page.getByTestId('play-last-server').click()
  await rail(page, 'Singleplayer').click()
  await rail(page, 'Multiplayer').click()
  await page.getByRole('button', { name: 'Options' }).click()

  await expect.poll(() => calls(page, 'game.options').then((c) => c.length)).toBe(1)
  expect(await calls(page, 'game.singleplayer')).toHaveLength(2)
  expect(await calls(page, 'game.multiplayer')).toHaveLength(2)
  expect((await calls(page, 'game.joinServer'))[0]?.params).toEqual({ address: 'preview.invalid', name: 'Preview server' })
  assertClean()
})

test('every rail destination opens its page and marks itself current', async ({ page }) => {
  const { assertClean } = await open(page)
  const pages: [string, string][] = [
    ['Mods', 'Mods'], ['Wardrobe', 'Wardrobe'], ['Friends', 'Friends'], ['Hosting', 'Hosting'], ['Settings', 'Settings'], ['Home', 'PreviewPlayer'],
  ]
  for (const [item, heading] of pages) {
    await rail(page, item).click()
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
    await expect(rail(page, item)).toHaveAttribute('aria-current', 'page')
  }
  assertClean()
})

test('Back and Escape walk up one level and stop at Home', async ({ page }) => {
  const { assertClean } = await open(page)
  const back = page.getByRole('button', { name: 'Back', exact: true })
  await expect(back).toBeDisabled()

  await rail(page, 'Mods').click()
  await page.getByRole('button', { name: 'FPS settings' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'FPS' })).toBeVisible()
  await expect(page.locator('.crumb-pg')).toHaveText('Mods / FPS')

  await page.keyboard.press('Escape')
  await expect(page.getByRole('heading', { level: 1, name: 'Mods' })).toBeVisible()
  await back.click()
  await expect(page.getByRole('heading', { name: 'PreviewPlayer' })).toBeVisible()

  // At the root Escape does nothing: it must never close the title menu.
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('heading', { name: 'PreviewPlayer' })).toBeVisible()
  expect(await calls(page, 'ui.close')).toHaveLength(0)
  expect(await calls(page, 'game.quit')).toHaveLength(0)
  assertClean()
})

test('Java escape protocol: every key.escape is answered with ui.escapeAck', async ({ page }) => {
  await open(page)
  await rail(page, 'Settings').click()
  await page.evaluate(() => window.__breezeEvent!({ type: 'key.escape' }))
  await expect(page.getByRole('heading', { name: 'PreviewPlayer' })).toBeVisible()
  await page.evaluate(() => window.__breezeEvent!({ type: 'key.escape' }))
  await expect.poll(async () => (await calls(page, 'ui.escapeAck')).map((c) => c.params.handled)).toEqual([true, false])
})

test('Quit asks first, and Escape cancels the question', async ({ page }) => {
  const { assertClean } = await open(page)
  await rail(page, 'Quit').click()
  const dialog = page.getByRole('dialog', { name: 'Quit Minecraft?' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Quit game' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  expect(await calls(page, 'game.quit')).toHaveLength(0)

  await rail(page, 'Quit').click()
  await page.getByRole('button', { name: 'Quit game' }).click()
  await expect.poll(() => calls(page, 'game.quit').then((c) => c.length)).toBe(1)
  assertClean()
})

test('in-game menu: resume, game menu and HUD editor', async ({ page }, info) => {
  const { assertClean } = await open(page, '?context=ingame')
  await expect(page.getByText('Preview world')).toBeVisible()
  await expect(rail(page, 'Singleplayer')).toHaveCount(0)
  await expect(rail(page, 'Quit')).toHaveCount(0)
  await snap(page, info, 'home-ingame')

  await page.getByTestId('resume').click()
  await page.getByRole('button', { name: 'Edit HUD' }).click()
  await page.locator('.bar').getByRole('button', { name: 'Game menu' }).click()
  await rail(page, 'Close').click()
  await expect.poll(() => calls(page, 'ui.close').then((c) => c.length)).toBe(2)
  expect(await calls(page, 'hud.openEditor')).toHaveLength(1)
  expect(await calls(page, 'game.pauseMenu')).toHaveLength(1)
  assertClean()
})

test('the route is reported so a reopened menu can start where it left off', async ({ page }) => {
  await open(page)
  await rail(page, 'Friends').click()
  await expect.poll(async () => (await calls(page, 'ui.route')).at(-1)?.params.route).toBe('friends')
})

test('keyboard only: Tab reaches the rail and Enter activates it', async ({ page }) => {
  await open(page)
  await page.keyboard.press('Tab')
  let guard = 0
  while (!(await rail(page, 'Mods').evaluate((el) => el === document.activeElement)) && guard++ < 20) {
    await page.keyboard.press('Tab')
  }
  await expect(rail(page, 'Mods')).toBeFocused()
  const outline = await rail(page, 'Mods').evaluate((el) => getComputedStyle(el).outlineStyle)
  expect(outline).not.toBe('none')
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { level: 1, name: 'Mods' })).toBeVisible()
})
