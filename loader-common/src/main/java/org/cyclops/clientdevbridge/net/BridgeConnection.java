package org.cyclops.clientdevbridge.net;

import org.cyclops.clientdevbridge.ClientDevBridge;
import org.cyclops.clientdevbridge.protocol.Dispatcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connected CLI invocation.
 *
 * Requests are read on this thread and handed to the {@link Dispatcher}; the handler's future is
 * responded to whenever it completes, so a slow handler (one waiting on ticks, say) never blocks
 * the next request or an outgoing notification.
 *
 * @author rubensworks
 */
public class BridgeConnection {

    private final Socket socket;
    private final Dispatcher dispatcher;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();

    private OutputStream out;

    public BridgeConnection(Socket socket, Dispatcher dispatcher, Runnable onClose) {
        this.socket = socket;
        this.dispatcher = dispatcher;
        this.onClose = onClose;
    }

    public void run(String helloMessage) {
        try (Socket socket = this.socket) {
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            this.out = new BufferedOutputStream(socket.getOutputStream());

            WebSockets.acceptHandshake(in, this.out);
            send(helloMessage);

            while (!this.closed.get()) {
                WebSockets.Message message = WebSockets.readMessage(in, this.out);
                if (message == null) {
                    break;
                }
                if (message.opcode() != WebSockets.OPCODE_TEXT) {
                    // The protocol is JSON text in both directions; binary payloads are base64 fields.
                    continue;
                }
                handle(message.text());
            }
        } catch (IOException e) {
            if (!this.closed.get()) {
                ClientDevBridge.LOGGER.debug("Bridge connection ended: {}", e.toString());
            }
        } finally {
            this.closed.set(true);
            this.onClose.run();
        }
    }

    private void handle(String text) {
        this.dispatcher.dispatch(text).whenComplete((response, throwable) -> {
            if (throwable != null) {
                ClientDevBridge.LOGGER.error("Dispatch failed", throwable);
                send(Dispatcher.errorResponse(null, org.cyclops.clientdevbridge.protocol.RpcErrorCodes.INTERNAL_ERROR,
                        String.valueOf(throwable.getMessage()), null));
            } else if (response != null) {
                send(response);
            }
        });
    }

    /**
     * Sends one text message. Safe to call from any thread; writes are serialised.
     */
    public void send(String text) {
        if (this.closed.get() || this.out == null) {
            return;
        }
        synchronized (this.writeLock) {
            try {
                WebSockets.writeText(this.out, text);
            } catch (IOException e) {
                ClientDevBridge.LOGGER.debug("Failed to write to bridge connection: {}", e.toString());
                close();
            }
        }
    }

    public boolean isOpen() {
        return !this.closed.get();
    }

    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            try {
                this.socket.close();
            } catch (IOException ignored) {
                // Closing a socket that is already gone is not interesting.
            }
        }
    }

}
