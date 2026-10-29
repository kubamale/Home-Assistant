import { WorkerWakeDetector, wakeThreshold, type WakeWorkerLike } from './wakeDetector'

class FakeWorker implements WakeWorkerLike {
  sent: { message: Record<string, unknown>; transfer?: Transferable[] }[] = []
  onmessage: ((e: MessageEvent) => void) | null = null
  terminated = false
  postMessage(message: unknown, transfer?: Transferable[]) {
    this.sent.push({ message: message as Record<string, unknown>, transfer })
  }
  terminate() {
    this.terminated = true
  }
  say(data: Record<string, unknown>) {
    this.onmessage?.({ data } as MessageEvent)
  }
}

function setup(options = {}) {
  const worker = new FakeWorker()
  let time = 10_000
  const detector = new WorkerWakeDetector(() => worker, { now: () => time, ...options })
  let wakes = 0
  detector.onWake = () => wakes++
  return { worker, detector, wakes: () => wakes, advance: (ms: number) => (time += ms) }
}

describe('WorkerWakeDetector', () => {
  it('loads the three models in the worker and resolves when it is ready', async () => {
    const { worker, detector } = setup()
    const loaded = detector.load()
    expect(worker.sent[0].message).toMatchObject({ type: 'load', models: { wake: '/models/hey_jarvis_v0.1.onnx' } })
    worker.say({ type: 'ready' })
    await loaded
    expect(await detector.load()).toBeUndefined() // loading twice does not start a second worker
  })

  it('rejects, with the worker\'s words, when the models cannot be loaded', async () => {
    const { worker, detector } = setup()
    const loaded = detector.load()
    worker.say({ type: 'error', message: '/models/x.onnx could not be loaded (404)' })
    await expect(loaded).rejects.toThrow('404')
  })

  it('passes microphone frames on as copies, handing over their memory', () => {
    const { worker, detector } = setup()
    void detector.load()
    const frame = new Int16Array([1, 2, 3])
    detector.push(frame)
    const sent = worker.sent[1]
    expect(sent.message.type).toBe('frame')
    expect(Array.from(sent.message.pcm as Int16Array)).toEqual([1, 2, 3])
    expect(sent.message.pcm).not.toBe(frame)
    expect(sent.transfer).toHaveLength(1)
    expect(frame.length).toBe(3) // the microphone's own buffer is untouched
  })

  it('wakes once the score has stayed high for two steps', () => {
    const { worker, detector, wakes } = setup()
    void detector.load()
    worker.say({ type: 'scores', scores: [0.1, 0.9] })
    expect(wakes()).toBe(0)
    worker.say({ type: 'scores', scores: [0.95] })
    expect(wakes()).toBe(1)
  })

  it('ignores a single spike and scores below the threshold', () => {
    const { worker, detector, wakes } = setup()
    void detector.load()
    worker.say({ type: 'scores', scores: [0.9, 0.2, 0.9, 0.3, 0.49, 0.49] })
    expect(wakes()).toBe(0)
  })

  it('wakes only once for one utterance, and again only after the score has dropped and the cooldown is over', () => {
    const { worker, detector, wakes, advance } = setup({ cooldownMs: 1500 })
    void detector.load()
    worker.say({ type: 'scores', scores: [0.9, 0.99, 0.99, 0.98, 0.97] })
    expect(wakes()).toBe(1)
    worker.say({ type: 'scores', scores: [0.1, 0.9, 0.9] }) // dropped, but too soon
    expect(wakes()).toBe(1)
    advance(2000)
    worker.say({ type: 'scores', scores: [0.1, 0.9, 0.9] })
    expect(wakes()).toBe(2)
  })

  it('uses a threshold that can be changed', () => {
    const { worker, detector, wakes } = setup()
    void detector.load()
    detector.setThreshold(0.3)
    worker.say({ type: 'scores', scores: [0.35, 0.4] })
    expect(wakes()).toBe(1)
  })

  it('stops the worker when it is disposed', () => {
    const { worker, detector } = setup()
    void detector.load()
    detector.dispose()
    expect(worker.terminated).toBe(true)
    expect(detector.onWake).toBeNull()
  })

  it('turns the sensitivity setting into a threshold, normal when it is not known', () => {
    expect(wakeThreshold('low')).toBe(0.7)
    expect(wakeThreshold('normal')).toBe(0.5)
    expect(wakeThreshold('high')).toBe(0.3)
    expect(wakeThreshold(undefined)).toBe(0.5)
    expect(wakeThreshold('what')).toBe(0.5)
  })
})
