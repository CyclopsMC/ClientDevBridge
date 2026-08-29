package org.cyclops.clientdevbridge.snapshot;

import org.cyclops.clientdevbridge.ClientDevBridge;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The extractor registry.
 *
 * Lookup walks up the class hierarchy, so registering for a base type covers every subclass and a
 * mod only needs to register the types it actually adds detail for.
 *
 * @author rubensworks
 */
public class SnapshotExtractors {

    private static final Map<Class<?>, SnapshotExtractor<?>> EXTRACTORS = new LinkedHashMap<>();

    public static <T> void register(Class<T> type, SnapshotExtractor<T> extractor) {
        SnapshotExtractor<?> existing = EXTRACTORS.put(type, extractor);
        if (existing != null) {
            ClientDevBridge.LOGGER.debug("Replaced the ClientDevBridge snapshot extractor for {}", type.getName());
        }
    }

    /**
     * Applies every registered extractor that matches the widget, base classes first.
     *
     * Running the whole chain rather than only the closest match means an extractor registered for
     * a base type (the one that reports attached tooltips, say) still contributes to a widget that
     * also has a more specific extractor, and the specific one can override what the base one set.
     */
    @SuppressWarnings("unchecked")
    public static void apply(Object widget, SnapshotNode node) {
        java.util.Deque<Class<?>> chain = new java.util.ArrayDeque<>();
        for (Class<?> type = widget.getClass(); type != null; type = type.getSuperclass()) {
            if (EXTRACTORS.containsKey(type)) {
                chain.addFirst(type);
            }
        }
        for (Class<?> type : chain) {
            SnapshotExtractor<?> extractor = EXTRACTORS.get(type);
            try {
                ((SnapshotExtractor<Object>) extractor).extract(widget, node);
            } catch (Throwable e) {
                // One misbehaving extractor must not take down the whole snapshot: an agent
                // relying on this to debug a GUI needs the rest of the tree more than it needs
                // the failure to be fatal.
                node.extra("extractorError", e.getClass().getSimpleName() + ": " + e.getMessage());
                ClientDevBridge.LOGGER.warn("Snapshot extractor for {} failed", type.getName(), e);
            }
        }
    }

    @Nullable
    public static SnapshotExtractor<?> find(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            SnapshotExtractor<?> extractor = EXTRACTORS.get(current);
            if (extractor != null) {
                return extractor;
            }
        }
        return null;
    }

    public static int size() {
        return EXTRACTORS.size();
    }

}
