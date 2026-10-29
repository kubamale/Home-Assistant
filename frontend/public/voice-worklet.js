// Runs on the audio thread. Takes the microphone's audio (whatever rate the device uses), averages it down to
// 16 kHz mono and posts it to the page in 20 ms frames of 16-bit samples, each with its loudness (RMS, 0 to 1).
// Plain JavaScript on purpose: it is loaded by the browser as a module, not bundled.
class PcmCapture extends AudioWorkletProcessor {
  constructor(options) {
    super()
    const target = (options.processorOptions && options.processorOptions.targetRate) || 16000
    this.frameSize = Math.round(target * 0.02)
    this.ratio = sampleRate / target
    this.pos = 0
    this.sum = 0
    this.count = 0
    this.frame = new Int16Array(this.frameSize)
    this.filled = 0
    this.squares = 0
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (!channel) return true
    for (let i = 0; i < channel.length; i++) {
      this.sum += channel[i]
      this.count += 1
      this.pos += 1
      if (this.pos < this.ratio) continue
      this.pos -= this.ratio
      const sample = this.sum / this.count
      this.sum = 0
      this.count = 0
      const clipped = Math.max(-1, Math.min(1, sample))
      this.frame[this.filled++] = clipped < 0 ? clipped * 32768 : clipped * 32767
      this.squares += sample * sample
      if (this.filled === this.frameSize) {
        const pcm = this.frame
        this.port.postMessage({ pcm: pcm.buffer, rms: Math.sqrt(this.squares / this.frameSize) }, [pcm.buffer])
        this.frame = new Int16Array(this.frameSize)
        this.filled = 0
        this.squares = 0
      }
    }
    return true
  }
}

registerProcessor('pcm-capture', PcmCapture)
