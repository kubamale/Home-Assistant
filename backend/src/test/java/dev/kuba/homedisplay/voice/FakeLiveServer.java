package dev.kuba.homedisplay.voice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A just-enough WebSocket server standing in for Gemini's Live API: it accepts one connection at a time, hands every
 * message the client sends to a script, and lets the script answer, as binary frames like the real service does.
 */
final class FakeLiveServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    final List<String> received = new CopyOnWriteArrayList<>();
    volatile String keyHeader;
    final CountDownLatch clientClosed = new CountDownLatch(1);
    /** What to do with each message from the client. */
    volatile Consumer<String> script = message -> { };
    private volatile OutputStream out;

    FakeLiveServer() throws IOException {
        server = new ServerSocket(0);
        Thread.ofVirtual().name("fake-live").start(this::serve);
    }

    int port() {
        return server.getLocalPort();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                handshake(socket);
                out = socket.getOutputStream();
                read(socket.getInputStream());
            } catch (IOException e) {
                // the client left, or the server was closed
            }
        }
    }

    private void handshake(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed during the handshake");
            }
            head.append((char) b);
        }
        String key = null;
        for (String line : head.toString().split("\r\n")) {
            String lower = line.toLowerCase();
            if (lower.startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
            } else if (lower.startsWith("x-goog-api-key:")) {
                keyHeader = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        try {
            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.UTF_8)));
            socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void read(InputStream in) throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) {
                break;
            }
            int opcode = first & 0x0f;
            int second = in.read();
            long length = second & 0x7f;
            if (length == 126) {
                length = (in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            byte[] mask = in.readNBytes(4);
            byte[] payload = in.readNBytes((int) length);
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
            if (opcode == 8) {
                break;
            }
            if (opcode == 1) {
                String message = new String(payload, StandardCharsets.UTF_8);
                received.add(message);
                script.accept(message);
            }
        }
        clientClosed.countDown();
    }

    /** Sends a JSON message as one binary frame. */
    void send(String json) {
        send(json, false);
    }

    /** Sends a JSON message as one binary frame, or split over two frames. */
    void send(String json, boolean split) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (!split) {
            frame(2, true, bytes);
            return;
        }
        int half = bytes.length / 2;
        frame(2, false, java.util.Arrays.copyOfRange(bytes, 0, half));
        frame(0, true, java.util.Arrays.copyOfRange(bytes, half, bytes.length));
    }

    /** Hangs up. */
    void drop() throws IOException {
        OutputStream open = out;
        if (open != null) {
            open.close();
        }
    }

    private synchronized void frame(int opcode, boolean fin, byte[] payload) {
        try {
            OutputStream open = out;
            open.write((fin ? 0x80 : 0) | opcode);
            if (payload.length < 126) {
                open.write(payload.length);
            } else {
                open.write(126);
                open.write(payload.length >> 8);
                open.write(payload.length & 0xff);
            }
            open.write(payload);
            open.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the client has sent at least this many messages. */
    void awaitReceived(int count) throws InterruptedException {
        for (int i = 0; i < 100 && received.size() < count; i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
