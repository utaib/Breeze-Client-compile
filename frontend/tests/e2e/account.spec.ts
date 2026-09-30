import { expect, test } from '@playwright/test'
import { calls, open, rail, snap } from './helpers'

test('wardrobe: equip and remove a cape', async ({ page }, info) => {
  const { assertClean } = await open(page)
  await rail(page, 'Wardrobe').click()
  const b = page.getByTestId('cape-preview-b')
  await expect(page.getByTestId('cape-preview-a')).toContainText('Equipped')
  // A cape with a picture shows its front; one without shows the placeholder.
  await expect(page.getByTestId('cape-preview-a').locator('.cape-front')).toHaveCSS('background-image', /^url\("data:image\/png;base64,/)
  await expect(page.getByTestId('cape-preview-c').locator('.cape-blank')).toBeVisible()
  await snap(page, info, 'wardrobe')

  await b.getByRole('button', { name: 'Equip' }).click()
  await expect(b).toContainText('Equipped')
  await expect(page.getByTestId('cape-preview-a').getByRole('button', { name: 'Equip' })).toBeVisible()
  await snap(page, info, 'wardrobe-equipped')

  await page.getByRole('button', { name: 'Remove cape' }).click()
  await expect(page.locator('.cape.on')).toHaveCount(0)
  expect((await calls(page, 'cosmetics.equipCape')).map((c) => c.params.id)).toEqual(['preview-b', null])
  assertClean()
})

test('wardrobe: empty, error and signed-out states', async ({ page }, info) => {
  await open(page, '?preview=empty')
  await rail(page, 'Wardrobe').click()
  await expect(page.getByText('No capes on this account yet')).toBeVisible()
  await snap(page, info, 'wardrobe-empty')

  await open(page, '?preview=error')
  await rail(page, 'Wardrobe').click()
  await expect(page.getByRole('alert').getByText('Could not load your capes')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible()
  await snap(page, info, 'wardrobe-error')

  await open(page, '?preview=signedout')
  await rail(page, 'Wardrobe').click()
  await expect(page.getByText('Sign in to Breeze to see this')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Try again' })).toHaveCount(0)
  await snap(page, info, 'wardrobe-signed-out')
})

test('wardrobe: a slow answer shows loading, not a blank page', async ({ page }, info) => {
  await open(page, '?latency=1500')
  await rail(page, 'Wardrobe').click()
  await expect(page.locator('[aria-busy="true"]')).toBeVisible()
  await snap(page, info, 'wardrobe-loading')
  await expect(page.getByTestId('cape-preview-a')).toBeVisible({ timeout: 5000 })
})

test('friends: validation, requests and removal', async ({ page }, info) => {
  const { assertClean } = await open(page)
  await rail(page, 'Friends').click()
  await expect(page.getByText('PreviewFriendOne')).toBeVisible()
  await snap(page, info, 'friends')

  const add = page.getByRole('button', { name: 'Add friend' })
  const name = page.getByRole('textbox', { name: 'Minecraft name' })
  await name.fill('a!')
  await expect(add).toBeDisabled()
  await name.fill('Notch')
  await add.click()
  await expect(page.getByText('Waiting for them to accept')).toBeVisible()

  await page.getByRole('button', { name: 'Accept' }).click()
  await expect(page.getByText('You and PreviewRequest are now friends')).toBeVisible()

  await page.getByRole('button', { name: 'Remove PreviewFriendTwo' }).click()
  await page.getByRole('button', { name: 'Remove friend' }).click()
  await expect(page.locator('.row-label', { hasText: 'PreviewFriendTwo' })).toHaveCount(0)

  expect((await calls(page, 'friends.request'))[0]?.params).toEqual({ name: 'Notch' })
  expect((await calls(page, 'friends.respond'))[0]?.params).toMatchObject({ accept: true })
  assertClean()
})

test('friends: empty state', async ({ page }, info) => {
  await open(page, '?preview=empty')
  await rail(page, 'Friends').click()
  await expect(page.getByText('No friends yet')).toBeVisible()
  await snap(page, info, 'friends-empty')
})

test('hosting: only offered inside a singleplayer world', async ({ page }, info) => {
  await open(page)
  await rail(page, 'Hosting').click()
  await expect(page.getByText('Open a singleplayer world to host it')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Start hosting' })).toBeDisabled()
  await snap(page, info, 'hosting-title')

  await open(page, '?context=ingame')
  await rail(page, 'Hosting').click()
  await page.getByRole('checkbox').first().check()
  await page.getByRole('button', { name: 'Start hosting' }).click()
  await expect(page.getByText('Your world is open to invited friends')).toBeVisible()
  await snap(page, info, 'hosting-active')
  expect((await calls(page, 'hosting.start'))[0]?.params.invite).toHaveLength(1)
  await page.getByRole('button', { name: 'Stop hosting' }).click()
  await expect(page.getByText('Ready to host this world')).toBeVisible()
})
