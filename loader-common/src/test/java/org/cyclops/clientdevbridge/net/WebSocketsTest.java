package org.cyclops.clientdevbridge.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport is hand-rolled, so its framing is worth testing directly rather than only
 * through a running game.
 *
 * @author rubensworks
 */
class WebSocketsTest {

    @Test
    void computesTheAcceptKeyFromRfc6455() {
        // The worked example from RFC 6455 section 1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSockets.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void completesAHandshakeForAValidUpgrade() throws IOException {
        String request = "GET / HTTP/1.1\r\n"
                + "Host: 127.0.0.1:25599\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSockets.acceptHandshake(new ByteArrayInputStream(request.getBytes(StandardCharsets.ISO_8859_1)), out);

        String response = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols"), response);
        assertTrue(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), response);
    }

    @Test
    void refusesAPlainHttpRequestWithAReadableBody() {
        String request = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () ->
                WebSockets.acceptHandshake(new ByteArrayInputStream(request.getBytes(StandardCharsets.ISO_8859_1)), out));
        String response = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(response.startsWith("HTTP/1.1 400"), response);
        assertTrue(response.contains("WebSocket"), response);
    }

    @Test
    void roundTripsATextMessage() throws IOException {
        String text = "{\"jsonrpc\":\"2.0\"}";
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        WebSockets.writeText(encoded, text);

        // Server-to-client frames are unmasked, so mask the bytes to replay them as a client would.
        byte[] frame = maskAsClient(encoded.toByteArray());
        WebSockets.Message message = WebSockets.readMessage(new ByteArrayInputStream(frame), new ByteArrayOutputStream());
        assertEquals(WebSockets.OPCODE_TEXT, message.opcode());
        assertEquals(text, message.text());
    }

    @Test
    void handlesPayloadsAcrossEveryLengthEncoding() throws IOException {
        for (int length : new int[] { 0, 5, 125, 126, 200, 65535, 65536, 200000 }) {
            String text = "x".repeat(length);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            WebSockets.writeText(encoded, text);
            byte[] frame = maskAsClient(encoded.toByteArray());
            WebSockets.Message message = WebSockets.readMessage(new ByteArrayInputStream(frame),
                    new ByteArrayOutputStream());
            assertEquals(length, message.text().length(), "round trip failed for a " + length + " byte payload");
        }
    }

    @Test
    void joinsContinuationFrames() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        // A non-final text frame, then a final continuation frame.
        writeClientFrame(stream, false, WebSockets.OPCODE_TEXT, "hello ".getBytes(StandardCharsets.UTF_8));
        writeClientFrame(stream, true, WebSockets.OPCODE_CONTINUATION, "world".getBytes(StandardCharsets.UTF_8));

        WebSockets.Message message = WebSockets.readMessage(new ByteArrayInputStream(stream.toByteArray()),
                new ByteArrayOutputStream());
        assertEquals("hello world", message.text());
        assertEquals(WebSockets.OPCODE_TEXT, message.opcode());
    }

    @Test
    void answersAPingAndKeepsReading() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        writeClientFrame(stream, true, WebSockets.OPCODE_PING, new byte[] { 1, 2, 3 });
        writeClientFrame(stream, true, WebSockets.OPCODE_TEXT, "after".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSockets.Message message = WebSockets.readMessage(new ByteArrayInputStream(stream.toByteArray()), out);
        assertEquals("after", message.text());

        byte[] pong = out.toByteArray();
        assertEquals(0x80 | WebSockets.OPCODE_PONG, pong[0] & 0xFF);
        assertArrayEquals(new byte[] { 1, 2, 3 }, new byte[] { pong[2], pong[3], pong[4] });
    }

    @Test
    void returnsNullOnCloseAndOnEndOfStream() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        writeClientFrame(stream, true, WebSockets.OPCODE_CLOSE, new byte[0]);
        assertNull(WebSockets.readMessage(new ByteArrayInputStream(stream.toByteArray()), new ByteArrayOutputStream()));
        assertNull(WebSockets.readMessage(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
    }

    @Test
    void refusesAFrameLargerThanTheCap() {
        // A 64-bit length header claiming 64 MiB, which is past the limit.
        byte[] header = new byte[] {
                (byte) 0x81, (byte) 0xFF,
                0, 0, 0, 0, 0x04, 0, 0, 0,
        };
        assertThrows(IOException.class, () ->
                WebSockets.readMessage(new ByteArrayInputStream(header), new ByteArrayOutputStream()));
    }

    /** Re-frames a server frame as a masked client frame, which is what a real client sends. */
    private static byte[] maskAsClient(byte[] serverFrame) throws IOException {
        int opcode = serverFrame[0] & 0x0F;
        int lengthByte = serverFrame[1] & 0x7F;
        int offset;
        int length;
        if (lengthByte < 126) {
            length = lengthByte;
            offset = 2;
        } else if (lengthByte == 126) {
            length = ((serverFrame[2] & 0xFF) << 8) | (serverFrame[3] & 0xFF);
            offset = 4;
        } else {
            length = 0;
            for (int i = 2; i < 10; i++) {
                length = (length << 8) | (serverFrame[i] & 0xFF);
            }
            offset = 10;
        }
        byte[] payload = new byte[length];
        System.arraycopy(serverFrame, offset, payload, 0, length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeClientFrame(out, true, opcode, payload);
        return out.toByteArray();
    }

    private static void writeClientFrame(ByteArrayOutputStream out, boolean fin, int opcode, byte[] payload)
            throws IOException {
        out.write((fin ? 0x80 : 0x00) | opcode);
        byte[] mask = { 0x12, 0x34, 0x56, 0x78 };
        if (payload.length < 126) {
            out.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (((long) payload.length >>> shift) & 0xFF));
            }
        }
        out.write(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i & 3]);
        }
    }

}
