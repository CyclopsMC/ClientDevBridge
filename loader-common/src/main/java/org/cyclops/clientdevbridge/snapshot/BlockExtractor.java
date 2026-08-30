package org.cyclops.clientdevbridge.snapshot;

import com.google.gson.JsonObject;

/**
 * Adds mod-specific detail to what {@code world.block} reports about a block.
 *
 * The default description is the block id, its state properties and its block entity type, which
 * is everything a vanilla block is. It is not everything a multipart block is: a cable carrying a
 * Redstone Writer and a bare one are the same block, the same state and the same block entity
 * type, so an agent cannot tell from the description whether the setup it just built worked.
 *
 * An extractor is registered against a block entity class and writes whatever distinguishes one
 * instance from another:
 *
 * <pre>
 * BlockExtractors.register(BlockEntityMultipartTicking.class, (blockEntity, details) -&gt; {
 *     for (Direction side : Direction.values()) {
 *         details.addProperty(side.getName(), name(blockEntity.getPart(side)));
 *     }
 * });
 * </pre>
 *
 * @author rubensworks
 */
@FunctionalInterface
public interface BlockExtractor<T> {

    void extract(T blockEntity, JsonObject details);

}
