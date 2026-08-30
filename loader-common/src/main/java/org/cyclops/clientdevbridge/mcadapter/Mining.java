package org.cyclops.clientdevbridge.mcadapter;

import com.google.gson.JsonArray;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.cyclops.clientdevbridge.protocol.Json;

/**
 * Breaking a block, the way a player does: by holding attack until the block gives way.
 *
 * A single click does nothing -- mining is a held action, and how long it takes is a function of
 * the block, the tool, and whether the tool is the right one at all. That is exactly the thing a
 * caller should not have to know, and exactly what makes the wait worth measuring: the tick count
 * is the observable difference between the right tool and the wrong one.
 *
 * The progress is advanced <em>once per tick</em> and not in a loop. Calling
 * {@code continueDestroyBlock} repeatedly inside one tick does break the block -- an integrated
 * server validates loosely enough to accept it -- but it is not mining, it makes the tool
 * irrelevant, and a server that checks would refuse it.
 *
 * @author rubensworks
 */
public class Mining {

    public static void start(Aim aim) {
        Minecraft.getInstance().gameMode.startDestroyBlock(aim.pos(), aim.face());
    }

    /** One tick's worth of progress. Answers whether the block has gone. */
    public static boolean advance(Aim aim) {
        Minecraft minecraft = Minecraft.getInstance();
        if (isBroken(aim.pos())) {
            return true;
        }
        minecraft.gameMode.continueDestroyBlock(aim.pos(), aim.face());
        // The player swings while mining, and a screenshot taken mid-break should show that rather
        // than a player standing still with a block crumbling in front of them.
        minecraft.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return isBroken(aim.pos());
    }

    public static void stop() {
        Minecraft.getInstance().gameMode.stopDestroyBlock();
    }

    public static boolean isBroken(BlockPos pos) {
        return ClientState.requireLevel().getBlockState(pos).isAir();
    }

    /**
     * What is lying on the ground near a position.
     *
     * The point of breaking a block in survival is usually the drop, and the drop is a server-side
     * entity that arrives a moment after the block goes -- so it is worth reporting rather than
     * leaving the caller to go looking for it.
     */
    public static JsonArray dropsNear(BlockPos pos) {
        JsonArray drops = new JsonArray();
        // Wide enough for a drop that bounced: they are thrown, not placed.
        AABB around = new AABB(pos).inflate(4.0d);
        for (ItemEntity entity : ClientState.requireLevel()
                .getEntitiesOfClass(ItemEntity.class, around)) {
            com.google.gson.JsonObject drop = Json.object();
            drop.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(entity.getItem().getItem()).toString());
            drop.addProperty("count", entity.getItem().getCount());
            // Where it actually landed, which is not where the block was: a drop is thrown with a
            // small random velocity and settles a block or two away. Without this a caller has
            // nowhere to walk to in order to pick it up.
            drop.add("pos", Json.arrayOfNumbers(entity.getX(), entity.getY(), entity.getZ()));
            drops.add(drop);
        }
        return drops;
    }

}
