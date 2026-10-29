import type { VoiceReply } from '../types'
import type { FrameHandler, Mic } from './mic'
import type { Speaker } from './player'
import type { Transport, TransportHandlers } from './transport'
import type { WakeDetector } from './wakeDetector'
import { VoiceClient, type VoiceMode, type VoiceState } from './voiceClient'

const QUIET = 0.004
const SPEECH = 0.12
const LOUD = 0.25

class FakeTransport implements Transport {
  json: Record<string, unknown>[] = []
  binary: Int16Array[] = []
  closed = false
  sendJson(message: Record<string, unknown>) {
    this.json.push(message)
  }
  sendBinary(data: Int16Array) {
    this.binary.push(data)
  }
  close() {
    this.closed = true
  }
  types() {
    return this.json.map((m) => m.type)
  }
}

class FakeWake implements WakeDetector {
  loads = 0
  pushed = 0
  failLoad: Error | null = null
  disposed = false
  onWake: (() => void) | null = null
  async load() {
    this.loads++
    if (this.failLoad) throw this.failLoad
  }
  push() {
    this.pushed++
  }
  dispose() {
    this.disposed = true
  }
  /** The wake word is heard. */
  hear() {
    this.onWake?.()
  }
}

class FakeSpeaker implements Speaker {
  resumed = 0
  begun = 0
  stopped = 0
  played: ArrayBuffer[] = []
  private drains: (() => void)[] = []
  async resume() {
    this.resumed++
  }
  begin() {
    this.begun++
  }
  play(pcm: ArrayBuffer) {
    this.played.push(pcm)
  }
  stop() {
    this.stopped++
  }
  whenDrained(done: () => void) {
    this.drains.push(done)
  }
  drain() {
    this.drains.splice(0).forEach((done) => done())
  }
}

function setup() {
  const transport = new FakeTransport()
  const speaker = new FakeSpeaker()
  const wake = new FakeWake()
  let wakes = 0
  let handlers!: TransportHandlers
  let onFrame!: FrameHandler
  let micOpen = false
  let micError: unknown = null
  const mic: Mic = {
    stop() {
      micOpen = false
    },
  }
  const states: VoiceState[] = []
  const transcripts: string[] = []
  const replies: VoiceReply[] = []
  const notices: string[] = []
  const errors: string[] = []
  const client = new VoiceClient(
    {
      openTransport: async (h) => {
        handlers = h
        return transport
      },
      openMic: async (f) => {
        if (micError) throw micError
        onFrame = f
        micOpen = true
        return mic
      },
      speaker,
      wake,
    },
    {
      onWake: () => wakes++,
      onState: (s) => states.push(s),
      onTranscript: (t) => transcripts.push(t),
      onReply: (r) => replies.push(r),
      onNotice: (n) => notices.push(n),
      onError: (e) => errors.push(e),
    },
  )
  const frames = (rms: number, count: number) => {
    for (let i = 0; i < count; i++) onFrame(new Int16Array(320).fill(1000), rms)
  }
  const server = (message: Record<string, unknown>) => handlers.onJson(message)
  return {
    client, transport, speaker, wake, wakes: () => wakes, states, transcripts, replies, notices, errors, frames, server,
    handlers: () => handlers,
    micOpen: () => micOpen,
    failMic: (e: unknown) => {
      micError = e
    },
  }
}

type Harness = ReturnType<typeof setup>

/** Opens the microphone and lets the detector measure a quiet room. */
async function listening(mode: VoiceMode = 'once'): Promise<Harness> {
  const h = setup()
  await h.client.start(mode)
  h.frames(QUIET, 25)
  return h
}

/** A whole spoken sentence: speech, then a pause that ends it. */
function sentence(h: Harness) {
  h.frames(SPEECH, 40)
  h.frames(QUIET, 35)
}

const reply = (text: string, id = 'c1') => ({ type: 'reply', reply: text, actions: ['Lamp: on'], failed: [], conversationId: id })

describe('VoiceClient, tap to talk', () => {
  it('opens the connection and the microphone from the tap', async () => {
    const h = setup()
    await h.client.start('once')
    expect(h.states).toEqual(['connecting', 'listening'])
    expect(h.speaker.resumed).toBe(1)
    expect(h.micOpen()).toBe(true)
  })

  it('sends a sentence, shows what comes back, plays the answer and lets go of the microphone', async () => {
    const h = await listening()
    sentence(h)
    expect(h.transport.json[0]).toEqual({ type: 'utterance_start', speak: true, conversationId: null })
    expect(h.transport.types()).toEqual(['utterance_start', 'utterance_end'])
    expect(h.states.at(-1)).toBe('transcribing')
    expect(h.transport.binary.length).toBeGreaterThan(0)

    h.server({ type: 'state', state: 'transcribing' })
    h.server({ type: 'transcript', text: 'turn on the lamp' })
    h.server({ type: 'state', state: 'thinking' })
    expect(h.states.at(-1)).toBe('thinking')
    h.server(reply('The lamp is on.'))
    h.server({ type: 'state', state: 'speaking' })
    h.server({ type: 'audio_start', sampleRate: 24000 })
    expect(h.states.at(-1)).toBe('speaking')
    h.handlers().onBinary(new ArrayBuffer(100))
    h.server({ type: 'audio_end' })
    h.server({ type: 'state', state: 'idle' })
    expect(h.states.at(-1)).toBe('speaking') // still being heard

    h.speaker.drain()
    expect(h.states.at(-1)).toBe('idle')
    expect(h.micOpen()).toBe(false)
    expect(h.transcripts).toEqual(['turn on the lamp'])
    expect(h.replies[0].reply).toBe('The lamp is on.')
    expect(h.speaker.begun).toBe(1)
    expect(h.speaker.played).toHaveLength(1)
  })

  it('sends the whole sentence, including the moment before speech was noticed, in batches of 100 ms', async () => {
    const h = await listening()
    sentence(h)
    const samples = h.transport.binary.reduce((n, b) => n + b.length, 0)
    // 15 frames of lead-in (which includes the first loud ones), the other 35 loud frames and all 35 quiet ones
    expect(samples).toBe((15 + 35 + 35) * 320)
    expect(Math.max(...h.transport.binary.map((b) => b.length))).toBeLessThanOrEqual(5 * 320)
  })

  it('carries the conversation on to the next sentence', async () => {
    const h = await listening()
    sentence(h)
    h.server(reply('Done.', 'abc'))
    h.server({ type: 'state', state: 'idle' })
    await h.client.start('once')
    h.frames(QUIET, 25)
    sentence(h)
    const starts = h.transport.json.filter((m) => m.type === 'utterance_start')
    expect(starts[1].conversationId).toBe('abc')
  })

  it('can ask for no spoken answer', async () => {
    const h = setup()
    h.client.speak = false
    await h.client.start('once')
    h.frames(QUIET, 25)
    sentence(h)
    expect(h.transport.json[0].speak).toBe(false)
  })

  it('ignores speech while the answer is playing', async () => {
    const h = await listening()
    sentence(h)
    h.server({ type: 'audio_start' })
    const before = h.transport.json.length
    h.frames(LOUD, 100)
    expect(h.transport.json.length).toBe(before)
    expect(h.speaker.stopped).toBe(0)
  })

  it('gives up after eight seconds of silence', async () => {
    const h = setup()
    await h.client.start('once')
    h.frames(QUIET, 400)
    expect(h.notices).toEqual(["I didn't hear anything."])
    expect(h.states.at(-1)).toBe('idle')
    expect(h.micOpen()).toBe(false)
    expect(h.transport.closed).toBe(true)
  })

  it('drops a cough and keeps listening', async () => {
    const h = await listening()
    h.frames(SPEECH, 6)
    h.frames(QUIET, 35)
    expect(h.transport.types()).toEqual(['utterance_start', 'utterance_cancel'])
    expect(h.states.at(-1)).toBe('listening')
  })

  it('can be cancelled while a sentence is being sent', async () => {
    const h = await listening()
    h.frames(SPEECH, 20)
    h.client.stop()
    expect(h.transport.types()).toEqual(['utterance_start', 'utterance_cancel'])
    expect(h.states.at(-1)).toBe('idle')
    expect(h.micOpen()).toBe(false)
  })

  it('stops the answer when the person taps to interrupt', async () => {
    const h = await listening()
    sentence(h)
    h.server({ type: 'audio_start' })
    h.client.interrupt()
    expect(h.speaker.stopped).toBe(1)
    expect(h.transport.types().at(-1)).toBe('interrupt')
    expect(h.states.at(-1)).toBe('idle')
  })

  it('says so when nothing was caught, and when something went wrong', async () => {
    const h = await listening()
    sentence(h)
    h.server({ type: 'no_speech' })
    h.server({ type: 'state', state: 'idle' })
    expect(h.notices).toEqual(["I didn't catch that."])
    expect(h.states.at(-1)).toBe('idle')

    const g = await listening()
    sentence(g)
    g.server({ type: 'error', message: 'The speech service is busy' })
    g.server({ type: 'state', state: 'idle' })
    expect(g.errors).toEqual(['The speech service is busy'])
    expect(g.states.at(-1)).toBe('idle')
  })

  it('explains a blocked microphone', async () => {
    const h = setup()
    h.failMic(new DOMException('denied', 'NotAllowedError'))
    await h.client.start('once')
    expect(h.errors[0]).toMatch(/blocked/i)
    expect(h.states.at(-1)).toBe('idle')
    expect(h.transport.closed).toBe(true)
  })

  it('reports a lost connection', async () => {
    const h = await listening()
    h.handlers().onClose()
    expect(h.errors).toEqual(['The voice connection was lost.'])
    expect(h.states.at(-1)).toBe('idle')
    expect(h.micOpen()).toBe(false)
  })
})

describe('VoiceClient, hands-free', () => {
  it('keeps listening after every answer', async () => {
    const h = await listening('handsfree')
    sentence(h)
    h.server(reply('Done.'))
    h.server({ type: 'state', state: 'idle' })
    expect(h.states.at(-1)).toBe('listening')
    expect(h.micOpen()).toBe(true)
    sentence(h)
    expect(h.transport.types().filter((t) => t === 'utterance_start')).toHaveLength(2)
  })

  it('stops the answer and takes the next sentence when the person speaks over it', async () => {
    const h = await listening('handsfree')
    sentence(h)
    h.server({ type: 'audio_start' })
    h.frames(0.03, 100) // the assistant's own voice leaking in: not an interruption
    expect(h.speaker.stopped).toBe(0)

    h.frames(LOUD, 15)
    expect(h.speaker.stopped).toBe(1)
    expect(h.transport.types().slice(-2)).toEqual(['interrupt', 'utterance_start'])
    expect(h.states.at(-1)).toBe('hearing')

    h.server({ type: 'interrupted' })
    h.server({ type: 'state', state: 'idle' }) // news about the turn that was cut off
    expect(h.states.at(-1)).toBe('hearing')

    h.frames(LOUD, 20)
    h.frames(QUIET, 35)
    expect(h.transport.types().at(-1)).toBe('utterance_end')
  })

  it('stays open until it is switched off', async () => {
    const h = await listening('handsfree')
    h.frames(QUIET, 1000)
    expect(h.states.at(-1)).toBe('listening')
    expect(h.notices).toEqual([])
    h.client.stop()
    expect(h.states.at(-1)).toBe('idle')
    expect(h.micOpen()).toBe(false)
  })
})

describe('VoiceClient, wake word', () => {
  /** In the wake mode, with the room measured. */
  async function waiting(): Promise<Harness> {
    const h = setup()
    await h.client.start('wake')
    h.frames(QUIET, 25)
    return h
  }

  it('loads the wake word, opens the microphone and waits', async () => {
    const h = setup()
    await h.client.start('wake')
    expect(h.states).toEqual(['connecting', 'waiting'])
    expect(h.wake.loads).toBe(1)
    expect(h.micOpen()).toBe(true)
  })

  it('sends nothing anywhere while waiting, however much is said, and gives every frame to the wake word', async () => {
    const h = await waiting()
    h.frames(SPEECH, 200)
    h.frames(LOUD, 50)
    expect(h.transport.json).toEqual([])
    expect(h.transport.binary).toEqual([])
    expect(h.wake.pushed).toBe(25 + 250)
    expect(h.states.at(-1)).toBe('waiting')
  })

  it('does not start when the wake word cannot be loaded', async () => {
    const h = setup()
    h.wake.failLoad = new Error('404')
    await h.client.start('wake')
    expect(h.errors[0]).toBe('The voice wake word could not be loaded (404).')
    expect(h.states.at(-1)).toBe('idle')
    expect(h.micOpen()).toBe(false)
  })

  it('takes the sentence after the wake word, measured against the room it already knew, keeping the moment before the wake-up', async () => {
    const h = await waiting()
    h.frames(SPEECH, 30) // "hey jarvis": heard, not sent
    h.wake.hear()
    expect(h.states.at(-1)).toBe('listening')
    expect(h.wakes()).toBe(1)

    sentence(h)
    expect(h.transport.types()).toEqual(['utterance_start', 'utterance_end'])
    const samples = h.transport.binary.reduce((n, b) => n + b.length, 0)
    // The wake word is noticed a little after it ends, so a sentence that goes straight on has its first word in the last 0.6 s: that
    // is kept (30 frames, of which 5 are the new speech), then the rest of the speech and the pause.
    expect(samples).toBe((30 + 35 + 35) * 320)
    expect(h.states.at(-1)).toBe('transcribing')
  })

  it('stays open for the next sentence after an answer, which needs no wake word', async () => {
    const h = await waiting()
    h.wake.hear()
    sentence(h)
    h.server(reply('The lamp is on.', 'abc'))
    h.server({ type: 'state', state: 'idle' })
    expect(h.states.at(-1)).toBe('listening')
    expect(h.micOpen()).toBe(true)

    sentence(h)
    const starts = h.transport.json.filter((m) => m.type === 'utterance_start')
    expect(starts).toHaveLength(2)
    expect(starts[1].conversationId).toBe('abc')
  })

  it('goes back to waiting, quietly, when nothing is said after the wake word or after an answer', async () => {
    const h = await waiting()
    h.wake.hear()
    h.frames(QUIET, 299)
    expect(h.states.at(-1)).toBe('listening')
    h.frames(QUIET, 1)
    expect(h.states.at(-1)).toBe('waiting')
    expect(h.notices).toEqual([])
    expect(h.transport.types()).toEqual(['wake']) // only that the wake word was heard: no audio
    expect(h.transport.binary).toEqual([])

    h.wake.hear()
    sentence(h)
    h.server(reply('Done.'))
    h.server({ type: 'state', state: 'idle' })
    h.frames(QUIET, 300)
    expect(h.states.at(-1)).toBe('waiting')
    expect(h.micOpen()).toBe(true)
  })

  it('is interrupted by the wake word while it is speaking, and listens', async () => {
    const h = await waiting()
    h.wake.hear()
    sentence(h)
    h.server(reply('The lamp is on, and the weather is mild, with some sun later.'))
    h.server({ type: 'audio_start' })
    expect(h.states.at(-1)).toBe('speaking')

    h.frames(LOUD, 100) // speech over the answer alone does nothing in this mode
    expect(h.states.at(-1)).toBe('speaking')

    h.wake.hear()
    expect(h.speaker.stopped).toBeGreaterThan(0)
    expect(h.transport.types()).toContain('interrupt')
    expect(h.states.at(-1)).toBe('listening')
    expect(h.wakes()).toBe(2)
  })

  it('ignores the wake word while it is already busy with a sentence', async () => {
    const h = await waiting()
    h.wake.hear()
    sentence(h)
    expect(h.states.at(-1)).toBe('transcribing')
    h.wake.hear()
    expect(h.states.at(-1)).toBe('transcribing')
    expect(h.wakes()).toBe(1)

    h.server({ type: 'state', state: 'idle' })
    h.frames(SPEECH, 10)
    h.frames(QUIET, 5)
    h.wake.hear() // listening already
    expect(h.wakes()).toBe(1)
  })

  it('can be told to stop answering and go back to waiting', async () => {
    const h = await waiting()
    h.wake.hear()
    sentence(h)
    h.server({ type: 'audio_start' })
    h.client.interrupt()
    expect(h.states.at(-1)).toBe('waiting')
    expect(h.micOpen()).toBe(true)
  })

  it('stops completely, and lets the wake word go when the page goes away', async () => {
    const h = await waiting()
    h.client.stop()
    expect(h.states.at(-1)).toBe('idle')
    expect(h.micOpen()).toBe(false)
    h.client.dispose()
    expect(h.wake.disposed).toBe(true)
  })

  it('reports a lost connection so that it can be set up again', async () => {
    const h = await waiting()
    h.handlers().onClose()
    expect(h.errors).toEqual(['The voice connection was lost.'])
    expect(h.states.at(-1)).toBe('idle')
    expect(h.micOpen()).toBe(false)
  })

  it('keeps the idle connection alive with a message every 25 seconds, until it is stopped', async () => {
    vi.useFakeTimers()
    try {
      const h = await waiting()
      vi.advanceTimersByTime(25_000)
      expect(h.transport.types()).toEqual(['ping'])
      vi.advanceTimersByTime(50_000)
      expect(h.transport.types()).toEqual(['ping', 'ping', 'ping'])
      h.client.stop()
      vi.advanceTimersByTime(60_000)
      expect(h.transport.types()).toHaveLength(3)
    } finally {
      vi.useRealTimers()
    }
  })

  describe('the reply to the wake word ("Yes?")', () => {
    const ack = (h: Harness) => {
      h.server({ type: 'ack', sampleRate: 24000 })
      h.handlers().onBinary(new ArrayBuffer(100))
    }

    it('asks the server for it after a beat of silence, not at once', async () => {
      const h = await waiting()
      h.wake.hear()
      expect(h.transport.types()).toEqual([])
      h.frames(QUIET, 19)
      expect(h.transport.types()).toEqual([])
      h.frames(QUIET, 1) // 0.4 s
      expect(h.transport.types()).toEqual(['wake'])
      h.frames(QUIET, 50)
      expect(h.transport.types()).toEqual(['wake']) // once
    })

    it('does not ask for it when the person goes straight on with the sentence', async () => {
      const h = await waiting()
      h.wake.hear()
      h.frames(SPEECH, 10) // "hey jarvis, turn off the light" in one breath
      expect(h.states.at(-1)).toBe('hearing')
      h.frames(SPEECH, 30)
      h.frames(QUIET, 35)
      expect(h.transport.types()).toEqual(['utterance_start', 'utterance_end'])
    })

    it('plays it, and listens for the sentence once it has been heard', async () => {
      const h = await waiting()
      h.wake.hear()
      h.frames(QUIET, 20)
      ack(h)
      expect(h.speaker.begun).toBe(1)
      expect(h.speaker.played).toHaveLength(1)
      expect(h.states.at(-1)).toBe('listening') // the pill does not claim to be speaking: the person is to talk next
      h.server({ type: 'ack_end' })
      h.speaker.drain()

      sentence(h)
      expect(h.transport.types()).toEqual(['wake', 'utterance_start', 'utterance_end'])
    })

    it('does not take the echo of its own voice for the person speaking', async () => {
      const h = await waiting()
      h.wake.hear()
      h.frames(QUIET, 20)
      ack(h)
      h.frames(0.03, 60) // what leaks into the microphone: more than the room, less than someone talking
      expect(h.transport.types()).toEqual(['wake'])
      expect(h.speaker.stopped).toBe(0)
    })

    it('is cut short when the person talks over it, and what they say is taken as the sentence', async () => {
      const h = await waiting()
      h.wake.hear()
      h.frames(QUIET, 20)
      ack(h)
      h.frames(SPEECH, 7)
      expect(h.transport.types()).toEqual(['wake'])
      h.frames(SPEECH, 1) // 160 ms of clear speech
      expect(h.speaker.stopped).toBe(1)
      expect(h.transport.types()).toEqual(['wake', 'utterance_start'])
      h.frames(SPEECH, 20)
      h.frames(QUIET, 35)
      expect(h.transport.types()).toEqual(['wake', 'utterance_start', 'utterance_end'])
    })

    it('is not played when it comes too late, after the person has started talking', async () => {
      const h = await waiting()
      h.wake.hear()
      h.frames(QUIET, 20)
      h.frames(SPEECH, 10) // the reply was on its way when they began
      expect(h.states.at(-1)).toBe('hearing')
      ack(h)
      expect(h.speaker.played).toHaveLength(0)
      expect(h.speaker.begun).toBe(0)
      h.server({ type: 'ack_end' })
      expect(h.states.at(-1)).toBe('hearing')
    })

    it('changes nothing when the server has no reply to give', async () => {
      const h = await waiting()
      h.wake.hear()
      h.frames(QUIET, 20)
      sentence(h)
      expect(h.transport.types()).toEqual(['wake', 'utterance_start', 'utterance_end'])
      expect(h.speaker.begun).toBe(0)
    })
  })

  it('is not available without a wake word detector', async () => {
    const h = setup()
    const bare = new VoiceClient({ openTransport: async () => h.transport, openMic: async () => ({ stop() {} }), speaker: h.speaker }, {
      onState: () => {},
      onTranscript: () => {},
      onReply: () => {},
      onNotice: () => {},
      onError: (e) => h.errors.push(e),
    })
    await bare.start('wake')
    expect(h.errors[0]).toBe('The voice wake word is not available here.')
  })
})
