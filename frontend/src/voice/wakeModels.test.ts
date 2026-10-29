// @vitest-environment node
import { readFileSync } from 'node:fs'
import * as ort from 'onnxruntime-web/wasm'
import { loadWakeModels, WakePipeline } from './wakePipeline'

/** The real models on two short recordings made with a speech synthesiser: the wake word, and a sentence that is not it. */
const file = (path: string) => new Uint8Array(readFileSync(new URL(path, import.meta.url)))

function samplesOf(path: string): Int16Array {
  const wav = file(path)
  const header = 44 // fixtures are plain 16 kHz, 16 bit, mono files
  return new Int16Array(wav.buffer.slice(wav.byteOffset + header, wav.byteOffset + wav.length))
}

/** All the scores for a recording with a second of silence either side, fed 20 ms at a time as the microphone does. */
async function scores(pipeline: WakePipeline, clip: Int16Array): Promise<number[]> {
  const padded = new Int16Array(16000 + clip.length + 16000)
  padded.set(clip, 16000)
  const all: number[] = []
  for (let at = 0; at + 320 <= padded.length; at += 320) all.push(...(await pipeline.push(padded.subarray(at, at + 320))))
  return all
}

/**
 * What openWakeWord itself (its Python package, version 0.6.0, which this pipeline copies) scores the "hey jarvis" fixture,
 * from the 16th 80 ms step on; this pipeline gives its first score there.
 */
const REFERENCE = [0, 0, 0, 0, 0.001, 0.034, 0.269, 0.975, 0.996, 0.998, 0.999, 0.999, 0.996, 0.997, 0.233, 0, 0, 0, 0, 0]

describe('the wake word models', () => {
  it('hear "hey jarvis" and nothing else', async () => {
    ort.env.wasm.numThreads = 1
    const models = await loadWakeModels(ort as unknown as Parameters<typeof loadWakeModels>[0], {
      mel: file('../../public/models/melspectrogram.onnx'),
      embed: file('../../public/models/embedding_model.onnx'),
      wake: file('../../public/models/hey_jarvis_v0.1.onnx'),
    })

    const heard = await scores(new WakePipeline(models), samplesOf('./fixtures/hey_jarvis.wav'))
    expect(heard).toHaveLength(REFERENCE.length)
    heard.forEach((score, step) => expect(Math.abs(score - REFERENCE[step]), `step ${step}`).toBeLessThan(0.03))
    expect(heard.filter((s) => s >= 0.5).length).toBeGreaterThanOrEqual(2) // long enough for the detector's patience

    const other = await scores(new WakePipeline(models), samplesOf('./fixtures/turn_off_the_light.wav'))
    expect(Math.max(...other)).toBeLessThan(0.1)
  }, 60_000)
})
