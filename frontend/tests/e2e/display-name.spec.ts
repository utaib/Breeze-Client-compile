import { expect, test, type Page } from '@playwright/test'
import { assertNoOverflow, calls, open, rail, snap } from './helpers'

async function openDisplayName(page: Page) {
  await rail(page, 'Mods').click()
  await page.getByRole('searchbox', { name: 'Search modules' }).fill('display name')
  await page.getByRole('button', { name: 'Display Name settings' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Display Name' })).toBeVisible()
}

const field = (page: Page) => page.getByRole('textbox', { name: 'Name you see' })

test('says the name is shown on this computer only', async ({ page }, info) => {
  const opened = await open(page)
  await openDisplayName(page)
  await expect(page.getByRole('note')).toContainText('Only on this computer')
  await expect(page.getByRole('note')).toContainText('still see your real name')
  await expect(field(page)).toHaveValue('You')
  await snap(page, info, 'display-name')
  await assertNoOverflow(page)
  opened.assertClean()
})

test('a name is saved on Enter, tidied as the game keeps it', async ({ page }) => {
  const opened = await open(page)
  await openDisplayName(page)
  await field(page).fill('  Uthab   YT ')
  await field(page).press('Enter')
  await expect(field(page)).toHaveValue('Uthab YT')
  const sent = await calls(page, 'modules.setSetting')
  expect(sent.map((c) => [c.params.id, c.params.value])).toEqual([['name', '  Uthab   YT ']])
  await expect(page.getByRole('alert')).toHaveCount(0)
  opened.assertClean()
})

test('a refused name says why, keeps the typing, and changes nothing', async ({ page }, info) => {
  const opened = await open(page)
  await openDisplayName(page)
  await field(page).fill('§cRed')
  await field(page).press('Enter')
  await expect(page.getByRole('alert')).toHaveText('Formatting codes (§) are not allowed.')
  await expect(field(page)).toHaveValue('§cRed')
  await expect(field(page)).toHaveAttribute('aria-invalid', 'true')
  await snap(page, info, 'display-name-refused')

  // Typing again clears the message; leaving the field saves the fixed name.
  await field(page).fill('Breeze')
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.getByRole('heading', { level: 1, name: 'Display Name' }).click()
  await expect.poll(async () => (await calls(page, 'modules.setSetting')).map((c) => c.params.value)).toEqual(['§cRed', 'Breeze'])
  await expect(field(page)).toHaveValue('Breeze')
  opened.assertClean()
})

test('Escape undoes unsaved typing before it leaves the page', async ({ page }) => {
  const opened = await open(page)
  await openDisplayName(page)
  await field(page).fill('Someone')
  await field(page).press('Escape')
  await expect(field(page)).toHaveValue('You')
  await expect(page.getByRole('heading', { level: 1, name: 'Display Name' })).toBeVisible()
  expect(await calls(page, 'modules.setSetting')).toEqual([])
  opened.assertClean()
})
