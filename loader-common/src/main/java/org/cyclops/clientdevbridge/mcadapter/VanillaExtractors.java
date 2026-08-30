package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.components.Tooltip;
import org.cyclops.clientdevbridge.snapshot.ItemExtractors;
import org.cyclops.clientdevbridge.snapshot.SnapshotExtractors;

/**
 * Snapshot detail for the vanilla widget and item types.
 *
 * The exact set of classes differs between Minecraft versions, which is why this lives in
 * {@code mcadapter} — the registries and the node model they feed do not change.
 *
 * @author rubensworks
 */
public class VanillaExtractors {

    public static void registerAll() {
        registerItems();

        SnapshotExtractors.register(Button.class, (button, node) ->
                node.extra("kind", "button"));

        SnapshotExtractors.register(ImageButton.class, (button, node) ->
                node.extra("kind", "imageButton"));

        SnapshotExtractors.register(EditBox.class, (editBox, node) -> {
            node.value(editBox.getValue());
            node.extra("kind", "editBox");
            node.extra("editable", editBox.isActive());
        });

        SnapshotExtractors.register(Checkbox.class, (checkbox, node) -> {
            node.value(checkbox.selected());
            node.extra("kind", "checkbox");
        });

        SnapshotExtractors.register(CycleButton.class, (cycleButton, node) -> {
            Object value = cycleButton.getValue();
            node.value(value == null ? null : String.valueOf(value));
            node.extra("kind", "cycleButton");
        });

        SnapshotExtractors.register(AbstractSliderButton.class, (slider, node) -> {
            // Normalised 0..1, which is the only representation the base class exposes; a subclass
            // knows how to map it onto its own units, but the snapshot deliberately does not guess.
            node.value(slider.value);
            node.extra("kind", "slider");
        });

        SnapshotExtractors.register(AbstractSelectionList.class, (list, node) -> {
            node.extra("kind", "selectionList");
            node.extra("entryCount", list.children().size());
            node.extra("rowWidth", list.getRowWidth());
            Object selected = list.getSelected();
            node.extra("selected", selected == null ? null : String.valueOf(selected));
        });

        // AbstractScrollWidget became AbstractScrollArea, and the scroll accessors lost their
        // get- prefixes. AbstractSelectionList now inherits its scrolling from here too.
        SnapshotExtractors.register(AbstractScrollArea.class, (widget, node) -> {
            node.extra("kind", "scrollArea");
            node.extra("scrollbarWidth", widget.scrollbarWidth());
            node.extra("scrollAmount", widget.scrollAmount());
            node.extra("maxScroll", widget.maxScrollAmount());
        });

        SnapshotExtractors.register(StringWidget.class, (widget, node) ->
                node.extra("kind", "text"));

        SnapshotExtractors.register(MultiLineTextWidget.class, (widget, node) ->
                node.extra("kind", "multiLineText"));

        SnapshotExtractors.register(TabNavigationBar.class, (bar, node) ->
                node.extra("kind", "tabNavigation"));

        // Widget tooltips are attached rather than rendered inline, so they would otherwise be
        // invisible to a snapshot even though they are exactly what a caller is looking for.
        SnapshotExtractors.register(net.minecraft.client.gui.components.AbstractWidget.class, (widget, node) -> {
            Tooltip tooltip = TooltipCapture.attachedTooltip(widget);
            if (tooltip != null) {
                node.extra("tooltip", TooltipCapture.describe(tooltip));
            }
        });
    }

    /**
     * Item detail for the vanilla types whose default description hides what matters.
     *
     * A stack is described by its id, count, name and the components' combined {@code toString}.
     * For a container item that last part is the only field that says what is inside, and it is
     * unreadable — so a shulker box with four stacks in it and an empty one describe identically,
     * which is exactly the case {@link ItemExtractors} exists for.
     */
    private static void registerItems() {
        ItemExtractors.register(net.minecraft.world.item.BlockItem.class, (stack, details) ->
                details.addProperty("places", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(((net.minecraft.world.item.BlockItem) stack.getItem()).getBlock()).toString()));

        ItemExtractors.register(net.minecraft.world.item.Item.class, (stack, details) -> {
            net.minecraft.world.item.component.ItemContainerContents contents =
                    stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
            if (contents != null) {
                com.google.gson.JsonArray inside = new com.google.gson.JsonArray();
                contents.nonEmptyItemCopyStream().forEach(held -> inside.add(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem())
                                + " x" + held.getCount()));
                details.add("contains", inside);
            }
            if (stack.isDamaged()) {
                details.addProperty("damage", stack.getDamageValue() + "/" + stack.getMaxDamage());
            }
        });
    }

}
