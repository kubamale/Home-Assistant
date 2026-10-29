/** The connection to the server's voice endpoint. The real one is a WebSocket; tests use a fake. */
export interface Transport {
  sendJson(message: Record<string, unknown>): void
  sendBinary(data: Int16Array): void
  close(): void
}

export interface TransportHandlers {
  onJson(message: Record<string, unknown>): void
  onBinary(data: ArrayBuffer): void
  onClose(): void
}

/** Opens the WebSocket to this site's voice endpoint (the cookie signs it in). */
export function openTransport(handlers: TransportHandlers): Promise<Transport> {
  return new Promise((resolve, reject) => {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${scheme}://${window.location.host}/api/voice/session`)
    socket.binaryType = 'arraybuffer'
    let opened = false
    socket.onopen = () => {
      opened = true
      resolve({
        sendJson: (message) => socket.send(JSON.stringify(message)),
        sendBinary: (data) => socket.send(data.slice().buffer as ArrayBuffer),
        close: () => socket.close(),
      })
    }
    socket.onmessage = (e) => {
      if (typeof e.data === 'string') handlers.onJson(JSON.parse(e.data) as Record<string, unknown>)
      else handlers.onBinary(e.data as ArrayBuffer)
    }
    socket.onerror = () => {
      if (!opened) reject(new Error('The voice connection could not be opened'))
    }
    socket.onclose = () => {
      if (opened) handlers.onClose()
      else reject(new Error('The voice connection could not be opened'))
    }
  })
}
