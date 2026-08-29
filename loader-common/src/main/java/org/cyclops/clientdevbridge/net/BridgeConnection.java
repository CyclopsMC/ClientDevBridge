package org.cyclops.clientdevbridge.net;

import org.cyclops.clientdevbridge.ClientDevBridge;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.RpcErrorCodes;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One connected CLI invocation.
 *
 * Requests are read on this thread and handed to the {@link Dispatcher}; the handler's future is
 * responded to whenever it completes, so a slow handler (one waiting on ticks, say) never blocks
 * the next request or an outgoing notification.
 *
 * Writes go through a bounded outbox drained by a dedicated thread. That indirection is not
 * incidental: {@code log.line} notifications are produced on whichever thread logged, very often
 * the render thread, and a client that stops reading would otherwise apply TCP backpressure
 * straight into the game loop and freeze the client.
 *
 * @author rubensworks
 */
public class BridgeConnection {

    /** How many outgoing messages may be in flight before the oldest notifications are dropped. */
    private static final int OUTBOX_CAPACITY = 512;

    /** Queued instead of a message when the connection is closing, to wake the writer thread. */
    private static final String POISON = "<clientdevbridge:close>";

    private final Socket socket;
    private final Dispatcher dispatcher;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(OUTBOX_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    private OutputStream out;
    private Thread writerThread;

    public BridgeConnection(Socket socket, Dispatcher dispatcher, Runnable onClose) {
        this.socket = socket;
        this.dispatcher = dispatcher;
        this.onClose = onClose;
    }

    public void run(String helloMessage) {
        try (Socket connectedSocket = this.socket) {
            connectedSocket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(connectedSocket.getInputStream());
            this.out = new BufferedOutputStream(connectedSocket.getOutputStream());

            WebSockets.acceptHandshake(in, this.out);

            this.writerThread = new Thread(this::writeLoop, Thread.currentThread().getName() + "-writer");
            this.writerThread.setDaemon(true);
            this.writerThread.start();

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
            close();
            this.onClose.run();
        }
    }

    private void handle(String text) {
        this.dispatcher.dispatch(text).whenComplete((response, throwable) -> {
            if (throwable != null) {
                ClientDevBridge.LOGGER.error("Dispatch failed", throwable);
                send(Dispatcher.errorResponse(null, RpcErrorCodes.INTERNAL_ERROR,
                        String.valueOf(throwable.getMessage()), null));
            } else if (response != null) {
                send(response);
            }
        });
    }

    /**
     * Queues one text message. Never blocks, and is safe to call from any thread — including the
     * render thread, which is what a {@code log.line} notification does on every logged line.
     *
     * When the outbox is full the oldest queued message is dropped rather than the caller being
     * made to wait: a dropped notification is a far better outcome than a stalled game loop.
     */
    public void send(String text) {
        if (this.closed.get()) {
            return;
        }
        while (!this.outbox.offer(text)) {
            if (this.outbox.poll() != null) {
                this.dropped.incrementAndGet();
            }
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                String message = this.outbox.take();
                if (POISON.equals(message) || this.closed.get()) {
                    return;
                }
                WebSockets.writeText(this.out, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!this.closed.get()) {
                ClientDevBridge.LOGGER.debug("Failed to write to bridge connection: {}", e.toString());
            }
        } finally {
            closeSocketOnly();
        }
    }

    /**
     * How many notifications were dropped because the client was not reading fast enough.
     */
    public long getDroppedCount() {
        return this.dropped.get();
    }

    public boolean isOpen() {
        return !this.closed.get();
    }

    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.outbox.clear();
            this.outbox.offer(POISON);
            closeSocketOnly();
        }
    }

    private void closeSocketOnly() {
        try {
            this.socket.close();
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not interesting.
        }
    }

}
