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
