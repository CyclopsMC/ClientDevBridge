package org.cyclops.clientdevbridge.snapshot;

import com.google.gson.JsonObject;

/**
 * Contributes type-specific detail about one widget to its snapshot node.
 *
 * This is the extension point for mods: CyclopsCore, Integrated Dynamics or any other mod can
 * register an extractor for its own widget types so that `snapshot` describes them as usefully as
 * it describes vanilla's. Register during client setup:
 *
 * <pre>{@code
 * SnapshotExtractors.register(MyFancyWidget.class, (widget, node) -> {
 *     node.value(widget.getFraction());
 *     node.extra("mode", widget.getMode().name());
 * });
 * }</pre>
 *
 * Extractors must not mutate the widget or the game: `snapshot` is expected to be side-effect free
 * so that an agent can call it freely between actions.
 *
 * @param <T> the widget type this extractor understands
 * @author rubensworks
 */
@FunctionalInterface
public interface SnapshotExtractor<T> {

    /**
     * @param widget the widget being described
     * @param node   the node to enrich; set {@link SnapshotNode#value} and add {@code extra} entries
     */
    void extract(T widget, SnapshotNode node);

    /**
     * Convenience for extractors that only want to add raw JSON.
     */
    static <T> SnapshotExtractor<T> ofExtra(java.util.function.BiConsumer<T, JsonObject> consumer) {
        return (widget, node) -> consumer.accept(widget, node.extra());
    }

}
