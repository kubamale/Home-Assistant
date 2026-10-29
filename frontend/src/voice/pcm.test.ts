import { concatInt16, int16BytesToFloat32 } from './pcm'
import { planStart } from './player'

describe('pcm', () => {
  it('reads little-endian 16-bit samples as floats', () => {
    const bytes = new Uint8Array([0x00, 0x40, 0x00, 0xc0, 0xff, 0x7f, 0x00, 0x80]).buffer
    const floats = int16BytesToFloat32(bytes)
    expect(Array.from(floats)).toEqual([0.5, -0.5, 32767 / 32768, -1])
  })

  it('ignores a torn last byte', () => {
    expect(int16BytesToFloat32(new Uint8Array([1, 0, 9]).buffer)).toHaveLength(1)
  })

  it('joins frames', () => {
    expect(Array.from(concatInt16([new Int16Array([1, 2]), new Int16Array([3]), new Int16Array([])]))).toEqual([1, 2, 3])
  })
})

describe('planStart', () => {
  it('buffers a little before the first piece plays', () => {
    expect(planStart(10, 0, true, 0.2)).toBeCloseTo(10.2)
  })

  it('queues the next piece right after the one playing', () => {
    expect(planStart(10, 10.6, false, 0.2)).toBe(10.6)
  })

  it('restarts with a small lead when the audio ran dry', () => {
    expect(planStart(10, 9.9, false, 0.2)).toBeCloseTo(10.03)
  })
})
