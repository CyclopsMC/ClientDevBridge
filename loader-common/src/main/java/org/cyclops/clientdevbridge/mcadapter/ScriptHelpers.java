package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
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

    /**
     * An item stack from a registry name, for comparing against what is held or in a slot.
     *
     * Looked up by scanning the registry rather than by building a resource key from the string:
     * the class that wraps a registry name is called ResourceLocation on 1.21 and Identifier on 26,
     * and naming neither keeps this one file identical on every branch. The registry has on the
     * order of a thousand entries and this runs once per call, so the scan costs nothing worth
     * carrying a version difference for.
     */
    public ItemStack item(String id, int count) {
        String wanted = id.indexOf(':') < 0 ? "minecraft:" + id : id;
        for (Item candidate : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(candidate).toString().equals(wanted)) {
                return new ItemStack(candidate, count);
            }
        }
        throw new IllegalArgumentException("No such item: " + wanted);
    }

    public ItemStack item(String id) {
        return item(id, 1);
    }

    /**
     * One of a block's state properties, by name -- {@code dev.prop(0, 4, 2, "lit")}.
     *
     * The direct route is {@code state.getValue(BlockStateProperties.LIT)}, which names a game class
     * and so runs straight into the class loader wall this whole object exists to remove. Looking
     * the property up by name needs nothing from the script's side.
     *
     * The value is the game's own object rather than its {@code toString}, so a script can compare
     * it -- {@code dev.prop(x, y, z, "lit") == true} and {@code dev.prop(x, y, z, "power") > 0} both
     * work, and neither would on a string.
     */
    public Comparable<?> prop(int x, int y, int z, String name) {
        BlockState state = block(x, y, z);
        Property<?> property = property(state, name);
        if (property == null) {
            // The caller is asking by name precisely because they do not know what is there, so an
            // NPE out of getValue(null) is the least useful thing this could do.
            throw new IllegalArgumentException(String.format(
                    "%s at %d,%d,%d has no property '%s'. It has: %s",
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()), x, y, z, name,
                    state.getProperties().isEmpty() ? "none"
                            : state.getProperties().stream().map(Property::getName).sorted()
                                    .collect(java.util.stream.Collectors.joining(", "))));
        }
        return state.getValue(property);
    }

    /**
     * Every state property of a block, by name. This is the question that comes just before
     * {@link #prop}, and answering it separately saves a round trip through a failed guess.
     */
    public java.util.Map<String, Comparable<?>> props(int x, int y, int z) {
        BlockState state = block(x, y, z);
        java.util.Map<String, Comparable<?>> values = new java.util.LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            values.put(property.getName(), state.getValue(property));
        }
        return values;
    }

    /**
     * Every block id, optionally narrowed to one mod's namespace.
     *
     * "What does this mod register" is the first question anyone has about an unfamiliar mod, and
     * before this it could not be asked: naming {@code BuiltInRegistries} in a script throws, which
     * is the class loader wall this whole object exists to remove. The answer used to require
     * unzipping the mod jar and reading its model files, to learn something the running game knows.
     */
    public java.util.List<String> blocks(@Nullable String namespace) {
        return names(BuiltInRegistries.BLOCK.keySet(), namespace);
    }

    public java.util.List<String> blocks() {
        return blocks(null);
    }

    public java.util.List<String> items(@Nullable String namespace) {
        return names(BuiltInRegistries.ITEM.keySet(), namespace);
    }

    public java.util.List<String> items() {
        return items(null);
    }

    /**
     * The namespaces that registered a block or an item.
     *
     * Also the quickest way to tell that a mod is genuinely loaded rather than merely present in
     * the run configuration: a mod that failed to initialise registers nothing.
     */
    public java.util.List<String> namespaces() {
        java.util.SortedSet<String> found = new java.util.TreeSet<>();
        for (String id : blocks(null)) {
            found.add(id.substring(0, id.indexOf(':')));
        }
        for (String id : items(null)) {
            found.add(id.substring(0, id.indexOf(':')));
        }
        return new java.util.ArrayList<>(found);
    }

    /**
     * The keys of a registry as strings, sorted.
     *
     * The set holds whatever wraps a registry name -- ResourceLocation on 1.21, Identifier on 26 --
     * and naming neither is what keeps this file identical on every branch, exactly as
     * {@link #item} does.
     */
    private static java.util.List<String> names(java.util.Set<?> keys, @Nullable String namespace) {
        String prefix = namespace == null ? null : namespace + ":";
        java.util.List<String> found = new java.util.ArrayList<>();
        for (Object key : keys) {
            String id = key.toString();
            if (prefix == null || id.startsWith(prefix)) {
                found.add(id);
            }
        }
        java.util.Collections.sort(found);
        return found;
    }

    @Nullable
    private static Property<?> property(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

}
