package org.cyclops.clientdevbridge.snapshot;

import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import org.cyclops.clientdevbridge.ClientDevBridge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The item-description extractor registry: {@link BlockExtractors} for what a stack holds rather
 * than what a block is.
 *
 * Lookup walks up the item's class hierarchy and runs the whole matching chain, base classes first,
 * for the same reasons -- a mod registering against its own base item covers every item it adds.
 *
 * @author rubensworks
 */
public class ItemExtractors {

    private static final Map<Class<?>, ItemExtractor<?>> EXTRACTORS = new LinkedHashMap<>();

    public static <T extends net.minecraft.world.item.Item> void register(
            Class<T> type, ItemExtractor<T> extractor) {
        ItemExtractor<?> existing = EXTRACTORS.put(type, extractor);
        if (existing != null) {
            ClientDevBridge.LOGGER.debug("Replaced the ClientDevBridge item extractor for {}", type.getName());
        }
    }

    @SuppressWarnings("unchecked")
    public static void apply(ItemStack stack, JsonObject details) {
        if (EXTRACTORS.isEmpty() || stack.isEmpty()) {
            // Every described stack goes through here, including forty-odd empty inventory slots
            // per snapshot, so the common case must cost nothing.
            return;
        }
        Deque<Class<?>> chain = new ArrayDeque<>();
        for (Class<?> type = stack.getItem().getClass(); type != null; type = type.getSuperclass()) {
            if (EXTRACTORS.containsKey(type)) {
                chain.addFirst(type);
            }
        }
        for (Class<?> type : chain) {
            try {
                ((ItemExtractor<Object>) EXTRACTORS.get(type)).extract(stack, details);
            } catch (Throwable e) {
                // A description is a diagnostic. One misbehaving extractor must not turn it into
                // an error, or it takes away the tool being used to find the problem.
                details.addProperty("extractorError", e.getClass().getSimpleName() + ": " + e.getMessage());
                ClientDevBridge.LOGGER.warn("Item extractor for {} failed", type.getName(), e);
            }
        }
    }

    public static int size() {
        return EXTRACTORS.size();
    }

}
