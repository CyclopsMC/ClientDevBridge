package org.cyclops.clientdevbridge.snapshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.protocol.Json;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * One node of the widget tree, in the shape the protocol promises.
 *
 * The node model is deliberately free of Minecraft types so it stays identical on every branch;
 * only the code that <em>fills</em> it is version-sensitive.
 *
 * @author rubensworks
 */
public class SnapshotNode {

    private final String path;
    private final String type;
    private final JsonObject extra = Json.object();
    private final List<SnapshotNode> children = new ArrayList<>();

    @Nullable
    private Bounds bounds;
    @Nullable
    private String message;
    @Nullable
    private String narration;
    @Nullable
    private JsonElement value;
    private boolean visible = true;
    private boolean active = true;
    private boolean focused;
    private boolean hovered;

    public SnapshotNode(String path, String type) {
        this.path = path;
        this.type = type;
    }

    public record Bounds(int x, int y, int width, int height) {
        public JsonObject toJson() {
            JsonObject object = Json.object();
            object.addProperty("x", this.x);
            object.addProperty("y", this.y);
            object.addProperty("w", this.width);
            object.addProperty("h", this.height);
            return object;
        }

        /** The point a click on this widget should target. */
        public int centreX() {
            return this.x + this.width / 2;
        }

        public int centreY() {
            return this.y + this.height / 2;
        }
    }

    public String getPath() {
        return this.path;
    }

    public String getType() {
        return this.type;
    }

    @Nullable
    public Bounds getBounds() {
        return this.bounds;
    }

    public SnapshotNode bounds(@Nullable Bounds value) {
        this.bounds = value;
        return this;
    }

    public SnapshotNode message(@Nullable String value) {
        this.message = value;
        return this;
    }

    public SnapshotNode narration(@Nullable String value) {
        this.narration = value;
        return this;
    }

    public SnapshotNode value(@Nullable Object raw) {
        this.value = raw == null ? JsonNull.INSTANCE : Json.toTree(raw);
        return this;
    }

    public SnapshotNode flags(boolean isVisible, boolean isActive, boolean isFocused, boolean isHovered) {
        this.visible = isVisible;
        this.active = isActive;
        this.focused = isFocused;
        this.hovered = isHovered;
        return this;
    }

    public boolean isFocused() {
        return this.focused;
    }

    public boolean isHovered() {
        return this.hovered;
    }

    public JsonObject extra() {
        return this.extra;
    }

    public SnapshotNode extra(String key, @Nullable Object raw) {
        this.extra.add(key, raw == null ? JsonNull.INSTANCE : Json.toTree(raw));
        return this;
    }

    public List<SnapshotNode> getChildren() {
        return this.children;
    }

    public SnapshotNode addChild(SnapshotNode child) {
        this.children.add(child);
        return child;
    }

    public JsonObject toJson() {
        JsonObject object = Json.object();
        object.addProperty("path", this.path);
        object.addProperty("type", this.type);
        object.add("bounds", this.bounds == null ? JsonNull.INSTANCE : this.bounds.toJson());
        object.addProperty("message", this.message);
        object.addProperty("narration", this.narration);
        object.addProperty("visible", this.visible);
        object.addProperty("active", this.active);
        object.addProperty("focused", this.focused);
        object.addProperty("hovered", this.hovered);
        object.add("value", this.value == null ? JsonNull.INSTANCE : this.value);
        object.add("extra", this.extra);

        JsonArray childArray = new JsonArray(this.children.size());
        for (SnapshotNode child : this.children) {
            childArray.add(child.toJson());
        }
        object.add("children", childArray);
        return object;
    }

    /**
     * Depth-first walk, used to resolve a widget by path or by its label.
     */
    public void visit(java.util.function.Consumer<SnapshotNode> visitor) {
        visitor.accept(this);
        for (SnapshotNode child : this.children) {
            child.visit(visitor);
        }
    }

}
