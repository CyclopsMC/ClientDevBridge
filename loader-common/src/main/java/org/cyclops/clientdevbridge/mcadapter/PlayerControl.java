package org.cyclops.clientdevbridge.mcadapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * Moving and inspecting the player.
 *
 * Teleporting goes through the integrated server's {@code /tp}, so the server's authoritative
 * position moves with the client's; setting the client position alone would be rubber-banded back.
 *
 * @author rubensworks
 */
public class PlayerControl {

    /**
     * A standing player's eye height. Only used to place the player so that their <em>eyes</em>
     * end up where an aim wants them, which a teleport cannot express directly because it moves
     * the feet.
     */
    public static final double EYE_HEIGHT = 1.62d;

    public static void look(float yaw, float pitch) {
        LocalPlayer player = ClientState.requirePlayer();
        player.setYRot(yaw);
        player.setXRot(Math.max(-90f, Math.min(90f, pitch)));
        player.yRotO = yaw;
        player.xRotO = player.getXRot();
        player.setYHeadRot(yaw);
    }

    public static void lookAt(Vec3 target) {
        LocalPlayer player = ClientState.requirePlayer();
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0d);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        look(yaw, pitch);
    }

    public static void teleport(double x, double y, double z, Float yaw, Float pitch) {
        String rotation = yaw == null && pitch == null
                ? ""
                : String.format(" %.2f %.2f", yaw == null ? 0f : yaw, pitch == null ? 0f : pitch);
        CommandRunner.run(String.format("tp @s %.4f %.4f %.4f%s", x, y, z, rotation));
        if (yaw != null || pitch != null) {
            look(yaw == null ? ClientState.requirePlayer().getYRot() : yaw,
                    pitch == null ? ClientState.requirePlayer().getXRot() : pitch);
        }
    }

    /**
     * Whether the client-side player has actually arrived within half a block of a target.
     *
     * Half a block is deliberately loose: the server snaps the player onto the ground, so an exact
     * comparison would never be true for a y that is not already resting on a surface.
     */
    public static boolean isAt(double x, double y, double z) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        return Math.abs(player.getX() - x) < 0.5d
                && Math.abs(player.getY() - y) < 1.5d
                && Math.abs(player.getZ() - z) < 0.5d;
    }

    /**
     * Whether the player has arrived <em>and stopped moving</em>.
     *
     * {@link #isAt} answers true the moment a teleport lands, which for a target in the air is
     * while the player is still falling: the reply then describes a position they hold for one more
     * tick, and every screenshot after it is of somewhere else. This is the condition a caller who
     * wants a stable camera is actually waiting for.
     *
     * Deliberately not folded into {@code isAt}. {@link Interaction#approach} waits on that one, and
     * {@link Aim#standingPosition} puts the player in mid-air on purpose to look down at a block's
     * top face -- requiring solid ground there would hang every downward interaction until the
     * timeout.
     */
    public static boolean isSettledAt(double x, double y, double z) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.onGround() && isAt(x, y, z);
    }

    /** Whether the player is falling, for a message that has to explain a position going stale. */
    public static boolean isFalling() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && !player.onGround();
    }

    public static void selectHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) {
            throw RpcException.invalidParams("Parameter 'slot' must be a hotbar slot 0-8, but was " + slot);
        }
        ClientState.requirePlayer().getInventory().selected = slot;
    }

    public static JsonObject inventory() {
        LocalPlayer player = ClientState.requirePlayer();
        JsonArray slots = new JsonArray();
        for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
            ItemStack stack = player.getInventory().getItem(index);
            slots.add(describeStack(index, stack));
        }
        JsonObject result = Json.object();
        result.add("slots", slots);
        result.addProperty("selected", player.getInventory().selected);
        result.add("carried", describeStack(-1, player.containerMenu.getCarried()));
        return result;
    }

    /**
     * A compact, stable description of one item stack. Empty stacks are reported as empty rather
     * than omitted, so slot indices always line up with what is on screen.
     */
    public static JsonObject describeStack(int index, ItemStack stack) {
        JsonObject object = Json.object();
        if (index >= 0) {
            object.addProperty("index", index);
        }
        if (stack.isEmpty()) {
            object.addProperty("item", (String) null);
            object.addProperty("count", 0);
            return object;
        }
        object.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString());
        object.addProperty("count", stack.getCount());
        object.addProperty("name", stack.getHoverName().getString());
        if (!stack.getComponents().isEmpty()) {
            object.addProperty("components", stack.getComponents().toString());
        }
        return object;
    }

    public static Minecraft minecraft() {
        return Minecraft.getInstance();
    }

}
