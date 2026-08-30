package org.cyclops.clientdevbridge.mcadapter;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.RpcException;

import com.google.gson.JsonObject;

/**
 * Conversions between the two coordinate spaces the protocol uses.
 *
 * <ul>
 *     <li><b>GUI space</b>: Minecraft's scaled coordinates. All widget and screen positions are in
 *     GUI space, and it is the default for every input command.</li>
 *     <li><b>Pixel space</b>: raw framebuffer pixels, which is what screenshots are in.</li>
 * </ul>
 *
 * @author rubensworks
 */
public class Geometry {

    public static final String SPACE_GUI = "gui";
    public static final String SPACE_PIXEL = "pixel";

    public static Window window() {
        return Minecraft.getInstance().getWindow();
    }

    /**
     * Converts an x/y from the given space into GUI space.
     */
    public static double toGui(double value, String space) {
        return SPACE_PIXEL.equals(space) ? value / window().getGuiScale() : value;
    }

    /**
     * Converts an x/y from the given space into pixel space.
     */
    public static double toPixel(double value, String space) {
        return SPACE_PIXEL.equals(space) ? value : value * window().getGuiScale();
    }

    public static String requireSpace(String space) {
        if (space == null) {
            return SPACE_GUI;
        }
        if (!SPACE_GUI.equals(space) && !SPACE_PIXEL.equals(space)) {
            throw RpcException.invalidParams("Parameter 'space' must be 'gui' or 'pixel', but was '" + space + "'");
        }
        return space;
    }

    /** The GUI-space width of the screen, which is what every coordinate the bridge reports is in. */
    public static int guiWidth() {
        return window().getGuiScaledWidth();
    }

    public static int guiHeight() {
        return window().getGuiScaledHeight();
    }

    /**
     * The metrics block that every snapshot and screenshot result carries, so a caller can always
     * relate what it is looking at to the coordinates it should send back.
     */
    public static JsonObject metrics() {
        Window window = window();
        JsonObject metrics = Json.object();
        metrics.addProperty("guiScale", window.getGuiScale());
        metrics.addProperty("guiWidth", window.getGuiScaledWidth());
        metrics.addProperty("guiHeight", window.getGuiScaledHeight());
        metrics.addProperty("pixelWidth", window.getWidth());
        metrics.addProperty("pixelHeight", window.getHeight());
        return metrics;
    }

    /**
     * Copies the standard metrics fields onto an existing result object.
     */
    public static void addMetrics(JsonObject target) {
        JsonObject metrics = metrics();
        for (String key : metrics.keySet()) {
            target.add(key, metrics.get(key));
        }
    }

}
