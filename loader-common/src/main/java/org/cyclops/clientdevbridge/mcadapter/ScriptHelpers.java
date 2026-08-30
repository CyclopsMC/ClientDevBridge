package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The handful of game objects a script needs to construct, built on the game's side of the class
 * loader boundary.
 *
 * The game is loaded by a transforming class loader and the script engine is not, so a script that
 * writes {@code new net.minecraft.core.BlockPos(0, 4, 2)} fails with a message about class loaders
 * and no indication of what to do instead. Nothing about that is fixable from the script: the class
 * is genuinely not visible from there. What is fixable is needing to name it at all -- every object
 * reached through a binding comes from this side, where the loader is right, so a factory bound
 * alongside {@code player} and {@code level} removes the whole category of failure.
 *
 * Bound as {@code dev}, so a script reads {@code dev.pos(0, 4, 2)} and never a package name.
 *
 * @author rubensworks
 */
public class ScriptHelpers {

    /** A block position, the argument almost every level query wants. */
    public BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** A precise point, for anything measuring distances or angles. */
    public Vec3 vec(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    public BlockState block(int x, int y, int z) {
        return ClientState.requireLevel().getBlockState(pos(x, y, z));
    }

    /** The block's registry name, which is what a script is usually comparing against. */
    public String blockId(int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getKey(block(x, y, z).getBlock()).toString();
    }

    @Nullable
    public BlockEntity blockEntity(int x, int y, int z) {
        return ClientState.requireLevel().getBlockEntity(pos(x, y, z));
    }

    /**
     * A block entity's saved data as a string.
     *
     * The client's copy, which is only what the server chose to synchronise -- the same data
     * {@code clientdevbridge block --nbt} reports, and for the same reason: it is the fastest way
     * to see what a modded block actually holds.
     */
    @Nullable
    public String nbt(int x, int y, int z) {
        BlockEntity blockEntity = blockEntity(x, y, z);
        return blockEntity == null
                ? null
                : blockEntity.saveWithoutMetadata(ClientState.requireLevel().registryAccess()).toString();
    }

    /** An item stack from a registry name, for comparing against what is held or in a slot. */
    public ItemStack item(String id, int count) {
        ResourceLocation location = ResourceLocation.parse(id);
        Item found = BuiltInRegistries.ITEM.getOptional(location)
                .orElseThrow(() -> new IllegalArgumentException("No such item: " + id));
        return new ItemStack(found, count);
    }

    public ItemStack item(String id) {
        return item(id, 1);
    }

}
