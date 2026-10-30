import { expect, test, type Page } from '@playwright/test'
import { assertNoOverflow, calls, open, rail, snap } from './helpers'

async function openCrosshair(page: Page) {
  await rail(page, 'Mods').click()
  await page.getByRole('searchbox', { name: 'Search modules' }).fill('crosshair')
  await page.getByRole('button', { name: 'Custom Crosshair settings' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Custom Crosshair' })).toBeVisible()
}

const preview = (page: Page) => page.getByRole('img', { name: 'Crosshair over the sky' })

test('the preview redraws as each setting changes', async ({ page }, info) => {
  const opened = await open(page)
  await openCrosshair(page)
  // A cross: four arms and their four outlines.
  await expect(preview(page)).toHaveAttribute('data-rects', '8')
  await snap(page, info, 'crosshair-default')

  await page.getByRole('switch', { name: 'Outline', exact: true }).click()
  await expect(preview(page)).toHaveAttribute('data-rects', '4')

  await page.getByRole('switch', { name: 'Centre dot' }).click()
  await expect(preview(page)).toHaveAttribute('data-rects', '5')

  await page.getByRole('group', { name: 'Style' }).getByRole('button', { name: 'T', exact: true }).click()
  await expect(preview(page)).toHaveAttribute('data-rects', '4')

  // The arm's width follows Thickness, at two screen pixels a GUI pixel.
  const thickness = page.getByRole('slider', { name: 'Thickness', exact: true })
  await thickness.focus()
  await page.keyboard.press('ArrowRight')
  await expect(preview(page).locator('rect').first()).toHaveAttribute('height', '2')
  await expect.poll(async () => (await calls(page, 'modules.setSetting')).map((c) => c.params.id)).toEqual(
    ['outline', 'dot', 'style', 'thickness'],
  )
  await snap(page, info, 'crosshair-changed')
  await assertNoOverflow(page)
  opened.assertClean()
})

test('a code is made, and another player\'s code changes only the crosshair', async ({ page }) => {
  await open(page)
  await openCrosshair(page)
  await page.getByRole('button', { name: 'Make a code' }).click()
  await expect(page.getByRole('textbox', { name: 'Your crosshair code' })).toHaveValue('BZC-AQAEAQICAf______AAAApS4')
  expect((await calls(page, 'crosshair.export')).length).toBe(1)

  await page.getByRole('textbox', { name: 'Crosshair code to use' }).fill('  BZC-AQADAgABAf8A_wD_AAAA_vg ')
  await page.getByRole('button', { name: 'Use', exact: true }).click()
  await expect.poll(async () => (await calls(page, 'crosshair.import')).at(-1)?.params).toEqual({ code: 'BZC-AQADAgABAf8A_wD_AAAA_vg' })
  // Green, thick, centre dot, no outline: four arms and the dot.
  await expect(preview(page)).toHaveAttribute('data-rects', '5')
  await expect(page.getByRole('switch', { name: 'Centre dot' })).toHaveAttribute('aria-checked', 'true')
  await expect(page.getByRole('textbox', { name: 'Colour', exact: true })).toHaveValue('#FF00FF00')
  await expect(page.getByRole('textbox', { name: 'Crosshair code to use' })).toHaveValue('')
  // Only the crosshair's own settings moved.
  expect(await calls(page, 'modules.setSetting')).toEqual([])
})

test('a damaged or foreign code is refused with the reason, and nothing changes', async ({ page }) => {
  await open(page)
  await openCrosshair(page)
  const field = page.getByRole('textbox', { name: 'Crosshair code to use' })
  const use = page.getByRole('button', { name: 'Use', exact: true })
  await expect(use).toBeDisabled()

  await field.fill('hello')
  await use.click()
  await expect(page.getByRole('alert')).toHaveText('That is not a Breeze crosshair code. They start with BZC-')

  await field.fill('BZC-AQAFAQICAf______AAAApS4')
  await field.press('Enter')
  await expect(page.getByRole('alert')).toHaveText('That code is damaged or incomplete. Copy it again.')
  await expect(preview(page)).toHaveAttribute('data-rects', '8')

  // Typing again clears the reason.
  await field.fill('BZC-')
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('colours take red, green and blue as numbers, keeping the alpha', async ({ page }) => {
  await open(page)
  await openCrosshair(page)
  const red = page.getByRole('spinbutton', { name: 'Outline colour red' })
  await red.fill('255')
  await red.press('Enter')
  await expect.poll(async () => (await calls(page, 'modules.setSetting')).at(-1)?.params).toEqual(
    { name: 'Custom Crosshair', id: 'outline_color', value: '#FFFF0000' },
  )
  const green = page.getByRole('spinbutton', { name: 'Colour green', exact: true })
  await green.fill('300')
  await green.blur()
  // Out of range is brought into range, and unchanged sends nothing.
  await expect(green).toHaveValue('255')
  expect((await calls(page, 'modules.setSetting')).length).toBe(1)
  await green.fill('')
  await green.blur()
  await expect(green).toHaveValue('255')
})

test('Free Look: its key looks, and the page says so', async ({ page }) => {
  await open(page)
  await rail(page, 'Mods').click()
  await page.getByRole('searchbox', { name: 'Search modules' }).fill('free look')
  await page.getByRole('button', { name: 'Free Look settings' }).click()
  await expect(page.getByText('Works while Free Look is on; switch it on here. Click, then press a key.')).toBeVisible()
  await expect(page.getByRole('group', { name: 'Key' })).toBeVisible()
  await expect(page.getByRole('slider', { name: 'Sensitivity' })).toBeVisible()
})
