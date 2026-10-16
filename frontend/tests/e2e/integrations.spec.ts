import { expect, test } from '@playwright/test'
import { calls, open, rail, snap } from './helpers'

// Other mods in the game: entries appear only for what Java reports
// (integrations.list), and open through integrations.open.

test('without other mods, the rail has no extra entries', async ({ page }) => {
  const { assertClean } = await open(page)
  await expect(page.locator('[data-testid^="integration-"]')).toHaveCount(0)
  await rail(page, 'Mods').click()
  await page.getByRole('group', { name: 'Section' }).getByRole('button', { name: 'Installed mods' }).click()
  await expect(page.getByTestId('installed-fabric-api')).toContainText('Library')
  await expect(page.getByRole('button', { name: /settings$/ })).toHaveCount(0)
  assertClean()
})

test('with Mod Menu, its list is one click from the rail and a mod with settings has a Settings button', async ({ page }, info) => {
  const { assertClean } = await open(page, '?preview=mods')
  const entry = page.getByTestId('integration-modmenu')
  await expect(entry).toBeVisible()
  await entry.click()
  await expect.poll(async () => (await calls(page, 'integrations.open')).at(-1)?.params).toEqual({ id: 'modmenu', action: 'mods' })

  await rail(page, 'Mods').click()
  await page.getByRole('group', { name: 'Section' }).getByRole('button', { name: 'Installed mods' }).click()
  await expect(page.getByTestId('installed-zoomify').getByRole('button', { name: 'Zoomify settings' })).toBeVisible()
  await expect(page.getByTestId('installed-xaerominimap').getByRole('button')).toHaveCount(0)
  await expect(page.locator('.integration-problem')).toContainText("Xaero's Minimap's settings screen failed to open")
  await snap(page, info, 'installed-mods-with-modmenu')
  await page.getByTestId('installed-zoomify').getByRole('button', { name: 'Zoomify settings' }).click()
  await expect.poll(async () => (await calls(page, 'integrations.open')).at(-1)?.params).toEqual({ id: 'modmenu', action: 'config:zoomify' })
  assertClean()
})
