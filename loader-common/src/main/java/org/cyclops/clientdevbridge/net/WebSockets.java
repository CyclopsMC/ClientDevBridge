package org.cyclops.clientdevbridge.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal RFC 6455 server-side WebSocket codec.
 *
 * Minecraft ships Netty, but not {@code netty-codec-http}, so {@code io.netty.handler.codec.http.websocketx}
 * is not on the classpath. Rather than adding a dependency that would have to be jar-in-jar'd into a
 * dev-only mod, the handful of framing rules we actually need are implemented here. This class is
 * deliberately version-independent: it must never need to change when Minecraft does.
 *
 * Only what a localhost JSON-RPC transport needs is supported: the opening handshake, text frames
 * (including continuations), ping/pong, and close.
 *
 * @author rubensworks
 */
public final class WebSockets {

    /** The magic value from RFC 6455 §1.3. */
    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    /** Refuse to buffer more than this much in a single message, so a stray client cannot exhaust the heap. */
    private static final long MAX_MESSAGE_BYTES = 32L * 1024 * 1024;

    private WebSockets() {
    }

    /**
     * Performs the server side of the opening handshake.
     *
     * @param in  the socket input, positioned at the start of the HTTP request
     * @param out the socket output
     * @throws IOException when the request is not a valid WebSocket upgrade, after replying with a 400
     */
    public static void acceptHandshake(InputStream in, OutputStream out) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || !requestLine.toUpperCase(Locale.ROOT).startsWith("GET ")) {
            refuse(out, "400 Bad Request", "ClientDevBridge speaks WebSocket only.");
            throw new IOException("Not a GET request: " + requestLine);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }

        String key = headers.get("sec-websocket-key");
        String upgrade = headers.get("upgrade");
        if (key == null || upgrade == null || !upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
            refuse(out, "400 Bad Request", "Missing WebSocket upgrade headers.");
            throw new IOException("Not a WebSocket upgrade request");
        }

        String accept = acceptKey(key);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * Computes the {@code Sec-WebSocket-Accept} value for a client's {@code Sec-WebSocket-Key}.
     */
    public static String acceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey + ACCEPT_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by RFC 6455 but unavailable", e);
        }
    }

    private static void refuse(OutputStream out, String status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int length = builder.length();
                if (length > 0 && builder.charAt(length - 1) == '\r') {
                    builder.setLength(length - 1);
                }
                return builder.toString();
            }
            builder.append((char) c);
            if (builder.length() > 8192) {
                throw new IOException("HTTP header line too long");
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /**
     * One decoded WebSocket message.
     *
     * @param opcode  the opcode of the message's first frame
     * @param payload the concatenated, unmasked payload
     */
    public record Message(int opcode, byte[] payload) {
        public String text() {
            return new String(this.payload, StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads one whole message, transparently joining continuation frames and
     * answering pings. Returns {@code null} on a close frame or end of stream.
     */
    public static Message readMessage(InputStream in, OutputStream out) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int messageOpcode = -1;

        while (true) {
            int b0 = in.read();
            if (b0 == -1) {
                return null;
            }
            int b1 = readByte(in);

            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7F;

            if (length == 126) {
                length = ((long) readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte(in);
                }
            }
            if (length < 0 || length > MAX_MESSAGE_BYTES) {
                throw new IOException("WebSocket frame too large: " + length);
            }

            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }

            byte[] payload = new byte[(int) length];
            readFully(in, payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i & 3]);
                }
            }

            // Control frames may be interleaved between the fragments of a message.
            if (opcode == OPCODE_CLOSE) {
                return null;
            } else if (opcode == OPCODE_PING) {
                writeMessage(out, OPCODE_PONG, payload);
                continue;
            } else if (opcode == OPCODE_PONG) {
                continue;
            }

            if (opcode != OPCODE_CONTINUATION) {
                messageOpcode = opcode;
            }
            buffer.write(payload);
            if (buffer.size() > MAX_MESSAGE_BYTES) {
                throw new IOException("WebSocket message too large");
            }

            if (fin) {
                return new Message(messageOpcode < 0 ? OPCODE_TEXT : messageOpcode, buffer.toByteArray());
            }
        }
    }

    /**
     * Writes one unfragmented, unmasked message. Server-to-client frames are never masked.
     *
     * Callers must serialise their writes on a single connection; {@link BridgeConnection} does.
     */
    public static void writeMessage(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        int length = payload.length;
        if (length < 126) {
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(126);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (((long) length >>> shift) & 0xFF));
            }
        }
        out.write(payload);
        out.flush();
    }

    public static void writeText(OutputStream out, String text) throws IOException {
        writeMessage(out, OPCODE_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("Unexpected end of WebSocket stream");
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = in.read(target, offset, target.length - offset);
            if (read == -1) {
                throw new EOFException("Unexpected end of WebSocket stream");
            }
            offset += read;
        }
    }

}
