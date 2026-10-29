import { ApiError } from './api'
import { assistantMessage, canSend, describeError, errorMessage, userMessage, voiceStatusText } from './assistantModel'

describe('assistantModel', () => {
  it('turns a reply into a bubble that keeps what was done and what failed', () => {
    const message = assistantMessage(3, { reply: 'Done.', actions: ['Desk lamp: on'], failed: ['Bar: Unavailable'], conversationId: 'c1' })
    expect(message).toMatchObject({ id: 3, role: 'assistant', text: 'Done.', actions: ['Desk lamp: on'], failed: ['Bar: Unavailable'], error: false })
  })

  it('builds user and error bubbles', () => {
    expect(userMessage(1, 'hi')).toMatchObject({ role: 'user', text: 'hi', error: false })
    expect(errorMessage(2, 'oops')).toMatchObject({ role: 'assistant', text: 'oops', error: true })
  })

  it('explains failures in plain words', () => {
    expect(describeError(new ApiError(429, 'x'))).toMatch(/too many/i)
    expect(describeError(new ApiError(401, 'x'))).toMatch(/signed out/i)
    expect(describeError(new ApiError(503, 'The assistant is not set up yet'))).toBe('The assistant is not set up yet')
    expect(describeError(new ApiError(502, ''))).toBe('The assistant could not answer.')
    expect(describeError(new TypeError('network'))).toBe("Can't reach the server.")
  })

  it('only sends real, short enough text', () => {
    expect(canSend('   ')).toBe(false)
    expect(canSend('lamp on')).toBe(true)
    expect(canSend('x'.repeat(501))).toBe(false)
  })

  it('marks what was said aloud', () => {
    expect(userMessage(1, 'hi', true).spoken).toBe(true)
    expect(userMessage(2, 'hi').spoken).toBe(false)
  })

  it('describes the voice state for the status line', () => {
    expect(voiceStatusText('idle', false)).toBe('')
    expect(voiceStatusText('listening', true)).toMatch(/just speak/)
    expect(voiceStatusText('speaking', false)).toMatch(/tap to stop/)
    expect(voiceStatusText('speaking', true)).toMatch(/interrupt/)
    expect(voiceStatusText('thinking', false)).toBe('Thinking…')
  })
})
