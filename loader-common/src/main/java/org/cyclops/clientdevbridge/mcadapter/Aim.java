package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import org.cyclops.clientdevbridge.ClientDevBridge;
import net.minecraft.world.phys.Vec3;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;

/**
 * Where on a block an interaction is aimed.
 *
 * A vanilla block reads the {@link BlockHitResult} it is handed, so any face works and aiming was
 * never worth expressing. Multipart blocks do not: Integrated Dynamics' cables, and anything else
 * built on CyclopsCore's {@code VoxelShapeComponents}, throw the hit result away and re-raytrace
 * from the player's eye along their look angle to decide which sub-part was clicked. For those,
 * <em>where the player stands and looks</em> is the whole interaction, and a caller that cannot
 * say which side of a cable it means cannot open a part's GUI at all.
 *
 * So an aim carries both halves and keeps them consistent: the point that goes into the hit result,
 * and the position to stand in for the ray from the eye to that point to arrive through the
 * intended face.
 *
 * @author rubensworks
 */
public class Aim {

    /** How far from the aimed point to stand. Comfortably inside the default reach limit. */
    private static final double STANDING_DISTANCE = 2.5d;

    private final BlockPos pos;
    private final Direction face;
    private final Vec3 point;

    private Aim(BlockPos pos, Direction face, Vec3 point) {
        this.pos = pos;
        this.face = face;
        this.point = point;
    }

    /**
     * Resolves an aim from what a caller supplied, in plain types so the protocol layer never has
     * to name a Minecraft class.
     *
     * @param face  a face name, or null
     * @param at    a world-space point, or null
     */
    public static Aim of(BlockPos pos, @Nullable String face, @Nullable double[] at) {
        Direction direction = face == null ? null : parseFace(face);
        if (at != null) {
            Vec3 point = new Vec3(at[0], at[1], at[2]);
            return new Aim(pos, direction == null ? nearestFace(pos, point) : direction, point);
        }
        if (direction != null) {
            return new Aim(pos, direction, faceCentre(pos, direction));
        }
        // No aim given: the block's centre, as it always was. Every vanilla block behaves the same
        // whichever face it is handed, so this stays the right default.
        return new Aim(pos, Direction.UP, Vec3.atCenterOf(pos));
    }

    public BlockPos pos() {
        return this.pos;
    }

    public Direction face() {
        return this.face;
    }

    public Vec3 point() {
        return this.point;
    }

    /** Whether the caller asked for a particular spot, rather than taking the default. */
    public boolean isDirected() {
        return !this.point.equals(Vec3.atCenterOf(this.pos));
    }

    public BlockHitResult hit() {
        return new BlockHitResult(this.point, this.face, this.pos, false);
    }

    /**
     * Where the player has to stand for the aimed point to be reachable and, more importantly, for
     * the ray from their eye to reach it through {@link #face()} rather than through the block.
     *
     * The eye has to end up outside the aimed face, so the position depends on which face it is.
     * It also has to be somewhere the player can stand: teleporting to a point in mid-air means
     * they start falling immediately, which moves the eye out from under the aim before the click
     * and stops the arrival wait from ever seeing them land.
     *
     * <ul>
     *   <li>A side face: back off along its normal, standing level with the block's bottom.</li>
     *   <li>The top face: stand on the block and look straight down. A ray that comes in at an
     *       angle carries on through the block and out the far side, where it can clip a part on
     *       another face -- so on a cable, asking for the top would open whatever was on the
     *       north.</li>
     *   <li>The bottom face: there is nowhere good. The player has to be under the block, which in
     *       a flat test world is inside the ground -- survivable in creative, and the ray is
     *       unaffected, but a caller that knows its own scene should place the player itself and
     *       pass {@code approach: false}.</li>
     * </ul>
     *
     * @return the feet position, which is what a teleport takes
     */
    public double[] standingPosition() {
        switch (this.face) {
            // Straight above, looking straight down. Standing off to one side and looking across
            // would be a shallow ray, and a shallow ray does not stop at the face it was aimed at:
            // it carries on through the block and out the far side, where it can clip a part on
            // one of the other faces. A vertical ray meets the top face first and the block's
            // centre second, which is the order a caller asking for the top face means.
            case UP:
                return new double[] { this.point.x, this.pos.getY() + 1.0d, this.point.z };
            case DOWN:
                return new double[] { this.point.x, this.point.y - STANDING_DISTANCE - PlayerControl.EYE_HEIGHT,
                        this.point.z };
            // A side face is looked at from standing height, level with the block's own bottom --
            // where a player walking up to it stands. That is a shallow ray too, but it enters
            // through the aimed face, which is what decides the interaction.
            default:
                return new double[] {
                        this.point.x + this.face.getStepX() * STANDING_DISTANCE,
                        this.pos.getY(),
                        this.point.z + this.face.getStepZ() * STANDING_DISTANCE };
        }
    }

    /**
     * The yaw and pitch that look at this aim from {@link #standingPosition()}.
     *
     * Worked out from the target position rather than measured from the current one, so it can be
     * handed to the teleport itself. Aiming after the teleport is issued but before it lands would
     * compute the angles from wherever the player still is.
     *
     * @return {yaw, pitch} in degrees
     */
    public float[] lookAngles() {
        double[] feet = standingPosition();
        double dx = this.point.x - feet[0];
        double dy = this.point.y - (feet[1] + PlayerControl.EYE_HEIGHT);
        double dz = this.point.z - feet[2];
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new float[] {
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0d),
                (float) -Math.toDegrees(Math.atan2(dy, horizontal)) };
    }

    /**
     * The centre of a face of the block's <em>actual</em> shape, not of the unit cube.
     *
     * A cable's core runs 6/16 to 10/16, so the centre of the unit cube's west face is empty air: a
     * ray aimed there passes straight through, the interaction lands on nothing, and -- because the
     * click itself was delivered -- it is reported as a success that did nothing. Reading the real
     * shape costs one lookup and makes {@code --face} mean what a caller assumes it means.
     *
     * Falls back to the unit cube when a block has no shape at all, which is what an empty shape
     * used to be treated as anyway.
     */
    private static Vec3 faceCentre(BlockPos pos, Direction face) {
        AABB bounds = shapeOf(pos);
        // The two axes across the face take the shape's middle; the axis along it takes the face.
        double x = face.getStepX() == 0
                ? pos.getX() + (bounds.minX + bounds.maxX) / 2
                : pos.getX() + (face.getStepX() > 0 ? bounds.maxX : bounds.minX);
        double y = face.getStepY() == 0
                ? pos.getY() + (bounds.minY + bounds.maxY) / 2
                : pos.getY() + (face.getStepY() > 0 ? bounds.maxY : bounds.minY);
        double z = face.getStepZ() == 0
                ? pos.getZ() + (bounds.minZ + bounds.maxZ) / 2
                : pos.getZ() + (face.getStepZ() > 0 ? bounds.maxZ : bounds.minZ);
        return new Vec3(x, y, z);
    }

    /** The block's collision-independent outline, in block-local coordinates. */
    private static AABB shapeOf(BlockPos pos) {
        try {
            net.minecraft.world.phys.shapes.VoxelShape shape = ClientState.requireLevel()
                    .getBlockState(pos).getShape(ClientState.requireLevel(), pos);
            if (!shape.isEmpty()) {
                return shape.bounds();
            }
        } catch (Throwable e) {
            // A shape that cannot be asked for is not worth failing an interaction over; the unit
            // cube is what this always used to assume.
            ClientDevBridge.LOGGER.debug("Could not read the shape at {}, aiming at the full cube", pos, e);
        }
        return new AABB(0, 0, 0, 1, 1, 1);
    }

    /** The face whose plane the point lies closest to, for a caller that gave a point but no face. */
    private static Direction nearestFace(BlockPos pos, Vec3 point) {
        Vec3 offset = point.subtract(Vec3.atCenterOf(pos));
        Direction best = Direction.UP;
        double bestDistance = Double.MAX_VALUE;
        for (Direction candidate : Direction.values()) {
            Vec3 centre = faceCentre(pos, candidate).subtract(Vec3.atCenterOf(pos));
            double distance = offset.distanceTo(centre);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static Direction parseFace(String face) {
        Direction direction = Direction.byName(face.toLowerCase(java.util.Locale.ROOT));
        if (direction == null) {
            throw RpcException.invalidParams(String.format(
                    "'%s' is not a face. Expected one of: down, up, north, south, east, west.", face));
        }
        return direction;
    }

}
