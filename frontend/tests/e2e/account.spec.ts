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

test('wardrobe: equip and remove 3D cosmetics, one per slot', async ({ page }, info) => {
  const { assertClean } = await open(page)
  await rail(page, 'Wardrobe').click()
  await expect(page.getByRole('heading', { name: '3D cosmetics' })).toBeVisible()
  const hat = page.getByTestId('owned-preview-hat')
  const hat2 = page.getByTestId('owned-preview-hat-2')
  await expect(hat).toContainText('Hat, wearing')
  await expect(hat.getByRole('button', { name: 'Remove' })).toBeVisible()
  await snap(page, info, 'wardrobe-3d')

  // A second hat replaces the first: the slot holds one.
  await hat2.getByRole('button', { name: 'Equip' }).click()
  await expect(hat2).toContainText('Hat, wearing')
  await expect(hat.getByRole('button', { name: 'Equip' })).toBeVisible()

  await page.getByTestId('owned-preview-pet').getByRole('button', { name: 'Remove' }).click()
  await expect(page.getByTestId('owned-preview-pet').getByRole('button', { name: 'Equip' })).toBeVisible()

  expect((await calls(page, 'cosmetics.equipModel')).map((c) => c.params.id)).toEqual(['preview-hat-2'])
  expect((await calls(page, 'cosmetics.unequipModel')).map((c) => c.params.slot)).toEqual(['pet'])
  assertClean()
})

test('wardrobe: an API without the in-game routes lists what is worn, without controls', async ({ page }) => {
  const { assertClean } = await open(page, '?preview=oldapi')
  await rail(page, 'Wardrobe').click()
  await expect(page.getByTestId('worn-preview-hat')).toContainText('Preview hat')
  await expect(page.getByTestId('worn-preview-pet')).toContainText('Pet')
  await expect(page.getByTestId('worn-preview-hat').getByRole('button')).toHaveCount(0)
  await expect(page.getByText("Change them in the Breeze launcher's Wardrobe.")).toBeVisible()
  // Capes can still be equipped, but not taken off: that API brought the cape back.
  await expect(page.getByTestId('cape-preview-b').getByRole('button', { name: 'Equip' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Remove cape' })).toHaveCount(0)
  assertClean()
})

test('wardrobe: capes not on the account point to the launcher, without an Equip button', async ({ page }, info) => {
  const { assertClean } = await open(page)
  await rail(page, 'Wardrobe').click()
  await expect(page.getByRole('heading', { name: 'More capes' })).toBeVisible()
  await expect(page.getByText("Get these in the Breeze launcher's store.")).toBeVisible()
  const d = page.getByTestId('store-preview-d')
  await expect(d).toContainText('Preview cape D')
  await expect(d).toContainText('In the launcher')
  await expect(d.getByRole('button')).toHaveCount(0)
  await expect(d.locator('.cape-front')).toHaveCSS('background-image', /^url\("data:image\/png;base64,/)
  // Owned capes are not listed again under More capes.
  await expect(page.getByTestId('store-preview-a')).toHaveCount(0)
  await d.scrollIntoViewIfNeeded()
  await snap(page, info, 'wardrobe-store')
  assertClean()
})

test('wardrobe: empty, error and signed-out states', async ({ page }, info) => {
  await open(page, '?preview=empty')
  await rail(page, 'Wardrobe').click()
  await expect(page.getByText('No capes on this account yet')).toBeVisible()
  await expect(page.getByTestId('owned-empty')).toContainText('No 3D cosmetics on this account yet.')
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
