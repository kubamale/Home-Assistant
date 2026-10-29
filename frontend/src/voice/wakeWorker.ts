/// <reference lib="webworker" />
// Runs the wake word models off the page's thread: three small neural networks every 80 ms would otherwise make the
// screen stutter on a Raspberry Pi. The page sends microphone frames; this answers with scores.
import * as ort from 'onnxruntime-web/wasm'
import wasmUrl from 'onnxruntime-web/ort-wasm-simd-threaded.wasm?url'
import { loadWakeModels, WakePipeline } from './wakePipeline'

/** Frames waiting for the models beyond this many (20 ms each) mean the device cannot keep up: skip them instead of falling further behind. */
const MAX_BACKLOG = 100

let pipeline: WakePipeline | null = null
let queued = 0
let chain: Promise<void> = Promise.resolve()

const post = (message: Record<string, unknown>) => self.postMessage(message)

async function load(models: { mel: string; embed: string; wake: string }) {
  ort.env.wasm.wasmPaths = { wasm: wasmUrl }
  ort.env.wasm.numThreads = 1 // more would need a cross-origin-isolated page
  const [mel, embed, wake] = await Promise.all(
    [models.mel, models.embed, models.wake].map(async (url) => {
      const response = await fetch(url)
      if (!response.ok) throw new Error(`${url} could not be loaded (${response.status})`)
      return response.arrayBuffer()
    }),
  )
  pipeline = new WakePipeline(await loadWakeModels(ort as unknown as Parameters<typeof loadWakeModels>[0], { mel, embed, wake }))
}

self.onmessage = (e: MessageEvent<{ type: string; pcm?: Int16Array; models?: { mel: string; embed: string; wake: string } }>) => {
  const message = e.data
  if (message.type === 'load') {
    load(message.models!).then(
      () => post({ type: 'ready' }),
      (error: unknown) => post({ type: 'error', message: error instanceof Error ? error.message : String(error) }),
    )
  } else if (message.type === 'reset') {
    chain = chain.then(() => pipeline?.reset())
  } else if (message.type === 'frame' && pipeline) {
    if (queued >= MAX_BACKLOG) return
    queued++
    const pcm = message.pcm!
    chain = chain
      .then(async () => {
        const scores = await pipeline!.push(pcm)
        if (scores.length > 0) post({ type: 'scores', scores })
      })
      .catch((error: unknown) => post({ type: 'error', message: error instanceof Error ? error.message : String(error) }))
      .finally(() => {
        queued--
      })
  }
}
