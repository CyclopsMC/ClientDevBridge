package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.Minecraft;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * The threading rule, in one place.
 *
 * Every handler that touches game state hops onto the client (render) thread through here and
 * the WebSocket thread awaits the resulting future. Handlers never block the render thread.
 *
 * @author rubensworks
 */
public class ClientThread {

    /**
     * Runs the supplier on the client thread and completes with its value.
     */
    public static <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        return Minecraft.getInstance().submit(supplier);
    }

    /**
     * Runs the action on the client thread and completes with null.
     */
    public static CompletableFuture<Object> run(Runnable action) {
        return Minecraft.getInstance().submit(() -> {
            action.run();
            return (Object) null;
        });
    }

    /**
     * Runs the supplier on the client thread, one rendered frame later than {@link #submit}.
     *
     * {@code Minecraft#runTick} drains its scheduled tasks <em>before</em> rendering, so a task
     * submitted now observes the framebuffer of the previous frame. Hopping once more means the
     * frame in the buffer is one that was rendered after the request arrived, which is what makes
     * a screenshot reflect the input that preceded it.
     */
    public static <T> CompletableFuture<T> submitAfterFrame(Supplier<T> supplier) {
        return Minecraft.getInstance()
                .submit(() -> null)
                .thenCompose(ignored -> Minecraft.getInstance().submit(supplier));
    }

    public static boolean isOnClientThread() {
        return Minecraft.getInstance().isSameThread();
    }

    /**
     * Actions waiting to be run from the client tick rather than from the task queue.
     */
    private static final Queue<Runnable> TICK_ACTIONS = new ConcurrentLinkedQueue<>();
    private static boolean drainingTickActions;

    /**
     * Runs an action from inside {@code Minecraft#tick} instead of from the scheduled-task queue.
     *
     * This exists for the handful of vanilla methods that block the client thread and pump
     * {@code runTick} while they wait — loading a world, and disconnecting from one.
     * {@code Minecraft} is a {@link net.minecraft.util.thread.ReentrantBlockableEventLoop}, which
     * deliberately refuses to run scheduled tasks while it is already inside one. So a blocking
     * load started from {@link #submit} waits forever for callbacks that are themselves scheduled
     * tasks — most visibly the resource-reload overlay's completion, which is what clears
     * {@code Minecraft#overlay} and lets {@code doWorldLoad} finish.
     *
     * Running from the tick hook means the reentrant depth is zero, exactly as it is when the
     * player clicks "Create New World", so the nested {@code runTick} calls drain tasks normally.
     */
    public static CompletableFuture<Object> runOnTick(Runnable action) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        TICK_ACTIONS.add(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Drains the queued tick actions. Called from the client tick hook, before the tick clock
     * advances, and guarded against the re-entrant ticks a blocking load produces.
     */
    public static void drainTickActions() {
        if (drainingTickActions) {
            return;
        }
        drainingTickActions = true;
        try {
            Runnable action;
            while ((action = TICK_ACTIONS.poll()) != null) {
                action.run();
            }
        } finally {
            drainingTickActions = false;
        }
    }

}
