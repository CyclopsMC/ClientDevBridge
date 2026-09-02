package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.cyclops.clientdevbridge.ClientDevBridge;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Creating, loading, deleting and leaving singleplayer worlds.
 *
 * A world is always created <em>programmatically</em>, through Minecraft's own world-creation code,
 * rather than by shipping a save file. A save file is written in the save format of one particular
 * Minecraft version, so it would silently rot on every branch; going through {@code WorldOpenFlows}
 * is correct for whatever version this branch is built against, by construction.
 *
 * @author rubensworks
 */
public class WorldControl {

    /** A fixed seed, so a reset world is byte-for-byte the same one every time. */
    public static final long SEED = 4_815_162_342L;

    /**
     * The rules that make a test world hold still: no day/night, no weather, no mobs, no drops
     * disappearing, and no advancement toasts drifting across a screenshot.
     */
    private static final List<String> DETERMINISM_GAMERULES = List.of(
            // Minecraft 26 renamed every game rule to a snake_case registry id, and renamed several
            // outright: doDaylightCycle became advance_time, announceAdvancements became
            // show_advancement_messages, doInsomnia became spawn_phantoms, and doFireTick became a
            // radius rather than a flag. The 1.21 names are not merely deprecated here, they are
            // rejected -- which is why applyDeterminism now checks each one.
            "advance_time false",
            "advance_weather false",
            "spawn_mobs false",
            "fire_spread_radius_around_player 0",
            "random_tick_speed 0",
            "mob_griefing false",
            "spawn_wandering_traders false",
            "spawn_patrols false",
            "show_advancement_messages false",
            "send_command_feedback true",
            "spawn_phantoms false",
            "players_sleeping_percentage 200");

    public static final int SPAWN_X = 0;
    public static final int SPAWN_Y = 4;
    public static final int SPAWN_Z = 0;

    /**
     * Half-width of the stone platform built under the spawn.
     *
     * The FLAT preset's surface is far below the documented spawn of 0,4,0, so without a platform
     * the player is spawned into open air and falls — which quietly breaks anything that depends
     * on standing still, most visibly a container screen closing as the player drops out of range.
     * Building the platform keeps the spawn at the documented, easy-to-reason-about coordinates
     * and gives screenshots a uniform backdrop.
     */
    public static final int PLATFORM_RADIUS = 8;
    public static final int PLATFORM_Y = SPAWN_Y - 1;

    public static LevelStorageSource levelSource() {
        return Minecraft.getInstance().getLevelSource();
    }

    public static boolean exists(String name) {
        return levelSource().levelExists(name);
    }

    public static List<String> listWorlds() {
        try (var stream = Files.list(levelSource().getBaseDir())) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            throw RpcException.illegalState("Could not list the saves directory: " + e.getMessage());
        }
    }

    /**
     * Leaves the current world, if any, and waits for the client to actually be out of it.
     */
    public static void leave() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            minecraft.disconnectWithProgressScreen();
        }
        minecraft.setScreenAndShow(null);
    }

    /**
     * Deletes a save directory. The world must not be loaded.
     */
    public static void delete(String name) {
        if (!exists(name)) {
            return;
        }
        try (LevelStorageSource.LevelStorageAccess access = levelSource().createAccess(name)) {
            access.deleteLevel();
        } catch (IOException e) {
            throw RpcException.illegalState("Could not delete the world '" + name + "': " + e.getMessage());
        }
    }

    /**
     * Copies a template directory committed in the consumer repository into the saves directory.
     *
     * @param templatesRoot {@code <projectDir>/clientdevbridge/templates}
     */
    /**
     * Checks a template exists before anything is destroyed on its behalf.
     *
     * Splitting this out of {@link #copyTemplate} is the whole point: world.reset leaves the world
     * and deletes it before it copies, so a typo'd template name used to cost the caller the world
     * they had and leave them at the title screen with nothing.
     */
    public static void requireTemplate(Path templatesRoot, String template) {
        Path source = templatesRoot.resolve(template);
        if (!Files.isDirectory(source)) {
            throw RpcException.invalidParams("No world template '" + template + "' at " + source
                    + ". Commit one there, or drop --template to generate a fresh superflat world.");
        }
    }

    public static void copyTemplate(Path templatesRoot, String template, String worldName) {
        requireTemplate(templatesRoot, template);
        Path source = templatesRoot.resolve(template);
        Path target = levelSource().getLevelPath(worldName);
        try {
            copyRecursively(source, target);
        } catch (IOException e) {
            throw RpcException.illegalState("Could not copy the world template '" + template + "': " + e.getMessage());
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    /**
     * Creates a fresh creative superflat world and starts loading it.
     *
     * This returns as soon as loading has been kicked off; callers wait for the {@code world.joined}
     * condition rather than blocking the client thread.
     */
    public static void createSuperflat(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        // Difficulty and hardcore moved into DifficultySettings, and game rules are no longer part
        // of LevelSettings at all; the bridge sets them through commands once the world is up.
        LevelSettings settings = new LevelSettings(
                name,
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true, // Cheats on: everything the bridge does with the world goes through commands.
                WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(SEED, false, false);

        minecraft.createWorldOpenFlows().createFreshLevel(
                name,
                settings,
                options,
                WorldControl::flatDimensions,
                null);
    }

    private static net.minecraft.world.level.levelgen.WorldDimensions flatDimensions(HolderLookup.Provider registries) {
        return registries
                .lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(WorldPresets.FLAT)
                .value()
                .createWorldDimensions();
    }

    /**
     * Fails with the list of real world names if this one does not exist.
     */
    public static void requireExists(String name) {
        if (!exists(name)) {
            java.util.List<String> worlds = listWorlds();
            throw RpcException.invalidParams("There is no world called '" + name + "'. "
                    + (worlds.isEmpty()
                            ? "This run directory has no worlds at all yet; make one with "
                                    + "'clientdevbridge world-reset'."
                            : "Existing worlds: " + String.join(", ", worlds)));
        }
    }

    public static void load(String name) {
        requireExists(name);
        Minecraft.getInstance().createWorldOpenFlows().openWorld(name, () ->
                ClientDevBridge.LOGGER.warn("Failed to open world {}", name));
    }

    /**
     * Applies the determinism game rules and puts the player at a known spot.
     * Run once the world has finished loading.
     */
    public static void applyDeterminism(@Nullable String extraSetup) {
        // One hop to the server thread for the whole sequence rather than one per command.
        CommandRunner.onServerThread(() -> applyDeterminismOnServerThread(extraSetup));
    }

    private static void applyDeterminismOnServerThread(@Nullable String extraSetup) {
        // Checked, not fire-and-forget. Minecraft 26 renamed every game rule -- camelCase became
        // snake_case, and announceAdvancements became show_advancement_messages -- so this whole
        // list failed silently on those branches for their entire life: the test world kept its
        // day/night cycle, its weather and its random ticks, and golden images passed on luck.
        // A rule that does not apply is a broken test world, so it is worth the whole reset.
        for (String rule : DETERMINISM_GAMERULES) {
            CommandRunner.runChecked("gamerule " + rule);
        }
        CommandRunner.run("time set noon");
        CommandRunner.run("weather clear");
        CommandRunner.run("gamemode creative @s");
        CommandRunner.run(String.format("fill %d %d %d %d %d %d minecraft:stone",
                SPAWN_X - PLATFORM_RADIUS, PLATFORM_Y, SPAWN_Z - PLATFORM_RADIUS,
                SPAWN_X + PLATFORM_RADIUS, PLATFORM_Y, SPAWN_Z + PLATFORM_RADIUS));
        CommandRunner.run(String.format("setworldspawn %d %d %d", SPAWN_X, SPAWN_Y, SPAWN_Z));
        CommandRunner.run("tp @s " + SPAWN_X + " " + SPAWN_Y + " " + SPAWN_Z + " 0 0");
        if (extraSetup != null && !extraSetup.isBlank()) {
            CommandRunner.run(extraSetup);
        }
    }

}
