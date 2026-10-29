import { allowAudio } from './mic'
import { int16BytesToFloat32 } from './pcm'

/** Where the audio goes. The real one is {@link PcmPlayer}; tests use a fake. */
export interface Speaker {
  /** Browsers only let a page make sound after a tap; call this from the tap. */
  resume(): Promise<void>
  /** Starts a new spoken answer, so its first piece is buffered a little before it plays. */
  begin(): void
  play(pcm: ArrayBuffer): void
  /** Silences everything at once. */
  stop(): void
  /** Calls back once everything handed over so far has been heard. */
  whenDrained(done: () => void): void
}

/**
 * When the next piece of audio should start playing: right after the previous one if it is still playing,
 * otherwise a little from now (more for the first piece, so the next ones arrive in time).
 */
export function planStart(now: number, nextTime: number, first: boolean, prebuffer: number): number {
  if (first) return now + prebuffer
  if (nextTime <= now) return now + 0.03 // the audio ran dry; start again with a small lead
  return nextTime
}

/** Plays 24 kHz mono PCM as it arrives, back to back without gaps. */
export class PcmPlayer implements Speaker {
  private context: AudioContext | null = null
  private nextTime = 0
  private first = true
  private readonly sources = new Set<AudioBufferSourceNode>()
  private timer: number | undefined

  constructor(
    private readonly sampleRate = 24000,
    private readonly prebuffer = 0.2,
  ) {}

  async resume(): Promise<void> {
    this.context ??= new AudioContext()
    await allowAudio(this.context)
  }

  begin(): void {
    this.first = true
  }

  play(pcm: ArrayBuffer): void {
    const context = this.context
    if (!context) return
    const samples = int16BytesToFloat32(pcm)
    if (samples.length === 0) return
    const buffer = context.createBuffer(1, samples.length, this.sampleRate)
    buffer.copyToChannel(new Float32Array(samples), 0)
    const source = context.createBufferSource()
    source.buffer = buffer
    source.connect(context.destination)
    const start = planStart(context.currentTime, this.nextTime, this.first, this.prebuffer)
    source.start(start)
    this.nextTime = start + buffer.duration
    this.first = false
    this.sources.add(source)
    source.onended = () => this.sources.delete(source)
  }

  stop(): void {
    window.clearTimeout(this.timer)
    for (const source of this.sources) {
      try {
        source.stop()
      } catch {
        // already ended
      }
    }
    this.sources.clear()
    this.nextTime = 0
    this.first = true
  }

  whenDrained(done: () => void): void {
    window.clearTimeout(this.timer)
    const remaining = this.context ? Math.max(0, this.nextTime - this.context.currentTime) : 0
    this.timer = window.setTimeout(done, remaining * 1000 + 40)
  }
}
