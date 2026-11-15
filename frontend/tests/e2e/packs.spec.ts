import { expect, test, type Page } from '@playwright/test'
import { assertNoOverflow, calls, open, rail, snap } from './helpers'

async function openPacks(page: Page, query = '') {
  const opened = await open(page, query)
  await rail(page, 'Mods').click()
  await page.getByRole('group', { name: 'Section' }).getByRole('button', { name: 'Resource packs', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Available' })).toBeVisible()
  return opened
}

const onList = (page: Page) => page.getByRole('list', { name: 'Resource packs on' })
const offList = (page: Page) => page.getByRole('list', { name: 'Resource packs available' })

test('turn a pack on, move it, and apply through the game', async ({ page }, info) => {
  const opened = await openPacks(page)
  await expect(onList(page).getByRole('listitem')).toHaveCount(1)
  // The game's own and the mods' packs fold into one line, and open to a list.
  const always = page.getByText(/^Always on: Breeze and 2 more from the game and your mods/)
  await expect(always).toBeVisible()
  await always.click()
  await expect(page.getByRole('list', { name: 'Resource packs always on' }).getByRole('listitem')).toHaveCount(3)
  const apply = page.getByRole('button', { name: 'Apply', exact: true })
  await expect(apply).toBeDisabled()

  await offList(page).getByRole('switch', { name: 'Low Fire on' }).click()
  // A pack turned on goes to the top.
  await expect(onList(page).getByRole('listitem').first()).toContainText('Low Fire')
  await expect(page.getByText('Not applied yet.')).toBeVisible()
  await onList(page).getByRole('button', { name: 'Move Low Fire down' }).click()
  await expect(onList(page).getByRole('listitem').first()).toContainText('Faithful 32x')
  // The game's own pack stays at the bottom and cannot move or be turned off.
  await expect(onList(page).getByRole('button', { name: 'Move Default up' })).toHaveCount(0)
  await expect(onList(page).getByRole('switch', { name: 'Default on' })).toHaveCount(0)

  await snap(page, info, 'packs-changed')
  await apply.click()
  await expect(page.getByText('Resource packs applied.')).toBeVisible()
  expect((await calls(page, 'packs.apply'))[0]!.params).toEqual({
    selected: ['file/Faithful 32x.zip', 'file/Low Fire.zip', 'breeze', 'fabric-api', 'vanilla'],
  })
  await expect(apply).toBeDisabled()
  await assertNoOverflow(page)
  opened.assertClean()
})

test('undo puts the list back; an old pack says so; the folder opens', async ({ page }) => {
  const opened = await openPacks(page)
  await expect(offList(page).getByText('Made for an older Minecraft')).toBeVisible()
  await onList(page).getByRole('switch', { name: 'Faithful 32x on' }).click()
  await expect(onList(page).getByRole('listitem')).toHaveCount(0)
  await expect(page.getByText('Only Minecraft\'s own look. Turn a pack on below.')).toBeVisible()
  await page.getByRole('button', { name: 'Undo changes' }).click()
  await expect(onList(page).getByRole('listitem')).toHaveCount(1)
  await page.getByRole('button', { name: 'Open the folder' }).click()
  await expect.poll(async () => (await calls(page, 'packs.openFolder')).length).toBe(1)
  expect(await calls(page, 'packs.apply')).toHaveLength(0)
  opened.assertClean()
})
