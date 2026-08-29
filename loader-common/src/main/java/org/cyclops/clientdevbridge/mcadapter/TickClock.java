package org.cyclops.clientdevbridge.mcadapter;

import org.cyclops.clientdevbridge.protocol.RpcErrorCodes;
import org.cyclops.clientdevbridge.protocol.RpcException;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Counts client ticks and completes futures from the client tick, so that "wait N ticks" and
 * "wait until X" never block the render thread.
 *
 * @author rubensworks
 */
public class TickClock {

    private final AtomicLong tick = new AtomicLong();
    private final Queue<Waiter> waiters = new ConcurrentLinkedQueue<>();

    /**
     * Called once per client tick, on the client thread.
     */
    public void onClientTick() {
        long now = this.tick.incrementAndGet();
        this.waiters.removeIf(waiter -> waiter.tryComplete(now));
    }

    public long getTick() {
        return this.tick.get();
    }

    /**
     * Completes after the given number of client ticks have elapsed.
     */
    public CompletableFuture<Long> afterTicks(int ticks) {
        if (ticks <= 0) {
            return CompletableFuture.completedFuture(this.tick.get());
        }
        long deadline = this.tick.get() + ticks;
        return await(now -> now >= deadline, -1, null);
    }

    /**
     * Completes with true as soon as the condition holds, or with false at the timeout.
     *
     * The condition is evaluated on the client thread, so it may read game state directly.
     *
     * @param condition   evaluated once per tick on the client thread
     * @param timeoutTicks how many ticks to wait before giving up, or a negative value to wait forever
     * @param timeoutMessage when non-null, the wait fails with this message instead of completing false
     */
    public CompletableFuture<Boolean> awaitCondition(BooleanSupplier condition, long timeoutTicks,
                                                     String timeoutMessage) {
        return await(now -> condition.getAsBoolean(), timeoutTicks, timeoutMessage)
                .thenApply(met -> met != null);
    }

    private CompletableFuture<Long> await(java.util.function.LongPredicate predicate, long timeoutTicks,
                                          String timeoutMessage) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        long deadline = timeoutTicks < 0 ? Long.MAX_VALUE : this.tick.get() + timeoutTicks;
        Waiter waiter = new Waiter(predicate, deadline, timeoutMessage, future);
        this.waiters.add(waiter);
        // Evaluate immediately as well, so an already-true condition does not cost a tick.
        if (waiter.tryComplete(this.tick.get())) {
            this.waiters.remove(waiter);
        }
        return future;
    }

    /**
     * Fails every outstanding waiter, so that a client shutting down does not leave the CLI hanging.
     */
    public void abortAll(String reason) {
        Waiter waiter;
        while ((waiter = this.waiters.poll()) != null) {
            waiter.future.completeExceptionally(RpcException.illegalState(reason));
        }
    }

    private record Waiter(java.util.function.LongPredicate predicate, long deadline, String timeoutMessage,
                          CompletableFuture<Long> future) {

        boolean tryComplete(long now) {
            if (this.future.isDone()) {
                return true;
            }
            boolean met;
            try {
                met = this.predicate.test(now);
            } catch (Throwable e) {
                this.future.completeExceptionally(e);
                return true;
            }
            if (met) {
                this.future.complete(now);
                return true;
            }
            if (now >= this.deadline) {
                if (this.timeoutMessage != null) {
                    this.future.completeExceptionally(new RpcException(RpcErrorCodes.TIMEOUT, this.timeoutMessage));
                } else {
                    this.future.complete(null);
                }
                return true;
            }
            return false;
        }

    }

}
