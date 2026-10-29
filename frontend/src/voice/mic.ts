/** A microphone delivering 20 ms frames of 16 kHz 16-bit samples with their loudness. */
export interface Mic {
  stop(): void
}

export type FrameHandler = (pcm: Int16Array, rms: number) => void

/** Browsers only start audio after a tap; a page that was reloaded and has not been tapped yet has to ask for one. */
export const NEEDS_TAP = 'The voice needs a tap to start.'

/** Lets audio start, or says that it needs a tap: an unallowed start would otherwise wait forever. */
export async function allowAudio(context: AudioContext): Promise<void> {
  if (context.state === 'suspended') {
    await Promise.race([context.resume(), new Promise((resolve) => setTimeout(resolve, 800))])
  }
  if (context.state !== 'running') throw new Error(NEEDS_TAP)
}

/** Why the microphone could not be opened, in words for the user. */
export function describeMicError(e: unknown): string {
  const name = e instanceof DOMException ? e.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return "The microphone is blocked. Allow it for this site in the browser's settings, then try again."
  }
  if (name === 'NotFoundError') return 'No microphone was found.'
  if (name === 'NotReadableError') return 'The microphone is in use by another app.'
  if (!navigator.mediaDevices?.getUserMedia) return 'This browser cannot use the microphone here (it needs HTTPS or localhost).'
  return 'The microphone could not be started.'
}

/**
 * Opens the microphone with the browser's own echo cancellation, noise suppression and level control, which is
 * what keeps the assistant's voice out of its own ears when it speaks through the same device's speakers.
 */
export async function openMic(onFrame: FrameHandler): Promise<Mic> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
  })
  const context = new AudioContext()
  try {
    await allowAudio(context)
    await context.audioWorklet.addModule('/voice-worklet.js')
    const source = context.createMediaStreamSource(stream)
    const node = new AudioWorkletNode(context, 'pcm-capture', { processorOptions: { targetRate: 16000 } })
    node.port.onmessage = (e: MessageEvent<{ pcm: ArrayBuffer; rms: number }>) => onFrame(new Int16Array(e.data.pcm), e.data.rms)
    source.connect(node) // not to the speakers: the microphone must not play back into the room
    return {
      stop() {
        node.port.onmessage = null
        source.disconnect()
        stream.getTracks().forEach((t) => t.stop())
        void context.close()
      },
    }
  } catch (e) {
    stream.getTracks().forEach((t) => t.stop())
    void context.close()
    throw e
  }
}
