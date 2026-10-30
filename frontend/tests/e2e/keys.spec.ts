import { expect, test, type Page } from '@playwright/test'
import { calls, open, rail, snap } from './helpers'

// Breeze's keys in the menu: the same bindings as Minecraft's Controls. A
// change asks Java for the next key pressed (keys.capture); the preview
// stands in for Java and takes the next key the browser sees.

const section = (page: Page, name: string) => page.getByRole('group', { name: 'Section' }).getByRole('button', { name, exact: true })
const row = (page: Page, id: string) => page.locator(`.row[data-anchor="${id}"]`)
const cap = (page: Page, id: string) => row(page, id).locator('.keycap')

async function openKeys(page: Page) {
  const run = await open(page)
  await rail(page, 'Mods').click()
  await section(page, 'Keys').click()
  await expect(row(page, 'key.breeze.zoom')).toBeVisible()
  return run
}

test('every Breeze key in one list, with keys shared with Minecraft marked', async ({ page }, info) => {
  const { assertClean } = await openKeys(page)
  await expect(row(page, 'key.breeze.menu')).toContainText('Open the Breeze menu')
  await expect(cap(page, 'key.breeze.menu')).toHaveText('Right Shift')
  await expect(cap(page, 'key.breeze.music')).toHaveText('M')
  // Zoom's C is also Minecraft's Save Hotbar Activator.
  await expect(cap(page, 'key.breeze.zoom')).toHaveClass(/conflict/)
  await expect(row(page, 'key.breeze.zoom')).toContainText('C is also Save Hotbar Activator')
  await expect(cap(page, 'key.breeze.fps')).toHaveText('No key')

  await page.getByRole('group', { name: 'Show' }).getByRole('button', { name: 'Shared (1)' }).click()
  await expect(page.locator('.rows .row')).toHaveCount(1)
  await page.getByRole('group', { name: 'Show' }).getByRole('button', { name: 'With a key' }).click()
  // The menu, Music, Zoom and Free Look (Left Alt).
  await expect(page.locator('.rows .row')).toHaveCount(4)
  await page.getByRole('group', { name: 'Show' }).getByRole('button', { name: 'All' }).click()

  // Search by name, or by the key itself.
  await page.getByRole('searchbox', { name: 'Search keys' }).fill('m')
  await expect(row(page, 'key.breeze.music')).toBeVisible()
  await page.getByRole('searchbox', { name: 'Search keys' }).fill('right shift')
  await expect(page.locator('.rows .row')).toHaveCount(1)
  await page.getByRole('searchbox', { name: 'Search keys' }).fill('')
  await snap(page, info, 'keys-list')
  assertClean()
})

test('changing a key takes the next key pressed; reset and remove work', async ({ page }, info) => {
  const { assertClean } = await openKeys(page)
  await cap(page, 'key.breeze.zoom').click()
  await expect(cap(page, 'key.breeze.zoom')).toHaveAttribute('data-state', 'waiting')
  await expect(cap(page, 'key.breeze.zoom')).toHaveText('Press a key')
  expect((await calls(page, 'keys.capture')).at(-1)?.params).toEqual({ id: 'key.breeze.zoom' })
  await snap(page, info, 'keys-waiting')
  await page.keyboard.press('KeyR')
  await expect(cap(page, 'key.breeze.zoom')).toHaveText('R')
  await expect(cap(page, 'key.breeze.zoom')).not.toHaveClass(/conflict/)
  // The page never saw the R: the search box did not receive it.
  await expect(page.getByRole('searchbox', { name: 'Search keys' })).toHaveValue('')

  await row(page, 'key.breeze.zoom').getByRole('button', { name: 'Put Zoom back on C' }).click()
  await expect(cap(page, 'key.breeze.zoom')).toHaveText('C')
  await expect.poll(async () => (await calls(page, 'keys.reset')).at(-1)?.params).toEqual({ id: 'key.breeze.zoom', to: 'default' })
  await row(page, 'key.breeze.zoom').getByRole('button', { name: "Remove Zoom's key" }).click()
  await expect(cap(page, 'key.breeze.zoom')).toHaveText('No key')

  // Two Breeze keys on one key are marked on both.
  await cap(page, 'key.breeze.fps').click()
  await expect(cap(page, 'key.breeze.fps')).toHaveAttribute('data-state', 'waiting')
  await page.keyboard.press('KeyM')
  await expect(row(page, 'key.breeze.fps')).toContainText('M is also Music player')
  await expect(row(page, 'key.breeze.music')).toContainText('M is also FPS')
  assertClean()
})

test('Escape while waiting cancels the change and stays on the page', async ({ page }) => {
  const { assertClean } = await openKeys(page)
  await cap(page, 'key.breeze.zoom').click()
  await expect(cap(page, 'key.breeze.zoom')).toHaveAttribute('data-state', 'waiting')
  await page.keyboard.press('Escape')
  await expect(cap(page, 'key.breeze.zoom')).toHaveText('C')
  await expect(page.getByRole('heading', { level: 1, name: 'Mods' })).toBeVisible()
  await expect(section(page, 'Keys')).toHaveAttribute('aria-pressed', 'true')
  // Clicking the waiting key cancels too.
  await cap(page, 'key.breeze.zoom').click()
  await expect(cap(page, 'key.breeze.zoom')).toHaveAttribute('data-state', 'waiting')
  await cap(page, 'key.breeze.zoom').click()
  await expect(cap(page, 'key.breeze.zoom')).toHaveText('C')
  await expect.poll(async () => (await calls(page, 'keys.cancel')).length).toBeGreaterThan(0)
  assertClean()
})

test('a module page changes its own key, and the module list shows it', async ({ page }, info) => {
  const { assertClean } = await open(page)
  await rail(page, 'Mods').click()
  await page.getByRole('button', { name: 'Zoom settings' }).click()
  await expect(row(page, 'key')).toContainText('C is also Save Hotbar Activator')
  await cap(page, 'key').click()
  await expect(cap(page, 'key')).toHaveAttribute('data-state', 'waiting')
  await page.keyboard.press('KeyV')
  await expect(cap(page, 'key')).toHaveText('V')
  await expect(row(page, 'key')).toContainText('Turns Zoom on and off')
  await snap(page, info, 'module-key')
  await page.getByRole('button', { name: 'Back', exact: true }).click()
  await expect(page.getByTestId('module-Zoom').locator('.module-key')).toHaveText('V')
  assertClean()
})

test('leaving while waiting stops Java waiting', async ({ page }) => {
  const { assertClean } = await openKeys(page)
  await cap(page, 'key.breeze.zoom').click()
  await expect(cap(page, 'key.breeze.zoom')).toHaveAttribute('data-state', 'waiting')
  const before = (await calls(page, 'keys.cancel')).length
  await rail(page, 'Home').click()
  await expect.poll(async () => (await calls(page, 'keys.cancel')).length).toBe(before + 1)
  // The next key goes to the page again, not to a binding.
  await rail(page, 'Mods').click()
  await section(page, 'Keys').click()
  await expect(cap(page, 'key.breeze.zoom')).toHaveText('C')
  assertClean()
})
