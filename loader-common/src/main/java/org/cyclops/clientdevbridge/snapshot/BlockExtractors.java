package org.cyclops.clientdevbridge.snapshot;

import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.ClientDevBridge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The block-description extractor registry, the counterpart of {@link SnapshotExtractors} for the
 * world rather than the screen.
 *
 * Lookup walks up the class hierarchy and runs the whole matching chain, base classes first, for
 * the same reasons.
 *
 * @author rubensworks
 */
public class BlockExtractors {

    private static final Map<Class<?>, BlockExtractor<?>> EXTRACTORS = new LinkedHashMap<>();

    public static <T> void register(Class<T> type, BlockExtractor<T> extractor) {
        BlockExtractor<?> existing = EXTRACTORS.put(type, extractor);
        if (existing != null) {
            ClientDevBridge.LOGGER.debug("Replaced the ClientDevBridge block extractor for {}", type.getName());
        }
    }

    @SuppressWarnings("unchecked")
    public static void apply(Object blockEntity, JsonObject details) {
        Deque<Class<?>> chain = new ArrayDeque<>();
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            if (EXTRACTORS.containsKey(type)) {
                chain.addFirst(type);
            }
        }
        for (Class<?> type : chain) {
            try {
                ((BlockExtractor<Object>) EXTRACTORS.get(type)).extract(blockEntity, details);
            } catch (Throwable e) {
                // A description is a diagnostic. One misbehaving extractor must not turn it into
                // an error, or it takes away the tool being used to find the problem.
                details.addProperty("extractorError", e.getClass().getSimpleName() + ": " + e.getMessage());
                ClientDevBridge.LOGGER.warn("Block extractor for {} failed", type.getName(), e);
            }
        }
    }

    public static int size() {
        return EXTRACTORS.size();
    }

}
