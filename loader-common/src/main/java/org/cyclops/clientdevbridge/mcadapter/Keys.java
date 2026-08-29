package org.cyclops.clientdevbridge.mcadapter;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the protocol's key names into GLFW key codes and, where one exists, the {@link KeyMapping}
 * bound to them.
 *
 * The protocol accepts either a GLFW constant name ({@code GLFW_KEY_E}, or just {@code E}) or a raw
 * integer, so a caller never has to know Minecraft's own naming.
 *
 * @author rubensworks
 */
public class Keys {

    /**
     * The keys worth naming explicitly: everything that is not a plain letter, digit or function key.
     */
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("SPACE", 32), Map.entry("APOSTROPHE", 39), Map.entry("COMMA", 44),
            Map.entry("MINUS", 45), Map.entry("PERIOD", 46), Map.entry("SLASH", 47),
            Map.entry("SEMICOLON", 59), Map.entry("EQUAL", 61),
            Map.entry("LEFT_BRACKET", 91), Map.entry("BACKSLASH", 92), Map.entry("RIGHT_BRACKET", 93),
            Map.entry("GRAVE_ACCENT", 96),
            Map.entry("ESCAPE", 256), Map.entry("ENTER", 257), Map.entry("TAB", 258),
            Map.entry("BACKSPACE", 259), Map.entry("INSERT", 260), Map.entry("DELETE", 261),
            Map.entry("RIGHT", 262), Map.entry("LEFT", 263), Map.entry("DOWN", 264), Map.entry("UP", 265),
            Map.entry("PAGE_UP", 266), Map.entry("PAGE_DOWN", 267), Map.entry("HOME", 268), Map.entry("END", 269),
            Map.entry("CAPS_LOCK", 280), Map.entry("SCROLL_LOCK", 281), Map.entry("NUM_LOCK", 282),
            Map.entry("PRINT_SCREEN", 283), Map.entry("PAUSE", 284),
            Map.entry("LEFT_SHIFT", 340), Map.entry("LEFT_CONTROL", 341), Map.entry("LEFT_ALT", 342),
            Map.entry("LEFT_SUPER", 343), Map.entry("RIGHT_SHIFT", 344), Map.entry("RIGHT_CONTROL", 345),
            Map.entry("RIGHT_ALT", 346), Map.entry("RIGHT_SUPER", 347), Map.entry("MENU", 348));

    /** GLFW modifier bits, as accepted in the protocol's `modifiers` field. */
    public static final int MOD_SHIFT = 0x0001;
    public static final int MOD_CONTROL = 0x0002;
    public static final int MOD_ALT = 0x0004;

    /**
     * Resolves a key name or code to a GLFW key code.
     */
    public static int toKeyCode(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException ignored) {
            // Not a raw code; fall through to the name forms below.
        }
        if (name.startsWith("GLFW_KEY_")) {
            name = name.substring("GLFW_KEY_".length());
        }
        Integer named = NAMED.get(name);
        if (named != null) {
            return named;
        }
        if (name.length() == 1) {
            char character = name.charAt(0);
            if (character >= 'A' && character <= 'Z') {
                return character; // GLFW letter codes are the ASCII uppercase values.
            }
            if (character >= '0' && character <= '9') {
                return character;
            }
        }
        if (name.matches("F([1-9]|1\\d|2[0-5])")) {
            return 289 + Integer.parseInt(name.substring(1)); // GLFW_KEY_F1 == 290
        }
        throw RpcException.invalidParams("Unknown key '" + raw + "'. Use a GLFW name such as 'GLFW_KEY_E', "
                + "a single letter or digit, 'F3', a named key like 'ESCAPE', or a raw integer key code.");
    }

    public static InputConstants.Key toInputKey(int keyCode) {
        return InputConstants.Type.KEYSYM.getOrCreate(keyCode);
    }

    public static InputConstants.Key toMouseKey(int button) {
        return InputConstants.Type.MOUSE.getOrCreate(button);
    }

    /**
     * The vanilla key mapping bound to a key code, when there is one.
     *
     * In-world input has to go through the mapping rather than through a screen, because with no
     * screen open there is no {@code GuiEventListener} to deliver the event to.
     */
    @Nullable
    public static KeyMapping findMapping(int keyCode) {
        Options options = Minecraft.getInstance().options;
        InputConstants.Key key = toInputKey(keyCode);
        for (KeyMapping mapping : options.keyMappings) {
            if (!mapping.isUnbound() && mapping.matches(key.getValue(), -1)) {
                return mapping;
            }
        }
        return null;
    }

}
