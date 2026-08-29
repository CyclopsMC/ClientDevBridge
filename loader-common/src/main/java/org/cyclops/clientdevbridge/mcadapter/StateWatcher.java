package org.cyclops.clientdevbridge.mcadapter;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.cyclops.clientdevbridge.protocol.Json;

import javax.annotation.Nullable;
import java.util.function.BiConsumer;

/**
 * Turns state changes into protocol notifications.
 *
 * Both loaders have events for screen opening and for joining a world, but they differ in shape and
 * in exactly when they fire. Deriving the transitions from a single per-tick comparison instead
 * keeps the loader surface down to one callback, and gives both loaders identical semantics — which
 * is what the protocol promises.
 *
 * @author rubensworks
 */
public class StateWatcher {

    private final BiConsumer<String, JsonObject> notify;
    private final boolean suppressToasts;

    @Nullable
    private String lastScreenClass;
    private boolean lastInWorld;
    private boolean primed;

    public StateWatcher(BiConsumer<String, JsonObject> notify, boolean suppressToasts) {
        this.notify = notify;
        this.suppressToasts = suppressToasts;
    }

    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();

        // Toasts fade in and out over several seconds, so anything on screen while one is showing
        // is not reproducible. Clearing them every tick keeps them from ever being drawn.
        if (this.suppressToasts) {
            minecraft.getToasts().clear();
        }
        String screenClass = minecraft.screen == null ? null : minecraft.screen.getClass().getName();
        boolean inWorld = minecraft.level != null && minecraft.player != null;

        if (!this.primed) {
            this.primed = true;
            this.lastScreenClass = screenClass;
            this.lastInWorld = inWorld;
            return;
        }

        if (!java.util.Objects.equals(screenClass, this.lastScreenClass)) {
            JsonObject params = Json.object();
            params.addProperty("screenClass", screenClass);
            params.addProperty("previousScreenClass", this.lastScreenClass);
            if (minecraft.screen != null) {
                params.addProperty("title", minecraft.screen.getTitle().getString());
            }
            this.lastScreenClass = screenClass;
            this.notify.accept("screen.changed", params);
        }

        if (inWorld != this.lastInWorld) {
            this.lastInWorld = inWorld;
            JsonObject params = Json.object();
            if (inWorld && minecraft.level != null) {
                params.addProperty("dimension", minecraft.level.dimension().location().toString());
            }
            this.notify.accept(inWorld ? "world.joined" : "world.left", params);
            if (!inWorld) {
                // A held movement key must not survive into the next scenario.
                InputControl.releaseAll();
            }
        }
    }

    public boolean isInWorld() {
        return this.lastInWorld;
    }

}
