import { expect, test, type Page } from '@playwright/test'
import { assertNoOverflow, calls, open, rail, snap } from './helpers'

async function openShield(page: Page) {
  await rail(page, 'Mods').click()
  await page.getByRole('searchbox', { name: 'Search modules' }).fill('shield')
  await page.getByRole('button', { name: 'Custom Shield settings' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Custom Shield' })).toBeVisible()
}

const pictures = (page: Page) => page.getByRole('radiogroup', { name: 'Shield picture' })

test('a picture from the folder goes on the shield, and Fit changes the preview', async ({ page }, info) => {
  const opened = await open(page)
  await openShield(page)
  await expect(page.getByRole('note')).toContainText('inventory slots keep the plain shield')
  // Chosen from the folder in the panel; the file name is not typed in a row.
  await expect(page.getByRole('textbox', { name: 'Picture' })).toHaveCount(0)
  await expect(pictures(page).getByRole('radio', { name: 'No picture' })).toHaveAttribute('aria-checked', 'true')
  await expect(page.getByText('Minecraft\'s own shield')).toBeVisible()

  await pictures(page).getByRole('radio', { name: 'dragon crest.png' }).click()
  await expect(pictures(page).getByRole('radio', { name: 'dragon crest.png' })).toHaveAttribute('aria-checked', 'true')
  const face = page.getByRole('img', { name: 'Your shield\'s front as the game will draw it' })
  await expect(face).toBeVisible()
  expect((await calls(page, 'shield.choose'))[0]!.params).toEqual({ picture: 'dragon crest.png' })
  // The module is off: it says so rather than pretending the shield changed.
  await expect(page.getByText('Turn Custom Shield on to see the picture on your shield.')).toBeVisible()
  await expect(page.getByText(/^Only you see it\./)).toBeVisible()

  const before = await face.getAttribute('src')
  await page.getByRole('group', { name: 'Fit' }).getByRole('button', { name: 'Whole picture' }).click()
  await expect.poll(() => face.getAttribute('src')).not.toBe(before)

  await snap(page, info, 'shield-chosen')
  await assertNoOverflow(page)
  opened.assertClean()
})

test('sharing says what happens; a picture the game refuses says why; the folder opens', async ({ page }) => {
  const opened = await open(page)
  await openShield(page)
  await page.getByRole('switch', { name: 'Custom Shield on' }).click()
  await pictures(page).getByRole('radio', { name: 'team-logo.jpg' }).click()
  await page.getByRole('switch', { name: 'Show it to other Breeze players' }).click()
  await expect(page.getByText('Shared with other Breeze players.')).toBeVisible()

  await pictures(page).getByRole('radio', { name: 'too-big.png' }).click()
  await expect(page.getByRole('alert')).toHaveText('Use a picture at most 2048 pixels a side.')
  await expect(page.getByRole('img', { name: 'Your shield\'s front as the game will draw it' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Open the folder' }).click()
  await expect.poll(async () => (await calls(page, 'shield.openFolder')).length).toBe(1)
  opened.assertClean()
})

test('an empty folder says where pictures go', async ({ page }) => {
  const opened = await open(page, '?preview=empty')
  await openShield(page)
  await expect(page.getByText('No pictures yet. Put a PNG or JPEG in the breeze_shields folder, then look again.')).toBeVisible()
  await expect(pictures(page).getByRole('radio')).toHaveCount(1)
  opened.assertClean()
})
