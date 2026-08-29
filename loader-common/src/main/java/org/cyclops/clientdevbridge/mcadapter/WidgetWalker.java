package org.cyclops.clientdevbridge.mcadapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.snapshot.SnapshotExtractors;
import org.cyclops.clientdevbridge.snapshot.SnapshotNode;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks the open screen's widget tree into the version-independent {@link SnapshotNode} model.
 *
 * Everything here depends on the shape of Minecraft's GUI classes and is therefore expected to need
 * attention on a version bump; the model it produces, the JSON schema, and the CLI's outline
 * formatter do not.
 *
 * @author rubensworks
 */
public class WidgetWalker {

    /** Hard caps from the protocol, so a pathological screen cannot produce an unbounded result. */
    public static final int MAX_DEPTH = 12;
    public static final int MAX_NODES = 2000;

    private final boolean includeHidden;
    private final int maxDepth;
    private int nodeCount;
    private boolean truncated;

    public WidgetWalker(boolean includeHidden, int maxDepth) {
        this.includeHidden = includeHidden;
        this.maxDepth = Math.min(maxDepth, MAX_DEPTH);
    }

    public boolean isTruncated() {
        return this.truncated;
    }

    /**
     * Builds the whole {@code screen.snapshot} result for the currently open screen.
     */
    public static JsonObject snapshot(boolean includeHidden, int maxDepth) {
        JsonObject result = Json.object();
        Geometry.addMetrics(result);
        result.add("mouse", Json.arrayOfNumbers(InputControl.getMouseX(), InputControl.getMouseY()));

        Screen screen = ClientState.screen();
        if (screen == null) {
            result.addProperty("screenClass", (String) null);
            result.addProperty("title", (String) null);
            result.add("root", com.google.gson.JsonNull.INSTANCE);
            result.add("container", com.google.gson.JsonNull.INSTANCE);
            result.addProperty("truncated", false);
            return result;
        }

        result.addProperty("screenClass", screen.getClass().getName());
        result.addProperty("title", screen.getTitle().getString());

        WidgetWalker walker = new WidgetWalker(includeHidden, maxDepth);
        SnapshotNode root = walker.walkScreen(screen);
        result.add("root", root.toJson());
        result.addProperty("truncated", walker.truncated);

        String focused = find(root, SnapshotNode::isFocused);
        String hovered = find(root, SnapshotNode::isHovered);
        result.addProperty("focused", focused);
        result.addProperty("hovered", hovered);

        result.add("container", screen instanceof AbstractContainerScreen<?> container
                ? describeContainer(container) : com.google.gson.JsonNull.INSTANCE);
        return result;
    }

    @Nullable
    private static String find(SnapshotNode root, java.util.function.Predicate<SnapshotNode> predicate) {
        List<String> found = new ArrayList<>(1);
        root.visit(node -> {
            if (found.isEmpty() && predicate.test(node)) {
                found.add(node.getPath());
            }
        });
        return found.isEmpty() ? null : found.get(0);
    }

    private SnapshotNode walkScreen(Screen screen) {
        SnapshotNode root = new SnapshotNode("/root", screen.getClass().getName());
        root.bounds(new SnapshotNode.Bounds(0, 0,
                Geometry.window().getGuiScaledWidth(), Geometry.window().getGuiScaledHeight()));
        root.message(screen.getTitle().getString());
        root.flags(true, true, false, false);
        this.nodeCount = 1;

        addChildren(root, screen.children(), 1);

        if (this.includeHidden) {
            // Renderables that are not GuiEventListeners are pure decoration and never appear in
            // children(); they only matter when a caller is asking what is drawn, not what is clickable.
            for (Renderable renderable : renderables(screen)) {
                if (!(renderable instanceof GuiEventListener)) {
                    SnapshotNode node = newNode(root, root.getChildren().size(), renderable);
                    if (node == null) {
                        break;
                    }
                    node.extra("decorationOnly", true);
                }
            }
        }
        return root;
    }

    private void addChildren(SnapshotNode parent, List<? extends GuiEventListener> children, int depth) {
        if (depth > this.maxDepth) {
            this.truncated = true;
            return;
        }
        int index = 0;
        for (GuiEventListener child : children) {
            SnapshotNode node = newNode(parent, index++, child);
            if (node == null) {
                return;
            }
            if (child instanceof ContainerEventHandler container) {
                addChildren(node, container.children(), depth + 1);
            }
        }
    }

    /**
     * @return the new node, or null once the node budget is spent
     */
    @Nullable
    private SnapshotNode newNode(SnapshotNode parent, int index, Object widget) {
        if (this.nodeCount >= MAX_NODES) {
            this.truncated = true;
            return null;
        }
        this.nodeCount++;

        SnapshotNode node = new SnapshotNode(parent.getPath() + "/children[" + index + "]",
                widget.getClass().getName());
        describe(widget, node);
        SnapshotExtractors.apply(widget, node);
        parent.addChild(node);
        return node;
    }

    /**
     * Fills in the fields every widget has, however it is implemented.
     */
    private void describe(Object widget, SnapshotNode node) {
        if (widget instanceof AbstractWidget abstractWidget) {
            node.bounds(new SnapshotNode.Bounds(abstractWidget.getX(), abstractWidget.getY(),
                    abstractWidget.getWidth(), abstractWidget.getHeight()));
            Component message = abstractWidget.getMessage();
            node.message(message == null ? null : message.getString());
            node.extra("component", message == null ? null : Component.Serializer.toJson(message,
                    ClientState.registryAccess()));
            node.narration(narrationOf(abstractWidget));
            node.flags(abstractWidget.visible, abstractWidget.isActive(),
                    abstractWidget.isFocused(), abstractWidget.isHovered());
            return;
        }

        if (widget instanceof GuiEventListener listener) {
            ScreenRectangle rectangle = listener.getRectangle();
            if (rectangle.width() > 0 && rectangle.height() > 0) {
                node.bounds(new SnapshotNode.Bounds(rectangle.left(), rectangle.top(),
                        rectangle.width(), rectangle.height()));
            } else {
                // A component that reports no rectangle is not introspectable through the standard
                // interfaces -- vanilla's recipe book is the common example. Claiming it is 0x0
                // would assert something false about a thing that is plainly on screen; saying
                // nothing about its bounds, and why, is more honest and more useful.
                node.bounds(reflectBounds(widget));
                node.extra("boundsUnknown", true);
                node.extra("note", "This component reports no rectangle, so its position and its "
                        + "own children are not visible to the snapshot. Read a screenshot to see it, "
                        + "or click it by coordinate.");
            }
            node.flags(true, true, listener.isFocused(), false);
            return;
        }

        node.bounds(reflectBounds(widget));
        node.flags(true, true, false, false);
    }

    /**
     * Last resort for widget types that are neither {@code AbstractWidget} nor {@code GuiEventListener}:
     * look for the conventional x/y/width/height fields before giving up on bounds entirely.
     */
    @Nullable
    private static SnapshotNode.Bounds reflectBounds(Object widget) {
        Integer x = intField(widget, "x");
        Integer y = intField(widget, "y");
        Integer width = intField(widget, "width");
        Integer height = intField(widget, "height");
        if (x == null || y == null || width == null || height == null) {
            return null;
        }
        return new SnapshotNode.Bounds(x, y, width, height);
    }

    @Nullable
    private static Integer intField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    return field.getInt(target);
                }
            } catch (NoSuchFieldException ignored) {
                // Keep walking up the hierarchy.
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Collects the widget's narration, which is often the only human-readable label a
     * message-less widget (an icon button, say) has.
     */
    @Nullable
    private static String narrationOf(AbstractWidget widget) {
        StringBuilder builder = new StringBuilder();
        try {
            widget.updateNarration(new NarrationElementOutput() {
                @Override
                public void add(net.minecraft.client.gui.narration.NarratedElementType type,
                                net.minecraft.client.gui.narration.NarrationThunk<?> contents) {
                    contents.getText(text -> {
                        if (text != null && !text.isEmpty()) {
                            if (builder.length() > 0) {
                                builder.append(' ');
                            }
                            builder.append(text);
                        }
                    });
                }

                @Override
                public NarrationElementOutput nest() {
                    return this;
                }
            });
        } catch (Throwable e) {
            // Narration is a nicety; a widget that throws while narrating must not fail the snapshot.
            return null;
        }
        String narration = builder.toString();
        return narration.isEmpty() ? null : narration;
    }

    @SuppressWarnings("unchecked")
    private static List<Renderable> renderables(Screen screen) {
        return (List<Renderable>) (List<?>) screen.renderables;
    }

    /**
     * The container block of the snapshot: slot positions in absolute GUI space, which is what a
     * caller needs in order to click one.
     */
    private static JsonObject describeContainer(AbstractContainerScreen<?> screen) {
        JsonObject container = Json.object();
        container.addProperty("menuClass", screen.getMenu().getClass().getName());
        container.addProperty("leftPos", screen.leftPos);
        container.addProperty("topPos", screen.topPos);
        container.addProperty("imageWidth", screen.imageWidth);
        container.addProperty("imageHeight", screen.imageHeight);
        container.add("carried", PlayerControl.describeStack(-1, screen.getMenu().getCarried()));

        Slot hovered = screen.hoveredSlot;
        JsonArray slots = new JsonArray();
        for (Slot slot : screen.getMenu().slots) {
            JsonObject slotObject = PlayerControl.describeStack(slot.index, slot.getItem());
            // Absolute GUI-space coordinates: slot.x/y are relative to the container's top-left.
            slotObject.addProperty("x", screen.leftPos + slot.x);
            slotObject.addProperty("y", screen.topPos + slot.y);
            slotObject.addProperty("hovered", hovered != null && hovered.index == slot.index);
            slots.add(slotObject);
        }
        container.add("slots", slots);
        return container;
    }

}
