import { WAKE_STEP, WakePipeline, type WakeModels } from './wakePipeline'

/** Models that only record what they are given. */
function fakeModels() {
  const calls = { mel: [] as number[], embedWindows: [] as Float32Array[], classified: [] as Float32Array[] }
  const models: WakeModels = {
    async mel(samples) {
      calls.mel.push(samples.length)
      const frames = Math.ceil(samples.length / 160 - 3)
      return new Float32Array(frames * 32).fill(20) // becomes 20 / 10 + 2 = 4
    },
    async embed(window) {
      calls.embedWindows.push(window)
      return new Float32Array(96).fill(calls.embedWindows.length)
    },
    async classify(embeddings) {
      calls.classified.push(embeddings)
      return 0.25
    },
  }
  return { models, calls }
}

const audio = (samples: number) => new Int16Array(samples).fill(7)

describe('WakePipeline', () => {
  it('gives no score until it has heard about 1.3 s, then one for every 80 ms', async () => {
    const { models } = fakeModels()
    const pipeline = new WakePipeline(models)
    expect(await pipeline.push(audio(WAKE_STEP * 15))).toEqual([])
    expect(await pipeline.push(audio(WAKE_STEP))).toEqual([0.25])
    expect(await pipeline.push(audio(WAKE_STEP * 3))).toEqual([0.25, 0.25, 0.25])
  })

  it('gives the same scores however the audio is cut up', async () => {
    const whole = new WakePipeline(fakeModels().models)
    const pieces = new WakePipeline(fakeModels().models)
    const a = await whole.push(audio(WAKE_STEP * 20))
    const b: number[] = []
    for (let i = 0; i < WAKE_STEP * 20; i += 320) b.push(...(await pieces.push(audio(320))))
    expect(b).toEqual(a)
    expect(a).toHaveLength(5)
  })

  it('hands the spectrogram model each 80 ms with 30 ms from before it', async () => {
    const { models, calls } = fakeModels()
    await new WakePipeline(models).push(audio(WAKE_STEP * 3))
    expect(calls.mel).toEqual([1280, 1760, 1760])
  })

  it('starts the first window from ones and rescales the spectrogram as the models expect', async () => {
    const { models, calls } = fakeModels()
    await new WakePipeline(models).push(audio(WAKE_STEP))
    const window = calls.embedWindows[0]
    expect(window).toHaveLength(76 * 32)
    expect(window[0]).toBe(1) // nothing heard yet before the first steps
    expect(window[window.length - 1]).toBe(4) // 20 / 10 + 2
  })

  it('reads the last 16 embeddings, oldest first', async () => {
    const { models, calls } = fakeModels()
    await new WakePipeline(models).push(audio(WAKE_STEP * 17))
    const features = calls.classified[1]
    expect(features).toHaveLength(16 * 96)
    expect(features[0]).toBe(2) // the embedding made by the 2nd step
    expect(features[15 * 96]).toBe(17)
  })

  it('starts over after a reset', async () => {
    const { models } = fakeModels()
    const pipeline = new WakePipeline(models)
    await pipeline.push(audio(WAKE_STEP * 20))
    pipeline.reset()
    expect(await pipeline.push(audio(WAKE_STEP * 15))).toEqual([])
  })
})
