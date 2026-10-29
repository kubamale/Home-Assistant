import { INTERRUPTING, LISTENING, Vad, type VadEvent } from './vad'

const QUIET = 0.004
const SPEECH = 0.12

/** Feeds the same loudness for a number of frames and returns the events that came out. */
function feed(vad: Vad, rms: number, frames: number): VadEvent[] {
  const events: VadEvent[] = []
  for (let i = 0; i < frames; i++) {
    const event = vad.push(rms)
    if (event) events.push(event)
  }
  return events
}

/** A detector that has already measured a quiet room. */
function ready(options = LISTENING): Vad {
  const vad = new Vad(options)
  feed(vad, QUIET, options.calibrationFrames)
  return vad
}

describe('Vad', () => {
  it('stays quiet in a quiet room', () => {
    expect(feed(ready(), QUIET, 500)).toEqual([])
  })

  it('does not listen while it measures the room', () => {
    expect(feed(new Vad(LISTENING), SPEECH, LISTENING.calibrationFrames)).toEqual([])
  })

  it('notices speech after a few loud frames, and the end after a pause', () => {
    const vad = ready()
    expect(feed(vad, SPEECH, LISTENING.minSpeechFrames - 1)).toEqual([])
    expect(vad.inSpeech).toBe(false)
    expect(feed(vad, SPEECH, 1)).toEqual(['start'])
    expect(feed(vad, SPEECH, 60)).toEqual([])
    expect(feed(vad, QUIET, LISTENING.endSilenceFrames - 1)).toEqual([])
    expect(feed(vad, QUIET, 1)).toEqual(['end'])
    expect(vad.inSpeech).toBe(false)
  })

  it('bridges the short pauses inside a sentence', () => {
    const vad = ready()
    feed(vad, SPEECH, 10)
    expect(feed(vad, QUIET, 20)).toEqual([]) // 400 ms of silence is not the end
    expect(feed(vad, SPEECH, 10)).toEqual([])
    expect(vad.inSpeech).toBe(true)
  })

  it('discards a blip that is too short to be a sentence', () => {
    const vad = ready()
    feed(vad, SPEECH, LISTENING.minSpeechFrames)
    expect(feed(vad, QUIET, LISTENING.endSilenceFrames)).toEqual(['discard'])
  })

  it('cuts off a sentence that never stops', () => {
    const vad = ready()
    const events = feed(vad, SPEECH, LISTENING.maxSpeechFrames + 5)
    expect(events[0]).toBe('start')
    expect(events[1]).toBe('end')
  })

  it('ignores sounds below the minimum however quiet the room is', () => {
    expect(feed(ready(), 0.01, 300)).toEqual([])
  })

  it('learns a noisy room, so steady noise is not speech', () => {
    const vad = new Vad(LISTENING)
    expect(feed(vad, 0.03, 200)).toEqual([]) // the room itself is at 0.03
    expect(feed(vad, 0.15, 5)).toEqual(['start']) // a voice on top of it is
  })

  it('can be told that speech has begun', () => {
    const vad = ready()
    vad.forceStart()
    expect(vad.inSpeech).toBe(true)
    expect(feed(vad, SPEECH, 40)).toEqual([])
    expect(feed(vad, QUIET, LISTENING.endSilenceFrames)).toEqual(['end'])
  })

  it('needs longer, louder speech to interrupt the assistant', () => {
    const vad = new Vad(INTERRUPTING)
    expect(feed(vad, 0.04, 100)).toEqual([]) // its own voice leaking into the microphone
    expect(feed(vad, 0.2, INTERRUPTING.minSpeechFrames - 1)).toEqual([])
    expect(feed(vad, 0.2, 1)).toEqual(['start'])
  })
})
