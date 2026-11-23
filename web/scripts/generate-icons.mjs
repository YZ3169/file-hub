// favicon.svg is the source for the web, Java tray and Windows launcher icons.
// Run: npm run icons:generate (PLAYWRIGHT_CHANNEL=msedge uses installed Edge).
import { readFile, writeFile } from 'node:fs/promises'
import { chromium } from '@playwright/test'

const sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
const svg = await readFile(new URL('../public/favicon.svg', import.meta.url), 'utf8')
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || undefined })

// DIB frames keep small icons compatible with Windows/.NET icon readers;
// the 256px frame uses PNG compression for Explorer's large-icon view.
function bitmapFrame(size, rgba) {
  const maskStride = Math.ceil(size / 32) * 4
  const pixelsLength = size * size * 4
  const frame = Buffer.alloc(40 + pixelsLength + maskStride * size)
  frame.writeUInt32LE(40, 0)
  frame.writeInt32LE(size, 4)
  frame.writeInt32LE(size * 2, 8)
  frame.writeUInt16LE(1, 12)
  frame.writeUInt16LE(32, 14)
  frame.writeUInt32LE(pixelsLength, 20)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const source = ((size - y - 1) * size + x) * 4
      const target = 40 + (y * size + x) * 4
      frame[target] = rgba[source + 2]
      frame[target + 1] = rgba[source + 1]
      frame[target + 2] = rgba[source]
      frame[target + 3] = rgba[source + 3]
      if (rgba[source + 3] === 0) {
        frame[40 + pixelsLength + y * maskStride + (x >> 3)] |= 0x80 >> (x % 8)
      }
    }
  }
  return frame
}

try {
  const page = await browser.newPage()
  const rendered = await page.evaluate(
    async ({ svg, sizes }) => {
      const image = new Image()
      image.src = `data:image/svg+xml;base64,${btoa(svg)}`
      await image.decode()
      return sizes.map((size) => {
        const canvas = document.createElement('canvas')
        canvas.width = canvas.height = size
        const context = canvas.getContext('2d')
        context.drawImage(image, 0, 0, size, size)
        return {
          size,
          png: canvas.toDataURL('image/png').split(',')[1],
          rgba: size < 256 ? Array.from(context.getImageData(0, 0, size, size).data) : null,
        }
      })
    },
    { svg, sizes },
  )

  const header = Buffer.alloc(6 + sizes.length * 16)
  header.writeUInt16LE(1, 2)
  header.writeUInt16LE(sizes.length, 4)
  let offset = header.length
  const frames = rendered.map(({ size, rgba, png }, index) => {
    const frame = size === 256 ? Buffer.from(png, 'base64') : bitmapFrame(size, rgba)
    const entry = 6 + index * 16
    header[entry] = header[entry + 1] = size === 256 ? 0 : size
    header.writeUInt16LE(1, entry + 4)
    header.writeUInt16LE(32, entry + 6)
    header.writeUInt32LE(frame.length, entry + 8)
    header.writeUInt32LE(offset, entry + 12)
    offset += frame.length
    return frame
  })
  await writeFile(
    new URL('../../desktop-shell/FileHub.ico', import.meta.url),
    Buffer.concat([header, ...frames]),
  )
  await writeFile(
    new URL('../public/app-icon.png', import.meta.url),
    Buffer.from(rendered.at(-1).png, 'base64'),
  )
  console.log(`Generated FileHub.ico (${sizes.join(', ')}px) and app-icon.png from favicon.svg`)
} finally {
  await browser.close()
}
