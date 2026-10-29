/** 16-bit little-endian samples, as the server sends them, to the floats the browser plays. */
export function int16BytesToFloat32(bytes: ArrayBuffer): Float32Array {
  const view = new DataView(bytes)
  const out = new Float32Array(Math.floor(bytes.byteLength / 2))
  for (let i = 0; i < out.length; i++) out[i] = view.getInt16(i * 2, true) / 32768
  return out
}

/** The samples of several frames joined into one array. */
export function concatInt16(parts: Int16Array[]): Int16Array {
  const out = new Int16Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const part of parts) {
    out.set(part, at)
    at += part.length
  }
  return out
}
