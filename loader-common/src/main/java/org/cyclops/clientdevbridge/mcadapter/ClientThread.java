package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
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

}
