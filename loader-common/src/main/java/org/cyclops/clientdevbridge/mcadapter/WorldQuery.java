package org.cyclops.clientdevbridge.mcadapter;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.cyclops.clientdevbridge.protocol.Json;

/**
 * Reading blocks out of the world.
 *
 * @author rubensworks
 */
public class WorldQuery {

    public static JsonObject block(BlockPos pos, boolean includeNbt) {
        BlockState state = ClientState.requireLevel().getBlockState(pos);

        JsonObject result = Json.object();
        result.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        result.add("pos", Json.arrayOfNumbers(pos.getX(), pos.getY(), pos.getZ()));
        result.addProperty("state", state.toString());

        JsonObject properties = Json.object();
        for (Property<?> property : state.getProperties()) {
            properties.addProperty(property.getName(), describe(state, property));
        }
        result.add("properties", properties);

        BlockEntity blockEntity = ClientState.requireLevel().getBlockEntity(pos);
        if (blockEntity == null) {
            result.add("blockEntity", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonObject entityObject = Json.object();
            entityObject.addProperty("type", BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getKey(blockEntity.getType()).toString());
            if (includeNbt) {
                // The client only has whatever the server chose to sync, which for most block
                // entities is the update tag rather than the full save data.
                entityObject.addProperty("nbt", blockEntity
                        .saveWithoutMetadata(ClientState.requireLevel().registryAccess()).toString());
            }
            result.add("blockEntity", entityObject);
        }
        return result;
    }

    private static <T extends Comparable<T>> String describe(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

}
