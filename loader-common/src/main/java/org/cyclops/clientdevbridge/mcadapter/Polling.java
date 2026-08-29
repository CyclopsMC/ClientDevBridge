package org.cyclops.clientdevbridge.mcadapter;

import org.cyclops.clientdevbridge.protocol.RpcErrorCodes;
import org.cyclops.clientdevbridge.protocol.RpcException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Waits for a condition on a timer thread rather than on the client tick.
 *
 * {@link TickClock} is the right tool for "wait N ticks", but the wrong one for "wait until the
 * client has finished loading a world": that load blocks the client thread and pumps its own
 * render loop, and whether client ticks keep being delivered through it differs between loaders.
 * Waiting on a wall-clock timer instead makes the wait independent of that entirely — which is
 * what a caller asking "is the world ready yet?" actually means.
 *
 * The conditions polled here read plain fields on {@code Minecraft} ({@code level}, {@code player},
 * {@code screen}). Reading those off-thread is a benign race: the worst case is noticing a
 * transition one poll interval late.
 *
 * @author rubensworks
 */
public class Polling {

    private static final long INTERVAL_MS = 100;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ClientDevBridge-poll");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Completes with true as soon as the condition holds, or fails at the timeout.
     *
     * @param condition      evaluated off the client thread, every {@value #INTERVAL_MS} ms
     * @param timeoutMs      how long to wait before giving up
     * @param timeoutMessage the failure message, which should say what to try next
     */
    public static CompletableFuture<Boolean> await(BooleanSupplier condition, long timeoutMs, String timeoutMessage) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + timeoutMs;

        ScheduledFuture<?> polling = SCHEDULER.scheduleAtFixedRate(() -> {
            if (future.isDone()) {
                return;
            }
            try {
                if (condition.getAsBoolean()) {
                    future.complete(true);
                } else if (System.currentTimeMillis() >= deadline) {
                    future.completeExceptionally(new RpcException(RpcErrorCodes.TIMEOUT, timeoutMessage));
                }
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);

        future.whenComplete((value, throwable) -> polling.cancel(false));
        return future;
    }

}
