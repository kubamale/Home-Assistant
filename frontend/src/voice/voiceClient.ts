import type { VoiceReply } from '../types'
import { describeMicError, type FrameHandler, type Mic } from './mic'
import type { Speaker } from './player'
import type { Transport, TransportHandlers } from './transport'
import { ACKNOWLEDGING, INTERRUPTING, LISTENING, Vad, type VadEvent } from './vad'
import type { WakeDetector } from './wakeDetector'

/** What the page shows. */
export type VoiceState = 'idle' | 'connecting' | 'waiting' | 'listening' | 'hearing' | 'transcribing' | 'thinking' | 'speaking'

/**
 * once: one sentence, then the microphone is released (tap to talk).
 * handsfree: the microphone stays open and listens again after every answer; speaking over the answer interrupts it.
 * wake: the microphone stays open but nothing is sent anywhere until the wake word is heard on this device; then one
 * sentence is taken, and for a few seconds after the answer the next one needs no wake word. Saying the wake word
 * over the answer interrupts it.
 */
export type VoiceMode = 'once' | 'handsfree' | 'wake'

export interface VoiceEvents {
  onState(state: VoiceState): void
  onTranscript(text: string): void
  onReply(reply: VoiceReply): void
  /** Something that is not an error, such as nothing being heard. */
  onNotice(text: string): void
  onError(message: string): void
  /** The microphone's loudness, 0 to 1, for a level indicator. */
  onLevel?(rms: number): void
  /** The wake word was heard. */
  onWake?(): void
}

export interface VoiceDeps {
  openTransport(handlers: TransportHandlers): Promise<Transport>
  openMic(onFrame: FrameHandler): Promise<Mic>
  speaker: Speaker
  /** Needed for {@link VoiceMode} wake. */
  wake?: WakeDetector
}

/** Frames kept from before speech was noticed, so the first syllable is not lost (15 x 20 ms). */
const PRE_ROLL_FRAMES = 15
/**
 * With a wake word the look-back is longer (30 frames, 0.6 s) and survives the wake-up: the wake word is noticed a little after it
 * ends, so someone who goes straight on ("hey jarvis, turn off the light") has already said the first word by then.
 */
const WAKE_PRE_ROLL_FRAMES = 30
/** Frames sent together in one message (5 x 20 ms = 100 ms). */
const BATCH_FRAMES = 5
/** Tap to talk gives up when nothing is said for this long (400 x 20 ms = 8 s). */
const WAIT_FRAMES = 400
/** After the wake word, and after each answer, the next sentence is awaited this long before going back to waiting (300 x 20 ms = 6 s). */
const WAKE_WAIT_FRAMES = 300
/**
 * The wake reply ("Yes?") is asked for only if the person has not started talking within this long (20 x 20 ms = 0.4 s): someone who
 * says "hey jarvis, turn off the light" in one breath needs no answer, and playing one over them would only get in the way.
 */
const ACK_DELAY_FRAMES = 20
/** An idle connection is kept alive, and a dead one noticed, with a message this often. */
const PING_MS = 25_000

/**
 * Runs a voice conversation: listens for a sentence, sends it, shows what comes back and plays the spoken answer.
 * The microphone, the connection and the speaker are handed in, so the logic can be tested without any of them.
 * Frames arrive every 20 ms, and everything that counts time counts frames.
 */
export class VoiceClient {
  /** Whether answers should be spoken (the person can mute them on this device). */
  speak = true
  private conversationId: string | null = null

  private transport: Transport | null = null
  private mic: Mic | null = null
  private mode: VoiceMode | null = null
  private state: VoiceState = 'idle'
  private starting = false

  private readonly vad = new Vad(LISTENING)
  private readonly bargeVad = new Vad(INTERRUPTING)
  private preRoll: Int16Array[] = []
  private batch: Int16Array[] = []
  private streaming = false
  private waited = 0
  private audioActive = false
  private ping: ReturnType<typeof setInterval> | null = null
  /** The wake reply ("Yes?") is on its way: its audio comes between the server's ack and ack_end messages. */
  private ackStream = false
  /** The wake reply is being played. Its sound is in the microphone, so only clear speech over it counts as the person talking. */
  private acking = false
  /** Frames left until the wake reply is asked for; 0 or less when it is not going to be. */
  private ackIn = 0
  private readonly ackVad = new Vad(ACKNOWLEDGING)

  constructor(
    private readonly deps: VoiceDeps,
    private readonly events: VoiceEvents,
  ) {}

  setConversation(id: string | null): void {
    this.conversationId = id
  }

  get current(): VoiceState {
    return this.state
  }

  // ---- what the person does ----

  /** Starts listening. Call it from a tap: the browser asks for the microphone and allows sound only then. */
  async start(mode: VoiceMode): Promise<void> {
    if (this.mode || this.starting) return
    this.starting = true
    this.setState('connecting')
    try {
      if (mode === 'wake') await this.loadWake()
      await this.deps.speaker.resume()
      this.transport ??= await this.deps.openTransport({
        onJson: (m) => this.onServer(m),
        onBinary: (data) => {
          if (this.ackStream && !this.acking) return // the reply came too late: the person is already talking
          this.deps.speaker.play(data)
        },
        onClose: () => this.onClosed(),
      })
      this.mic = await this.deps.openMic((pcm, rms) => this.onFrame(pcm, rms))
    } catch (e) {
      this.events.onError(e instanceof Error && e.message.startsWith('The voice') ? e.message : describeMicError(e))
      this.release()
      this.setState('idle')
      return
    } finally {
      this.starting = false
    }
    this.mode = mode
    this.resetListening()
    if (mode === 'wake') {
      this.ping = setInterval(() => this.transport?.sendJson({ type: 'ping' }), PING_MS)
      this.setState('waiting')
    } else {
      this.setState('listening')
    }
  }

  private async loadWake(): Promise<void> {
    const wake = this.deps.wake
    if (!wake) throw new Error('The voice wake word is not available here.')
    try {
      await wake.load()
    } catch (e) {
      throw new Error(`The voice wake word could not be loaded${e instanceof Error ? ` (${e.message})` : ''}.`)
    }
    wake.onWake = () => this.onWake()
  }

  /** Stops listening (and speaking). A sentence being sent is dropped. */
  stop(): void {
    if (this.streaming) this.transport?.sendJson({ type: 'utterance_cancel' })
    if (this.audioActive || this.state === 'speaking') this.interrupt()
    this.mode = null
    this.release()
    this.streaming = false
    this.setState('idle')
  }

  /** Silences the answer and tells the server to stop making it. */
  interrupt(): void {
    this.deps.speaker.stop()
    this.audioActive = false
    this.transport?.sendJson({ type: 'interrupt' })
    if (this.mode === 'handsfree') {
      this.resetListening()
      this.setState('listening')
    } else if (this.mode === 'wake') {
      this.resetListening()
      this.setState('waiting')
    } else {
      this.mode = null
      this.releaseMic()
      this.setState('idle')
    }
  }

  /** Closes everything, for when the page goes away. */
  dispose(): void {
    this.mode = null
    this.release()
    this.deps.speaker.stop()
    this.deps.wake?.dispose()
  }

  // ---- the microphone ----

  private onFrame(pcm: Int16Array, rms: number): void {
    this.events.onLevel?.(rms)
    this.preRoll.push(pcm)
    if (this.preRoll.length > (this.mode === 'wake' ? WAKE_PRE_ROLL_FRAMES : PRE_ROLL_FRAMES)) this.preRoll.shift()

    if (this.mode === 'wake') this.deps.wake?.push(pcm)
    if (this.state === 'waiting') {
      this.vad.push(rms) // only to learn how loud the room is, so the sentence after the wake word is measured against it
      return
    }
    if (this.state === 'speaking') {
      // In hands-free mode, real speech over the answer interrupts it. In tap to talk the microphone stays deaf until the answer ends.
      // With a wake word, the wake word does it (see onWake).
      if (this.mode === 'handsfree' && this.bargeVad.push(rms) === 'start') this.bargeIn()
      return
    }
    if (this.state !== 'listening' && this.state !== 'hearing') return

    const event = this.acking ? (this.ackVad.push(rms) === 'start' ? this.talkOverAck() : null) : this.vad.push(rms)
    if (event !== 'start') this.tickAck()
    if (event === 'start') {
      this.beginUtterance()
    } else if (this.streaming) {
      this.queue(pcm)
    } else if (this.state === 'listening' && this.mode === 'once' && ++this.waited >= WAIT_FRAMES) {
      this.events.onNotice("I didn't hear anything.")
      this.stop()
      return
    } else if (this.state === 'listening' && this.mode === 'wake' && ++this.waited >= WAKE_WAIT_FRAMES) {
      this.resetListening()
      this.setState('waiting')
      return
    }
    if (event === 'end') this.endUtterance()
    if (event === 'discard') this.dropUtterance()
  }

  private beginUtterance(): void {
    this.ackIn = 0
    this.transport?.sendJson({ type: 'utterance_start', speak: this.speak, conversationId: this.conversationId })
    this.streaming = true
    this.batch = []
    for (const frame of this.preRoll) this.queue(frame) // includes the frame that was just heard
    this.setState('hearing')
  }

  private queue(frame: Int16Array): void {
    this.batch.push(frame)
    if (this.batch.length >= BATCH_FRAMES) this.flush()
  }

  private flush(): void {
    if (this.batch.length === 0) return
    const joined = new Int16Array(this.batch.reduce((n, f) => n + f.length, 0))
    let at = 0
    for (const frame of this.batch) {
      joined.set(frame, at)
      at += frame.length
    }
    this.batch = []
    this.transport?.sendBinary(joined)
  }

  private endUtterance(): void {
    this.flush()
    this.transport?.sendJson({ type: 'utterance_end' })
    this.streaming = false
    this.setState('transcribing')
  }

  private dropUtterance(): void {
    this.transport?.sendJson({ type: 'utterance_cancel' })
    this.streaming = false
    this.batch = []
    this.setState('listening')
  }

  /** The wake word was heard: take a sentence. Said over an answer, it silences the answer first. */
  private onWake(): void {
    if (this.mode !== 'wake') return
    if (this.state === 'speaking') {
      this.deps.speaker.stop()
      this.audioActive = false
      this.transport?.sendJson({ type: 'interrupt' })
    } else if (this.state !== 'waiting') {
      return // already in a conversation
    }
    const lookBack = this.preRoll
    this.resetListening()
    this.preRoll = lookBack // the first word of a sentence that goes straight on from the wake word is in here
    this.events.onWake?.()
    this.ackIn = ACK_DELAY_FRAMES
    this.setState('listening')
  }

  /** Counts down to asking the server for the wake reply, which is dropped if the person began to speak in the meantime. */
  private tickAck(): void {
    if (this.ackIn <= 0) return
    if (--this.ackIn === 0 && this.state === 'listening') {
      this.transport?.sendJson({ type: 'wake' }) // the server answers with its wake reply ("Yes?"), if one is set and ready
    }
  }

  /** The person spoke over the wake reply: silence it and take what they are saying as the sentence. */
  private talkOverAck(): VadEvent {
    this.deps.speaker.stop()
    this.acking = false
    this.ackVad.reset()
    this.vad.reset()
    this.vad.forceStart()
    return 'start'
  }

  /** The person spoke over the answer: silence it and send what they are saying as the next sentence. */
  private bargeIn(): void {
    this.deps.speaker.stop()
    this.audioActive = false
    this.transport?.sendJson({ type: 'interrupt' })
    this.vad.reset()
    this.vad.forceStart()
    this.beginUtterance()
  }

  private resetListening(): void {
    this.vad.reset()
    this.bargeVad.reset()
    this.preRoll = []
    this.batch = []
    this.streaming = false
    this.waited = 0
    this.acking = false
    this.ackIn = 0
  }

  // ---- the server ----

  private onServer(message: Record<string, unknown>): void {
    switch (message.type) {
      case 'state':
        this.onServerState(String(message.state))
        break
      case 'transcript':
        this.events.onTranscript(String(message.text ?? ''))
        break
      case 'reply': {
        const reply = message as unknown as VoiceReply
        this.conversationId = reply.conversationId
        this.events.onReply(reply)
        break
      }
      case 'audio_start':
        this.deps.speaker.begin()
        this.audioActive = true
        this.setState('speaking')
        break
      case 'audio_end':
        this.deps.speaker.whenDrained(() => this.finished())
        break
      case 'ack':
        this.ackStream = true
        if (this.mode === 'wake' && this.state === 'listening') {
          this.deps.speaker.begin()
          this.ackVad.reset()
          this.acking = true
        }
        break
      case 'ack_end':
        this.ackStream = false
        if (this.acking) this.deps.speaker.whenDrained(() => (this.acking = false))
        break
      case 'no_speech':
        this.events.onNotice("I didn't catch that.")
        break
      case 'error':
        this.events.onError(String(message.message ?? 'Something went wrong'))
        break
      default:
        break // ready, interrupted
    }
  }

  private onServerState(state: string): void {
    if (this.streaming || this.state === 'hearing') return // a newer sentence has begun; the old turn's news is stale
    if (state === 'transcribing' || state === 'thinking') this.setState(state)
    else if (state === 'idle' && !this.audioActive) this.finished()
  }

  /** The turn is over and everything has been heard. */
  private finished(): void {
    if (this.streaming || this.state === 'hearing') return
    this.audioActive = false
    if (this.mode === 'handsfree' || this.mode === 'wake') {
      this.resetListening() // with a wake word this is the few seconds in which the next sentence needs none
      this.setState('listening')
    } else {
      this.mode = null
      this.releaseMic()
      this.setState('idle')
    }
  }

  private onClosed(): void {
    this.ackStream = false
    if (this.ping) clearInterval(this.ping)
    this.ping = null
    this.transport = null
    if (this.mode || this.state !== 'idle') this.events.onError('The voice connection was lost.')
    this.mode = null
    this.releaseMic()
    this.deps.speaker.stop()
    this.streaming = false
    this.setState('idle')
  }

  // ---- housekeeping ----

  private releaseMic(): void {
    this.mic?.stop()
    this.mic = null
  }

  private release(): void {
    if (this.ping) clearInterval(this.ping)
    this.ping = null
    this.releaseMic()
    this.transport?.close()
    this.transport = null
  }

  private setState(state: VoiceState): void {
    if (state === this.state) return
    this.state = state
    this.events.onState(state)
  }
}
