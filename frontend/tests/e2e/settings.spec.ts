import { expect, test } from '@playwright/test'
import { calls, open, rail, snap } from './helpers'

test.beforeEach(async ({ page }) => {
  await open(page)
  await rail(page, 'Settings').click()
})

test('themes and accents repaint the whole interface', async ({ page }, info) => {
  const html = page.locator('html')
  await expect(html).toHaveAttribute('data-theme', 'glass')
  await snap(page, info, 'settings')

  for (const theme of ['black', 'white', 'midnight', 'abyss', 'glass']) {
    await page.getByTestId(`theme-${theme}`).click()
    await expect(html).toHaveAttribute('data-theme', theme)
    await expect(page.getByTestId(`theme-${theme}`)).toHaveAttribute('aria-checked', 'true')
    if (theme === 'white') await snap(page, info, 'settings-white')
  }

  const railBg = () => page.locator('.rail').evaluate((el) => getComputedStyle(el).backgroundColor)
  await page.getByTestId('theme-white').click()
  const light = await railBg()
  await page.getByTestId('theme-black').click()
  expect(await railBg()).not.toBe(light)

  await page.getByRole('radio', { name: 'Green', exact: true }).click()
  await expect(html).toHaveAttribute('data-accent', 'green')
  // The switch eases between colours, so wait for it to settle.
  await expect.poll(() => page.getByRole('switch', { name: 'Animations' }).evaluate((el) => getComputedStyle(el).backgroundColor))
    .toBe('rgb(61, 214, 140)')

  const sent = (await calls(page, 'settings.set')).map((c) => c.params)
  expect(sent).toContainEqual({ key: 'accent', value: 'green' })
  expect(sent).toContainEqual({ key: 'theme', value: 'black' })
})

test('interface size: automatic, then custom scales the root', async ({ page }) => {
  const scale = () => page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--ui-scale').trim())
  expect(await scale()).toBe('1.05')
  await page.getByRole('group', { name: 'Interface size mode' }).getByRole('button', { name: 'Custom' }).click()
  const slider = page.getByRole('slider', { name: 'Interface size' })
  await slider.focus()
  for (let i = 0; i < 4; i++) await page.keyboard.press('ArrowRight')
  await expect.poll(scale).toBe('1.26')
  const px = await page.evaluate(() => getComputedStyle(document.documentElement).fontSize)
  expect(parseFloat(px)).toBeCloseTo(16 * 1.26, 1)
  await page.getByRole('group', { name: 'Interface size mode' }).getByRole('button', { name: 'Automatic' }).click()
  await expect.poll(scale).toBe('1.05')
})

test('motion and transparency switches reach the document', async ({ page }) => {
  const html = page.locator('html')
  await page.getByRole('switch', { name: 'Animations' }).click()
  await expect(html).toHaveAttribute('data-motion', 'off')
  await page.getByRole('switch', { name: 'Reduce transparency' }).click()
  await expect(html).toHaveAttribute('data-solid', 'true')
  // Both writes answered: the older answer must not have undone the newer one.
  await expect.poll(async () => (await calls(page, 'settings.set')).length).toBe(2)
  await page.waitForTimeout(250)
  await expect(html).toHaveAttribute('data-motion', 'off')
  await expect(html).toHaveAttribute('data-solid', 'true')
  const blur = await page.locator('.bar').evaluate((el) => getComputedStyle(el).backdropFilter)
  expect(blur).toBe('none')
})

test('restore defaults asks, then puts everything back', async ({ page }) => {
  await page.getByTestId('theme-ember').click()
  await page.getByRole('button', { name: 'Restore defaults' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Restore defaults' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'glass')
  await expect(page.getByText('Interface settings restored to their defaults')).toBeVisible()
})

test('menu settings: title screen switch and vanilla menu for this session', async ({ page }) => {
  await page.getByRole('switch', { name: 'Breeze title screen' }).click()
  await page.getByRole('button', { name: 'Show it now' }).click()
  await expect.poll(async () => (await calls(page, 'ui.vanillaMenu')).length).toBe(1)
  expect((await calls(page, 'settings.set')).at(-1)?.params).toEqual({ key: 'replaceTitleScreen', value: false })
})

test('Minecraft settings opens the game options', async ({ page }) => {
  await expect(page.getByRole('heading', { level: 2, name: 'Minecraft' })).toBeVisible()
  await page.getByTestId('minecraft-settings').click()
  await expect.poll(() => calls(page, 'game.options').then((c) => c.length)).toBe(1)
})
