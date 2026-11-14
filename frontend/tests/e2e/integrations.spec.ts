import { expect, test, type Page } from '@playwright/test'
import { assertNoOverflow, calls, open, rail, snap } from './helpers'

// Other mods in the game: what Java reports (integrations.list), one entry
// per mod under Mods / Other mods, and one rail entry for all of them,
// however many mods and buttons there are. Each opens through
// integrations.open.

const section = (page: Page, name: string) => page.getByRole('group', { name: 'Section' }).getByRole('button', { name, exact: true })
const group = (page: Page, key: string) => page.getByTestId(`integration-group-${key}`)
const lastOpen = async (page: Page) => (await calls(page, 'integrations.open')).at(-1)?.params

test('without other mods, the rail has no Other mods entry and the page says why', async ({ page }) => {
  const { assertClean } = await open(page)
  await expect(rail(page, 'Other mods')).toHaveCount(0)
  await rail(page, 'Mods').click()
  await section(page, 'Other mods').click()
  await expect(page.getByText('No other mod offers anything here')).toBeVisible()
  await expect(rail(page, 'Mods')).toHaveAttribute('aria-current', 'page')
  await section(page, 'Installed mods').click()
  await expect(page.getByTestId('installed-fabric-api')).toContainText('Library')
  await expect(page.getByRole('button', { name: /settings$/ })).toHaveCount(0)
  assertClean()
})

test('with Mod Menu, its list and a mod\'s settings are one entry each under Other mods', async ({ page }, info) => {
  const { assertClean } = await open(page, '?preview=mods')
  await expect(page.locator('nav.rail [data-testid^="integration-"]')).toHaveCount(0)
  await rail(page, 'Other mods').click()
  await expect(rail(page, 'Other mods')).toHaveAttribute('aria-current', 'page')
  await expect(rail(page, 'Mods')).not.toHaveAttribute('aria-current', 'page')
  await expect(page.locator('.crumb-pg')).toHaveText('Mods / Other mods')
  await expect(page.locator('.xmod').first()).toHaveAttribute('data-testid', 'integration-group-modmenu')
  await group(page, 'modmenu').getByRole('button', { name: 'Mod Menu: mod list' }).click()
  await expect.poll(() => lastOpen(page)).toEqual({ id: 'modmenu', action: 'mods' })
  await group(page, 'zoomify').getByRole('button', { name: 'Zoomify settings' }).click()
  await expect.poll(() => lastOpen(page)).toEqual({ id: 'modmenu', action: 'config:zoomify' })
  await expect(page.locator('.integration-problem')).toContainText("Xaero's Minimap's settings screen failed to open")
  await snap(page, info, 'other-mods-with-modmenu')

  // The Installed list keeps its Settings button beside the mod.
  await section(page, 'Installed mods').click()
  await expect(rail(page, 'Mods')).toHaveAttribute('aria-current', 'page')
  await expect(page.getByTestId('installed-zoomify').getByRole('button', { name: 'Zoomify settings' })).toBeVisible()
  await expect(page.getByTestId('installed-xaerominimap').getByRole('button')).toHaveCount(0)
  await snap(page, info, 'installed-mods-with-modmenu')
  await page.getByTestId('installed-zoomify').getByRole('button', { name: 'Zoomify settings' }).click()
  await expect.poll(() => lastOpen(page)).toEqual({ id: 'modmenu', action: 'config:zoomify' })
  assertClean()
})

test('a game full of mods adds one rail entry, and a mod with nine buttons is one card', async ({ page }, info) => {
  const { assertClean } = await open(page)
  const plain = await page.locator('nav.rail .ni').count()
  await page.goto('/?preview=manymods')
  await expect(page.locator('.rail')).toBeVisible()
  await expect(rail(page, 'Other mods')).toBeVisible()
  await expect(page.locator('nav.rail .ni')).toHaveCount(plain + 1)
  await assertNoOverflow(page)

  await rail(page, 'Other mods').click()
  await expect(page.locator('.toolbar-count')).toHaveText('17 mods')
  // Mod Menu first, mods by name, buttons no mod could be told for last.
  const keys = await page.locator('.xmod').evaluateAll((els) => els.map((e) => e.getAttribute('data-testid')!.replace('integration-group-', '')))
  expect(keys[0]).toBe('modmenu')
  expect(keys.at(-1)).toBe('title-screen')
  expect(keys).toContain('flashback')

  const essential = group(page, 'essential')
  await expect(essential.locator('.xmod-meta')).toHaveText('9 title screen buttons')
  await expect(essential.locator('.xmod-action')).toHaveCount(6)
  await essential.getByRole('button', { name: 'Show all 9 from Essential' }).click()
  await expect(essential.locator('.xmod-action')).toHaveCount(9)
  await essential.getByRole('button', { name: 'Essential: Updates' }).click()
  await expect.poll(() => lastOpen(page)).toEqual({ id: 'title:Updates', action: 'press' })

  // A mod's settings, own entry and title button together.
  await expect(group(page, 'replaymod').locator('.xmod-action')).toHaveText(['Replay Mod', 'Replay Viewer'])
  await expect(group(page, 'replaymod').locator('.xmod-meta')).toHaveText('Its own Breeze entry · A title screen button')
  await expect(group(page, 'flashback').getByRole('button', { name: 'Flashback' })).toBeVisible()
  await expect(group(page, 'title-screen')).toContainText('Title screen buttons')
  await expect(group(page, 'title-screen').getByRole('button', { name: 'Changelog' })).toBeVisible()
  await snap(page, info, 'other-mods-many')
  assertClean()
})

test('searching other mods finds a mod by name, or an entry by its label across mods', async ({ page }, info) => {
  const { assertClean } = await open(page, '?preview=manymods')
  await rail(page, 'Other mods').click()
  const search = page.getByRole('searchbox')
  await expect(search).toBeFocused()

  await search.fill('sodium')
  await expect(page.locator('.xmod')).toHaveCount(1)
  await expect(group(page, 'sodium').getByRole('button', { name: 'Sodium settings' })).toBeVisible()

  // Matching labels show even past the first six, without Show all.
  await search.fill('updates')
  await expect(page.locator('.xmod')).toHaveCount(1)
  await expect(group(page, 'essential').locator('.xmod-action')).toHaveText(['Updates'])
  await expect(page.getByRole('button', { name: /^Show all/ })).toHaveCount(0)

  await search.fill('settings')
  await expect(group(page, 'essential').locator('.xmod-action')).toHaveText(['Essential settings'])
  // What the row says is about the whole mod, not only what the search shows.
  await expect(group(page, 'essential').locator('.xmod-meta')).toHaveText('9 title screen buttons')
  await expect(page.locator('.toolbar-count')).toHaveText('14 of 17 mods')
  // Zoomify, the twelve other mods with Mod Menu settings, and Essential.
  expect(await page.locator('.xmod').count()).toBe(14)
  await snap(page, info, 'other-mods-search')

  await search.fill('no mod is called this')
  await expect(page.getByText('Nothing matches "no mod is called this"')).toBeVisible()
  await search.fill('')
  // 17 mods and the buttons no mod could be told for.
  await expect(page.locator('.xmod')).toHaveCount(18)
  assertClean()
})

test('an entry Java refuses says why, and the menu stays where it was', async ({ page }) => {
  const { assertClean } = await open(page, '?preview=manymods')
  await rail(page, 'Other mods').click()
  // The preview's Changelog button is gone from the title screen, as when a
  // mod removes its button after the list was read.
  await group(page, 'title-screen').getByRole('button', { name: 'Changelog' }).click()
  await expect(page.getByRole('alert')).toContainText('Changelog is not on the title screen in this game.')
  await expect(page.locator('.crumb-pg')).toHaveText('Mods / Other mods')
  await expect(group(page, 'essential')).toBeVisible()
  assertClean()
})

test('Other mods fits a small window without sideways scrolling', async ({ page }, info) => {
  await page.setViewportSize({ width: 854, height: 480 })
  const { assertClean } = await open(page, '?preview=manymods')
  await rail(page, 'Other mods').click()
  await expect(group(page, 'essential')).toBeVisible()
  await assertNoOverflow(page)
  await snap(page, info, 'other-mods-854x480')
  assertClean()
})
