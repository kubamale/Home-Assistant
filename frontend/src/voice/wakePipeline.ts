/**
 * The wake word's audio pipeline, as openWakeWord does it (the pre-trained "hey jarvis" model needs exactly these
 * steps): every 80 ms of audio becomes mel-spectrogram frames, the last 76 of those become one 96-number embedding,
 * and the model reads the last 16 embeddings (about 1.3 s) and gives a score from 0 to 1.
 *
 * The three models are handed in, so this can be tested without them and runs in the browser or in Node.
 */

/** 80 ms of 16 kHz audio: the pipeline's step. */
export const WAKE_STEP = 1280
/** Audio from before a step that the spectrogram also needs (30 ms). */
const CONTEXT = 480
const MEL_BINS = 32
const WINDOW = 76
const EMBEDDINGS = 16
const EMBEDDING_SIZE = 96

export interface WakeModels {
  /** Audio as numbers in the 16-bit range → mel frames, flat (frames × 32). */
  mel(samples: Float32Array): Promise<Float32Array>
  /** 76 mel frames, flat (76 × 32) → one embedding of 96 numbers. */
  embed(window: Float32Array): Promise<Float32Array>
  /** The last 16 embeddings, flat (16 × 96) → how likely the wake word was just said. */
  classify(embeddings: Float32Array): Promise<number>
}

export class WakePipeline {
  private readonly step = new Int16Array(WAKE_STEP)
  private filled = 0
  private context = new Int16Array(0)
  private mel: Float32Array[] = []
  private embeddings: Float32Array[] = []

  constructor(private readonly models: WakeModels) {
    this.reset()
  }

  /** Forgets all audio, as at the start. */
  reset(): void {
    this.filled = 0
    this.context = new Int16Array(0)
    this.mel = Array.from({ length: WINDOW }, () => new Float32Array(MEL_BINS).fill(1))
    this.embeddings = []
  }

  /** Feeds audio of any length; returns one score for every 80 ms completed by it (none until about 1.3 s has been heard). */
  async push(samples: Int16Array): Promise<number[]> {
    const scores: number[] = []
    let at = 0
    while (at < samples.length) {
      const take = Math.min(WAKE_STEP - this.filled, samples.length - at)
      this.step.set(samples.subarray(at, at + take), this.filled)
      this.filled += take
      at += take
      if (this.filled === WAKE_STEP) {
        this.filled = 0
        const score = await this.process()
        if (score !== null) scores.push(score)
      }
    }
    return scores
  }

  private async process(): Promise<number | null> {
    const input = new Float32Array(this.context.length + WAKE_STEP)
    input.set(this.context)
    input.set(this.step, this.context.length)
    const tail = new Int16Array(Math.min(CONTEXT, input.length))
    tail.set(input.subarray(input.length - tail.length)) // Float32 holds every 16-bit value exactly
    this.context = tail

    const mel = await this.models.mel(input)
    for (let at = 0; at + MEL_BINS <= mel.length; at += MEL_BINS) {
      const frame = new Float32Array(MEL_BINS)
      for (let i = 0; i < MEL_BINS; i++) frame[i] = mel[at + i] / 10 + 2 // makes the ONNX spectrogram match the one the models were trained on
      this.mel.push(frame)
    }
    if (this.mel.length > WINDOW * 2) this.mel.splice(0, this.mel.length - WINDOW)

    const window = new Float32Array(WINDOW * MEL_BINS)
    this.mel.slice(-WINDOW).forEach((frame, i) => window.set(frame, i * MEL_BINS))
    this.embeddings.push(await this.models.embed(window))
    if (this.embeddings.length > EMBEDDINGS) this.embeddings.shift()
    if (this.embeddings.length < EMBEDDINGS) return null

    const features = new Float32Array(EMBEDDINGS * EMBEDDING_SIZE)
    this.embeddings.forEach((e, i) => features.set(e, i * EMBEDDING_SIZE))
    return this.models.classify(features)
  }
}

/** The parts of onnxruntime-web used here, so this file needs neither it nor a browser. */
interface OrtLike {
  Tensor: new (type: 'float32', data: Float32Array, dims: number[]) => unknown
  InferenceSession: {
    create(model: ArrayBuffer | Uint8Array): Promise<{
      run(feeds: Record<string, unknown>): Promise<Record<string, { data: Float32Array | ArrayLike<number> }>>
    }>
  }
}

/** The three models of the "hey jarvis" detector, run with onnxruntime. */
export async function loadWakeModels(
  ort: OrtLike,
  files: { mel: ArrayBuffer | Uint8Array; embed: ArrayBuffer | Uint8Array; wake: ArrayBuffer | Uint8Array },
): Promise<WakeModels> {
  const [mel, embed, wake] = await Promise.all([files.mel, files.embed, files.wake].map((f) => ort.InferenceSession.create(f)))
  const floats = (d: Float32Array | ArrayLike<number>) => (d instanceof Float32Array ? d : Float32Array.from(d))
  return {
    async mel(samples) {
      const out = await mel.run({ input: new ort.Tensor('float32', samples, [1, samples.length]) })
      return floats(out.output.data)
    },
    async embed(window) {
      const out = await embed.run({ input_1: new ort.Tensor('float32', window, [1, WINDOW, MEL_BINS, 1]) })
      return floats(out.conv2d_19.data)
    },
    async classify(embeddings) {
      const out = await wake.run({ 'x.1': new ort.Tensor('float32', embeddings, [1, EMBEDDINGS, EMBEDDING_SIZE]) })
      return floats(out['53'].data)[0]
    },
  }
}
