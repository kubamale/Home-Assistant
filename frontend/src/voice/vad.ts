/**
 * Finds where a spoken sentence starts and ends in a stream of loudness readings, one per 20 ms frame.
 * It learns the room's background level and counts a frame as speech when it is clearly above it.
 * Pure arithmetic, so it can be tested without a microphone.
 */
export interface VadOptions {
  /** Loud frames in a row before speech is considered started. */
  minSpeechFrames: number
  /** Quiet frames in a row that end a sentence. */
  endSilenceFrames: number
  /** A sentence this long is ended anyway, so a noisy room cannot record forever. */
  maxSpeechFrames: number
  /** Shorter than this is a cough or a click, not a sentence. */
  minUtteranceFrames: number
  /** The lowest loudness that can count as speech, however quiet the room. */
  minThreshold: number
  /** How many times louder than the background speech has to be. */
  noiseMultiplier: number
  /** Frames at the start used to measure the room before listening for speech (the quietest quarter counts). */
  calibrationFrames: number
}

export const FRAME_MS = 20

export const LISTENING: VadOptions = {
  minSpeechFrames: 5, // 100 ms
  endSilenceFrames: 35, // 700 ms
  maxSpeechFrames: 600, // 12 s
  minUtteranceFrames: 15, // 300 ms
  minThreshold: 0.015,
  noiseMultiplier: 3,
  calibrationFrames: 25, // 500 ms
}

/** While the assistant is talking its own voice leaks into the microphone, so interrupting needs real, sustained speech. */
export const INTERRUPTING: VadOptions = { ...LISTENING, minSpeechFrames: 15, minThreshold: 0.05, noiseMultiplier: 4, calibrationFrames: 0 }

/**
 * While the wake reply ("Yes?") plays its sound is in the microphone, so only clear speech counts as the person talking over it,
 * but a little sooner than for a long answer: the reply is short and the person is usually about to start.
 */
export const ACKNOWLEDGING: VadOptions = { ...INTERRUPTING, minSpeechFrames: 8 }

export type VadEvent = 'start' | 'end' | 'discard'

export class Vad {
  private noise = 0.004
  private speaking = false
  private loud = 0
  private quiet = 0
  private length = 0
  private readonly calibration: number[] = []

  constructor(private readonly options: VadOptions = LISTENING) {}

  get threshold(): number {
    return Math.max(this.options.minThreshold, this.noise * this.options.noiseMultiplier)
  }

  get inSpeech(): boolean {
    return this.speaking
  }

  /** Feeds one frame's loudness. Returns an event when a sentence starts or ends. */
  push(rms: number): VadEvent | null {
    if (this.calibration.length < this.options.calibrationFrames) {
      this.calibration.push(rms)
      if (this.calibration.length === this.options.calibrationFrames) {
        const sorted = [...this.calibration].sort((a, b) => a - b)
        this.noise = Math.max(0.002, sorted[Math.floor(sorted.length / 4)])
      }
      return null
    }
    const isLoud = rms > this.threshold
    if (!this.speaking) {
      if (!isLoud) this.noise = this.noise * 0.98 + rms * 0.02
      this.loud = isLoud ? this.loud + 1 : 0
      if (this.loud >= this.options.minSpeechFrames) {
        this.speaking = true
        this.length = this.loud
        this.quiet = 0
        return 'start'
      }
      return null
    }
    this.length += 1
    this.quiet = isLoud ? 0 : this.quiet + 1
    if (this.quiet >= this.options.endSilenceFrames) {
      const spoken = this.length - this.quiet
      this.reset()
      return spoken >= this.options.minUtteranceFrames ? 'end' : 'discard'
    }
    if (this.length >= this.options.maxSpeechFrames) {
      this.reset()
      return 'end'
    }
    return null
  }

  /** Treats speech as already under way, for when something else (an interruption) decided it started. */
  forceStart(): void {
    this.speaking = true
    this.loud = this.options.minSpeechFrames
    this.length = this.options.minSpeechFrames
    this.quiet = 0
  }

  reset(): void {
    this.speaking = false
    this.loud = 0
    this.quiet = 0
    this.length = 0
  }
}
