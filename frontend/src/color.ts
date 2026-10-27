/** A CSS colour for a hue (0 to 359) and saturation (0 to 100) at full value, the way the bulbs take them. */
export function hsvCss(hue: number, saturation: number): string {
  const s = saturation / 100
  const lightness = 1 - s / 2
  const sl = lightness === 0 || lightness === 1 ? 0 : (1 - lightness) / Math.min(lightness, 1 - lightness)
  return `hsl(${Math.round(hue)} ${Math.round(sl * 100)}% ${Math.round(lightness * 100)}%)`
}

/** Colours people can tap instead of dragging: a few hues at full saturation, and white. */
export const COLOR_PRESETS: { name: string; hue: number; saturation: number }[] = [
  { name: 'White', hue: 0, saturation: 0 },
  { name: 'Red', hue: 0, saturation: 100 },
  { name: 'Orange', hue: 25, saturation: 100 },
  { name: 'Yellow', hue: 50, saturation: 100 },
  { name: 'Green', hue: 120, saturation: 100 },
  { name: 'Cyan', hue: 180, saturation: 100 },
  { name: 'Blue', hue: 220, saturation: 100 },
  { name: 'Purple', hue: 275, saturation: 100 },
  { name: 'Pink', hue: 320, saturation: 100 },
]
