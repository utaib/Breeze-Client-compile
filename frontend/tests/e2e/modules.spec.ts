import { expect, test } from '@playwright/test'
import { calls, open, rail, snap } from './helpers'

test.beforeEach(async ({ page }) => {
  await open(page)
  await rail(page, 'Mods').click()
})

test('module cards: search, filters and the empty result', async ({ page }, info) => {
  await expect(page.getByTestId('module-FPS')).toBeVisible()
  await snap(page, info, 'mods-modules')

  await page.getByRole('searchbox', { name: 'Search modules' }).fill('armour')
  await expect(page.locator('.module')).toHaveCount(1)
  await expect(page.getByTestId('module-Armor Status')).toBeVisible()

  // Escape inside the search clears it rather than leaving the page.
  await page.getByRole('searchbox', { name: 'Search modules' }).press('Escape')
  await expect(page.getByRole('searchbox', { name: 'Search modules' })).toHaveValue('')
  await expect(page.getByRole('heading', { level: 1, name: 'Mods' })).toBeVisible()

  await page.getByRole('tab', { name: 'Visual' }).click()
  const visual = await page.locator('.module .module-cat').allTextContents()
  expect(visual.length).toBeGreaterThan(0)
  expect(new Set(visual)).toEqual(new Set(['Visual']))

  await page.getByRole('tab', { name: 'On' }).click()
  expect(await page.locator('.module').count()).toBe(4)

  await page.getByRole('tab', { name: 'All' }).click()
  await page.getByRole('searchbox', { name: 'Search modules' }).fill('zzzz')
  await expect(page.getByText('Nothing matches "zzzz"')).toBeVisible()
  await snap(page, info, 'mods-empty-search')
})

test('toggling a module sends it to Java and survives navigation', async ({ page }) => {
  const sw = page.getByRole('switch', { name: 'CPS on' })
  await expect(sw).toHaveAttribute('aria-checked', 'false')
  await sw.click()
  await expect(sw).toHaveAttribute('aria-checked', 'true')
  await expect.poll(async () => (await calls(page, 'modules.setEnabled')).at(-1)?.params).toEqual({ name: 'CPS', enabled: true })

  await rail(page, 'Home').click()
  await expect(page.getByText('5 modules on')).toBeVisible()
  await rail(page, 'Mods').click()
  await expect(page.getByRole('switch', { name: 'CPS on' })).toHaveAttribute('aria-checked', 'true')
})

test('module settings: every control type writes a real setting', async ({ page }, info) => {
  await page.getByRole('button', { name: 'FPS settings' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'FPS' })).toBeVisible()
  await snap(page, info, 'module-settings')

  await page.getByRole('switch', { name: 'Border' }).click()
  await page.getByRole('group', { name: 'Background' }).getByRole('button', { name: 'Gradient' }).click()

  const scale = page.getByRole('slider', { name: 'Scale' })
  await scale.focus()
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('ArrowRight')
  await expect(page.locator('.row', { hasText: 'Scale' }).locator('.slider-value')).toHaveText('102%')

  const colour = page.getByRole('textbox', { name: 'Text colour' })
  await colour.fill('#FF78B2FF')
  await colour.press('Enter')

  await expect.poll(async () => (await calls(page, 'modules.setSetting')).map((c) => c.params.id)).toEqual(
    ['border', 'panel', 'scale', 'scale', 'textColor'],
  )
  const last = (await calls(page, 'modules.setSetting')).at(-1)!
  expect(last.params).toEqual({ name: 'FPS', id: 'textColor', value: '#FF78B2FF' })
})

test('Low Fire: the slider shows its value and writes each change', async ({ page }, info) => {
  await page.getByRole('searchbox', { name: 'Search modules' }).fill('fire')
  await page.getByRole('button', { name: 'Low Fire settings' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Low Fire' })).toBeVisible()

  const lower = page.getByRole('slider', { name: 'Lower by' })
  const shown = page.locator('.row', { hasText: 'Lower by' }).locator('.slider-value')
  await expect(shown).toHaveText('50%')
  await snap(page, info, 'module-low-fire')

  await lower.focus()
  await page.keyboard.press('End')
  await expect(shown).toHaveText('100%')
  await page.keyboard.press('Home')
  await expect(shown).toHaveText('0%')

  await expect.poll(async () => (await calls(page, 'modules.setSetting')).map((c) => c.params.value)).toEqual([100, 0])
  expect((await calls(page, 'modules.setSetting')).at(-1)!.params).toEqual({ name: 'Low Fire', id: 'lower', value: 0 })
})

test('an invalid colour is refused and put back', async ({ page }) => {
  await page.getByRole('button', { name: 'FPS settings' }).click()
  const colour = page.getByRole('textbox', { name: 'Text colour' })
  await colour.fill('#12')
  await expect(page.locator('.color-field.invalid')).toHaveCount(1)
  await colour.blur()
  await expect(colour).toHaveValue('#FFFFFFFF')
  expect(await calls(page, 'modules.setSetting')).toHaveLength(0)
})

test('reset asks first; Escape closes the dialog before leaving the page', async ({ page }) => {
  await page.getByRole('button', { name: 'FPS settings' }).click()
  await page.getByRole('button', { name: 'Reset' }).click()
  await expect(page.getByRole('dialog', { name: 'Reset FPS?' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toBeHidden()
  await expect(page.getByRole('heading', { level: 1, name: 'FPS' })).toBeVisible()

  await page.getByRole('button', { name: 'Reset' }).click()
  await page.getByRole('button', { name: 'Reset settings' }).click()
  await expect.poll(async () => (await calls(page, 'modules.reset')).length).toBe(1)
})

test('installed mods lists what Fabric loaded', async ({ page }, info) => {
  await page.getByRole('group', { name: 'Section' }).getByRole('button', { name: 'Installed mods' }).click()
  await expect(page.getByText('Fabric API')).toBeVisible()
  await expect(page.getByText('3 mods loaded')).toBeVisible()
  await snap(page, info, 'mods-installed')
})

test('modules show their Minecraft icon, the right square of its texture, pixel for pixel', async ({ page }, info) => {
  const fps = page.getByTestId('module-icon-FPS')
  await expect(fps).toBeVisible()
  const img = fps.locator('img')
  await expect(img).toHaveAttribute('src', /^data:image\/png;base64,/)
  expect(await img.evaluate((el) => getComputedStyle(el).imageRendering)).toBe('pixelated')

  // Ping's picture is the top-right square of a sheet twice its size: the
  // sheet is drawn twice as wide, shifted one square to the left.
  const frame = page.getByTestId('module-icon-Ping').locator('.mc-frame')
  const f = await frame.boundingBox()
  const i = await frame.locator('img').boundingBox()
  expect(f && i).toBeTruthy()
  expect(Math.abs(i!.width - 2 * f!.width)).toBeLessThan(1)
  expect(Math.abs(i!.x - (f!.x - f!.width))).toBeLessThan(1)
  expect(Math.abs(i!.y - f!.y)).toBeLessThan(1)

  // A module the game had no picture for keeps its category symbol.
  await expect(page.getByTestId('module-Zoom').locator('.module-icon svg')).toBeVisible()
  await snap(page, info, 'mods-icons')

  // The module's own page shows the same picture, larger.
  await page.getByRole('button', { name: 'FPS settings' }).click()
  await expect(page.getByTestId('module-icon-FPS')).toHaveClass(/large/)

  // Asked for once, not on every visit.
  await rail(page, 'Mods').click()
  await expect(page.getByTestId('module-icon-FPS')).toBeVisible()
  expect((await calls(page, 'modules.icons')).length).toBe(1)
})
