/** Hears a wake word in the microphone's frames. Everything stays on this device until it fires. */
export interface WakeDetector {
  /** Loads the models. Resolves when frames can be pushed; rejects (with words for the person) if they cannot be loaded. */
  load(): Promise<void>
  /** The next microphone frame (16 kHz, 16 bit). Never blocks. */
  push(pcm: Int16Array): void
  /** Called once each time the wake word is heard. */
  onWake: (() => void) | null
  dispose(): void
}

/** The page's end of the worker: just enough to be replaced in tests. */
export interface WakeWorkerLike {
  postMessage(message: unknown, transfer?: Transferable[]): void
  onmessage: ((e: MessageEvent) => void) | null
  terminate(): void
}

export interface WakeOptions {
  /** The score (0 to 1) from which the wake word counts as heard. */
  threshold: number
  /** How many 80 ms steps in a row must reach the threshold. */
  patience: number
  /** The least time between two wake-ups. */
  cooldownMs: number
  now?: () => number
}

/** The score the wake word has to reach for each setting: a lower one wakes more easily (and more often by mistake). */
export function wakeThreshold(sensitivity: string | undefined): number {
  return sensitivity === 'high' ? 0.3 : sensitivity === 'low' ? 0.7 : 0.5
}

export const DEFAULT_WAKE: WakeOptions = { threshold: 0.5, patience: 2, cooldownMs: 1500 }

const MODELS = { mel: '/models/melspectrogram.onnx', embed: '/models/embedding_model.onnx', wake: '/models/hey_jarvis_v0.1.onnx' }

/** Decides from the worker's scores whether the wake word was said. */
export class WorkerWakeDetector implements WakeDetector {
  onWake: (() => void) | null = null
  private worker: WakeWorkerLike | null = null
  private loading: Promise<void> | null = null
  private streak = 0
  /** After a wake-up the score stays high for a few steps; the next one needs it to drop first. */
  private armed = true
  private lastWake = -Infinity
  private readonly options: WakeOptions

  constructor(
    private readonly createWorker: () => WakeWorkerLike,
    options: Partial<WakeOptions> = {},
  ) {
    this.options = { ...DEFAULT_WAKE, ...options }
  }

  setThreshold(threshold: number): void {
    this.options.threshold = threshold
  }

  load(): Promise<void> {
    this.loading ??= new Promise<void>((resolve, reject) => {
      const worker = this.createWorker()
      this.worker = worker
      worker.onmessage = (e) => {
        const message = e.data as { type: string; scores?: number[]; message?: string }
        if (message.type === 'ready') resolve()
        else if (message.type === 'error') {
          reject(new Error(message.message ?? 'The wake word could not be loaded'))
          this.loading = null
        } else if (message.type === 'scores') this.score(message.scores ?? [])
      }
      worker.postMessage({ type: 'load', models: MODELS })
    })
    return this.loading
  }

  push(pcm: Int16Array): void {
    // A copy: the microphone reuses its buffers, and the worker takes ownership of what it is sent.
    const copy = pcm.slice()
    this.worker?.postMessage({ type: 'frame', pcm: copy }, [copy.buffer])
  }

  private score(scores: number[]): void {
    const now = this.options.now ?? Date.now
    for (const score of scores) {
      if (score < this.options.threshold) {
        this.streak = 0
        this.armed = true
        continue
      }
      this.streak++
      if (this.armed && this.streak >= this.options.patience && now() - this.lastWake >= this.options.cooldownMs) {
        this.armed = false
        this.lastWake = now()
        this.onWake?.()
      }
    }
  }

  dispose(): void {
    this.worker?.terminate()
    this.worker = null
    this.loading = null
    this.onWake = null
  }
}

/** The real thing: a module worker that runs the models. */
export function createWakeDetector(options: Partial<WakeOptions> = {}): WorkerWakeDetector {
  return new WorkerWakeDetector(() => new Worker(new URL('./wakeWorker.ts', import.meta.url), { type: 'module' }), options)
}
