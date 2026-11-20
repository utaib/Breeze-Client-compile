import { expect, test, type Page } from '@playwright/test'
import { assertNoOverflow, calls, open, rail, snap } from './helpers'

async function openKeys(page: Page, query = '') {
  const opened = await open(page, query)
  await rail(page, 'Mods').click()
  await page.getByRole('group', { name: 'Section' }).getByRole('button', { name: 'Keys', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Command keys' })).toBeVisible()
  return opened
}

const list = (page: Page) => page.getByRole('list', { name: 'Command keys' })

test('add a command key, then press Ctrl and a key for it', async ({ page }, info) => {
  const opened = await openKeys(page)
  await page.getByRole('textbox', { name: 'New command' }).fill('/home base')
  await page.getByRole('button', { name: 'Add command key' }).click()
  // Straight on to its key.
  const cap = list(page).getByRole('button', { name: /Press a key for \/home base/ })
  await expect(cap).toBeVisible()
  await page.keyboard.down('Control')
  await page.keyboard.press('J')
  await page.keyboard.up('Control')
  await expect(list(page).getByRole('button', { name: '/home base: Ctrl + J. Change' })).toBeVisible()
  expect((await calls(page, 'commandKeys.save'))[0]!.params).toEqual({ command: '/home base' })
  await snap(page, info, 'command-keys')
  await assertNoOverflow(page)
  opened.assertClean()
})

test('a key Minecraft needs for playing is refused alone, taken with a modifier', async ({ page }) => {
  const opened = await openKeys(page)
  await list(page).getByRole('button', { name: '/spawn: Ctrl + K. Change' }).click()
  await expect(list(page).getByRole('button', { name: /Press a key for \/spawn/ })).toBeVisible()
  await page.keyboard.press('W')
  await expect(list(page).getByText('W is Walk Forwards in Minecraft')).toBeVisible()
  await expect(list(page).getByRole('button', { name: '/spawn: Ctrl + K. Change' })).toBeVisible()

  await list(page).getByRole('button', { name: '/spawn: Ctrl + K. Change' }).click()
  await expect(list(page).getByRole('button', { name: /Press a key for \/spawn/ })).toBeVisible()
  await page.keyboard.down('Alt')
  await page.keyboard.press('W')
  await page.keyboard.up('Alt')
  await expect(list(page).getByRole('button', { name: '/spawn: Alt + W. Change' })).toBeVisible()
  // Minecraft still walks on W with Alt held: said, not hidden.
  await expect(list(page).getByText('Alt + W is also Walk Forwards. Pressing it does both.')).toBeVisible()
  opened.assertClean()
})

test('a command with a formatting code is refused with the reason', async ({ page }) => {
  const opened = await openKeys(page)
  const field = list(page).getByRole('textbox', { name: 'Command for Ctrl + K' })
  await field.fill('/say §cred')
  await field.press('Enter')
  await expect(list(page).getByRole('alert')).toHaveText('Formatting codes (§) are not allowed in a command.')
  await expect(field).toHaveValue('/say §cred')
  await field.fill('/warp hub')
  await field.press('Enter')
  await expect(list(page).getByRole('alert')).toHaveCount(0)
  await expect(list(page).getByRole('button', { name: '/warp hub: Ctrl + K. Change' })).toBeVisible()
  opened.assertClean()
})

test('in a world, a command the server does not offer is pointed out', async ({ page }) => {
  const opened = await openKeys(page, '?context=ingame')
  const field = list(page).getByRole('textbox', { name: 'Command for Ctrl + K' })
  await field.fill('/warp hub')
  await field.press('Enter')
  await expect(list(page).getByText('This server does not offer /warp to you here.')).toBeVisible()
  opened.assertClean()
})

test('switch off, take the key away, and delete', async ({ page }) => {
  const opened = await openKeys(page)
  await list(page).getByRole('switch', { name: '/spawn on' }).click()
  await expect(list(page).getByRole('switch', { name: '/spawn on' })).toHaveAttribute('aria-checked', 'false')
  await list(page).getByRole('button', { name: 'Remove the key of /spawn' }).click()
  await expect(list(page).getByRole('button', { name: '/spawn: no key. Change' })).toBeVisible()
  await list(page).getByRole('button', { name: 'Delete the command key /spawn' }).click()
  await expect(list(page)).toHaveCount(0)
  expect((await calls(page)).map((c) => c.action).filter((a) => a.startsWith('commandKeys.') && a !== 'commandKeys.list'))
    .toEqual(['commandKeys.save', 'commandKeys.unbind', 'commandKeys.remove'])
  opened.assertClean()
})
