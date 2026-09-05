package org.cyclops.clientdevbridge.mcadapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.cyclops.clientdevbridge.protocol.Json;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the tooltip that would be shown at a point on screen.
 *
 * Two sources cover essentially every tooltip a mod author cares about, and both can be read
 * directly rather than intercepted:
 *
 * <ul>
 *     <li>the item under the cursor in a container screen, through the same
 *     {@code getTooltipFromItem} the screen itself uses;</li>
 *     <li>the {@link Tooltip} attached to the hovered widget.</li>
 * </ul>
 *
 * A screen that draws a tooltip ad hoc inside its own {@code render} — rather than attaching one or
 * relying on a slot — is not visible here. Capturing those would mean mixing into
 * {@code GuiGraphics#renderTooltipInternal}, a private method whose argument type
 * ({@code ClientTooltipComponent}) does not expose its text; that is a lot of fragility across
 * versions for a case that a `screenshot` already shows. If you need it, register a
 * {@code SnapshotExtractor} for your screen's widget and put the text in {@code extra}.
 *
 * @author rubensworks
 */
public class TooltipCapture {

    /**
     * Describes the tooltip at a GUI-space point, moving the mouse there first so that hover state
     * matches what the caller asked about.
     */
    public static JsonObject at(double x, double y) {
        InputControl.mouseMove(x, y);

        JsonObject result = Json.object();
        result.add("at", Json.arrayOfNumbers(x, y));

        Screen screen = ClientState.screen();
        if (screen == null) {
            result.add("lines", new JsonArray());
            result.addProperty("source", "none");
            return result;
        }

        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = slotAt(container, x, y);
            if (slot != null && !slot.getItem().isEmpty()) {
                result.add("lines", linesOf(itemTooltip(slot.getItem())));
                result.addProperty("source", "slot");
                result.addProperty("slot", slot.index);
                result.add("item", PlayerControl.describeStack(slot.index, slot.getItem()));
                return result;
            }
        }

        AbstractWidget widget = widgetAt(screen, x, y);
        Tooltip attached = widget == null ? null : attachedTooltip(widget);
        if (attached != null) {
            result.add("lines", describe(attached));
            result.addProperty("source", "widget");
            result.addProperty("widget", widget.getClass().getName());
            result.addProperty("message", widget.getMessage() == null ? null : widget.getMessage().getString());
            return result;
        }

        // Three quite different situations used to answer "none" alike, and the third is the one
        // that misleads: a tooltip a mod paints in its own render is not attached to anything the
        // game models, so nothing here can reach it -- and reporting that as "no tooltip" says the
        // opposite of what a screenshot of the same point shows.
        result.add("lines", new JsonArray());
        if (widget != null) {
            result.addProperty("source", "widgetWithoutTooltip");
            result.addProperty("widget", widget.getClass().getName());
            result.addProperty("note", "There is a widget here and it has no tooltip attached.");
        } else {
            result.addProperty("source", "unmodelled");
            result.addProperty("note", "Nothing here is a widget or a slot, so there is no tooltip "
                    + "to read -- but a mod that paints its own tooltip in render() draws one "
                    + "without registering anything, and that cannot be read from here. Take a "
                    + "screenshot with the cursor parked at this point to see whether one is drawn.");
        }
        return result;
    }

    /**
     * The tooltip attached to a widget, which now lives behind a holder rather than a getter.
     */
    @Nullable
    public static Tooltip attachedTooltip(AbstractWidget widget) {
        return widget.tooltip.tooltip;
    }

    /**
     * The lines of an attached widget tooltip.
     */
    public static JsonArray describe(Tooltip tooltip) {
        JsonArray lines = new JsonArray();
        for (net.minecraft.util.FormattedCharSequence sequence : tooltip.toCharSequence(Minecraft.getInstance())) {
            lines.add(flatten(sequence));
        }
        return lines;
    }

    private static String flatten(net.minecraft.util.FormattedCharSequence sequence) {
        StringBuilder builder = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            builder.appendCodePoint(codePoint);
            return true;
        });
        return builder.toString();
    }

    private static List<Component> itemTooltip(ItemStack stack) {
        return Screen.getTooltipFromItem(Minecraft.getInstance(), stack);
    }

    private static JsonArray linesOf(List<Component> components) {
        JsonArray lines = new JsonArray();
        for (Component component : components) {
            lines.add(component.getString());
        }
        return lines;
    }

    @Nullable
    private static Slot slotAt(AbstractContainerScreen<?> screen, double x, double y) {
        for (Slot slot : screen.getMenu().slots) {
            double slotX = screen.leftPos + slot.x;
            double slotY = screen.topPos + slot.y;
            if (x >= slotX && x < slotX + 16 && y >= slotY && y < slotY + 16 && slot.isActive()) {
                return slot;
            }
        }
        return null;
    }

    /**
     * The deepest widget whose bounds contain the point, so a widget nested inside a list wins over
     * the list itself.
     */
    @Nullable
    public static AbstractWidget widgetAt(Screen screen, double x, double y) {
        List<AbstractWidget> matches = new ArrayList<>();
        collect(screen.children(), x, y, matches, 0);
        return matches.isEmpty() ? null : matches.get(matches.size() - 1);
    }

    private static void collect(List<? extends GuiEventListener> children, double x, double y,
                                List<AbstractWidget> matches, int depth) {
        if (depth > WidgetWalker.MAX_DEPTH) {
            return;
        }
        for (GuiEventListener child : children) {
            if (child instanceof AbstractWidget widget && widget.visible && widget.isMouseOver(x, y)) {
                matches.add(widget);
            }
            if (child instanceof ContainerEventHandler container) {
                collect(container.children(), x, y, matches, depth + 1);
            }
        }
    }

}
