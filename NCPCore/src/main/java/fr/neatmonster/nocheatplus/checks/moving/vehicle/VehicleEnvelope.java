/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import java.util.LinkedList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.location.setback.SetBackEntry;
import fr.neatmonster.nocheatplus.checks.moving.magic.LostGroundVehicle;
import fr.neatmonster.nocheatplus.checks.moving.magic.MagicVehicle;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.model.VehicleMoveData;
import fr.neatmonster.nocheatplus.checks.moving.model.VehicleMoveInfo;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.PotionUtil;
import fr.neatmonster.nocheatplus.utilities.ReflectionUtil;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.collision.Axis;
import fr.neatmonster.nocheatplus.utilities.collision.PassableAxisTracing;
import fr.neatmonster.nocheatplus.utilities.location.RichEntityLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;

/**
 * Vehicle moving envelope check, for Minecraft 1.9 and higher.
 * 
 * @author asofold
 *
 */
public class VehicleEnvelope extends Check {

    /**
     * Check specific details for re-use.
     * 
     * @author asofold
     *
     */
    public class CheckDetails {

        public boolean canClimb;
        public boolean canRails;
        public boolean canJump, canStepUpBlock; // TODO: Model as heights?
        public double maxAscend;
        public double gravityTargetSpeed;
        /** Simplified type, like BOAT, MINECART. */
        public EntityType simplifiedType; // Not sure can be kept up.
        public boolean checkAscendMuch;
        public boolean checkDescendMuch;
        /** From could be a new set back location. */
        public boolean fromIsSafeMedium;
        /** To could be a new set back location. */
        public boolean toIsSafeMedium;
        /** Interpreted differently depending on check. */
        public boolean inAir;

        public void reset() {
            canClimb = canRails = canJump = canStepUpBlock = false;
            maxAscend = 0.0;
            checkAscendMuch = checkDescendMuch = true;
            fromIsSafeMedium = toIsSafeMedium = inAir = false;
            simplifiedType = null;
            gravityTargetSpeed = MagicVehicle.boatVerticalFallTarget;
        }

    }

    /** One context per invocation, including nested checks on the same thread. */
    private static final ScopedValue<CheckContext> CONTEXT = ScopedValue.newInstance();
    private final class CheckContext {
        final List<String> tags = new LinkedList<>();
        final List<String> debugDetails = new LinkedList<>();
        final CheckDetails details = new CheckDetails();
    }

    private final Class<?> bestHorse;
    
    private final Class<?> strider;
    
    private final Class<?> camel;
    
   /*
    *
    * Instanties a new VehicleEnvelope check
    *
    */
    public VehicleEnvelope() {
        super(CheckType.MOVING_VEHICLE_ENVELOPE);
        Class<?> clazz = ReflectionUtil.getClass("org.bukkit.entity.AbstractHorse");
        bestHorse = clazz == null ? ReflectionUtil.getClass("org.bukkit.entity.Horse") : clazz;
        strider = ReflectionUtil.getClass("org.bukkit.entity.Strider");
        camel = ReflectionUtil.getClass("org.bukkit.entity.Camel");
    }


  /**
    *
    * @param player
    * @param vehicle
    * @param thisMove
    * @param isFake
    * @param data
    * @param cc
    * @param pData
    * @param moveInfo
    *
    */
    public SetBackEntry check(final Player player, final Entity vehicle, 
                              final VehicleMoveData thisMove, final boolean isFake, 
                              final MovingData data, final MovingConfig cc, 
                              final IPlayerData pData, final VehicleMoveInfo moveInfo) {

        return ScopedValue.where(CONTEXT, new CheckContext()).call(() ->
                checkMove(player, vehicle, thisMove, isFake, data, cc, pData, moveInfo));
    }

    private SetBackEntry checkMove(final Player player, final Entity vehicle,
                              final VehicleMoveData thisMove, final boolean isFake,
                              final MovingData data, final MovingConfig cc,
                              final IPlayerData pData, final VehicleMoveInfo moveInfo) {
        final boolean debug = pData.isDebugActive(type);

        // Delegate to a sub-check.
        CONTEXT.get().tags.clear();
        CONTEXT.get().tags.add("entity." + vehicle.getType());

        if (debug) {
            CONTEXT.get().debugDetails.clear();
            data.ws.setJustUsedIds(CONTEXT.get().debugDetails); // Add just used workaround ids to this list directly, for now.
        }
        // TODO: Need confine more!
        LostGroundVehicle.lostGround(vehicle, moveInfo.from, moveInfo.to, thisMove.hDistance, thisMove.yDistance, false, data.vehicleMoves.getFirstPastMove(), data, cc, null, CONTEXT.get().tags);
        final boolean violation = checkEntity(player, vehicle, thisMove, isFake, data, cc, debug, moveInfo);

        if (debug && !CONTEXT.get().debugDetails.isEmpty()) {
            debugDetails(player);
            CONTEXT.get().debugDetails.clear();
        }

        return handleViolation(player, data, cc, violation, moveInfo);
    }

    /** Boats keep their existing flight freedom; only solid block crossings are rejected. */
    public SetBackEntry checkBoatPassable(final Player player, final Entity vehicle,
            final VehicleMoveInfo moveInfo, final MovingData data, final MovingConfig cc, final IPlayerData pData) {
        return ScopedValue.where(CONTEXT, new CheckContext()).call(() -> {
            final boolean blocked = isBoatMoveBlocked(moveInfo.from, moveInfo.to);
            if (blocked) {
                CONTEXT.get().tags.add("entity." + vehicle.getType());
                CONTEXT.get().tags.add("boat_passable");
                if (pData.isDebugActive(type)) debug(player, "Boat intersects a solid block.");
            }
            else if (moveInfo.to.isPassableBox()) {
                data.vehicleSetBacks.setSafeMediumEntry(moveInfo.to);
            }
            final SetBackEntry fallback = handleViolation(player, data, cc, blocked, moveInfo);
            return blocked && fallback != null ? nearestBoatSetBack(moveInfo.from, moveInfo.to, fallback) : fallback;
        });
    }

    private SetBackEntry handleViolation(final Player player, final MovingData data, final MovingConfig cc,
            final boolean violation, final VehicleMoveInfo moveInfo) {
        if (violation) {
            data.vehicleEnvelopeVL += 1.0; // Add up one for now.
            final ViolationData vd = new ViolationData(this, player, data.vehicleEnvelopeVL, 1, cc.vehicleEnvelopeActions);
            fr.neatmonster.nocheatplus.telemetry.Telemetry.blocks(vd, moveInfo.from, moveInfo.to);
            vd.setParameter(ParameterName.TAGS, StringUtil.join(CONTEXT.get().tags, "+"));
            if (executeActions(vd).willCancel()) {
                return data.vehicleSetBacks.getValidSafeMediumEntry();
            }
        }
        else {
            data.vehicleEnvelopeVL *= 0.99; // Random cool down for now.
            // Do not set a set back here.
        }
        return null;
    }


  /**
    *
    * @param player
    */
    private void debugDetails(final Player player) {

        if (!CONTEXT.get().tags.isEmpty()) {
            CONTEXT.get().debugDetails.add("tags:");
            CONTEXT.get().debugDetails.add(StringUtil.join(CONTEXT.get().tags, "+"));
        }

        final StringBuilder builder = new StringBuilder(500);
        builder.append("Details:\n");

        for (final String detail : CONTEXT.get().debugDetails) {
            builder.append(" , ");
            builder.append(detail);
        }

        debug(player, builder.toString());
        CONTEXT.get().debugDetails.clear();
    }


  /**
    * Return the horizontal distance cap for the vehicle
    * @param type
    * @param cc
    * @param thisMove
    * @param data
    *
    */
    private double getHDistCap(final EntityType type, final MovingConfig cc, final VehicleMoveData thisMove, final MovingData data) {

        final Double cap = cc.vehicleEnvelopeHorizontalSpeedCap.get(type);
        final Double globalcap = cc.vehicleEnvelopeHorizontalSpeedCap.get(null);

        if (cap == null) {
            if(MaterialUtil.isBoat(type)){
                return getHDistCapBoats(thisMove,data,1.0,globalcap);
            }
            return globalcap;
        }
        else {
            if(MaterialUtil.isBoat(type)) {
                return getHDistCapBoats(thisMove,data,cap,globalcap);
            }
            return cap;
        }
    }
    /**
     * Return the horizontal distance cap for the boat
     * @param thisMove
     * @param data
     * @param multiplier
     *
     */
    private double getHDistCapBoats(final VehicleMoveData thisMove, final MovingData data, final double multiplier, final double globalcap) {
        if(thisMove.from.onBlueIce && !thisMove.to.onBlueIce){ //workaround for when the boat leaves icy places
            data.boatIceVelocityTicks = 20;
        }
        else if (thisMove.from.onIce && !thisMove.to.onIce){
            data.boatIceVelocityTicks = 10;
        }
        if (thisMove.from.onBlueIce || thisMove.to.onBlueIce) return multiplier * 4.1;
        if (thisMove.from.onIce || thisMove.to.onIce) return multiplier * 2.3;
        if(data.boatIceVelocityTicks-- > 0){ // allow high speed for a moment
            if (data.boatIceVelocityTicks > 10) return multiplier * 4.1;
            return multiplier * 2.3;
        }
        if ((thisMove.from.onGround && !thisMove.from.inWater) || thisMove.to.onGround && !thisMove.to.inWater) return multiplier * 0.4;
        if (thisMove.from.inWater || thisMove.to.inWater) return multiplier * 0.5;
        return multiplier == 1.0 ? globalcap : multiplier;
    }

  /**
    * Actual check
    * @param player
    * @param vehicle
    * @param thisMove
    * @param thisMove
    * @param isFake
    * @param data
    * @param cc
    * @param debug
    * @param moveInfo
    *
    * @return
    */
    private static SetBackEntry nearestBoatSetBack(final RichEntityLocation from, final RichEntityLocation to,
            final SetBackEntry fallback) {
        final double epsilon = 1.0E-7;
        final double halfWidth = from.getWidth() / 2 - epsilon;
        if (!BlockProperties.isPassableBox(from.getBlockCache(),
                from.getX() - halfWidth, from.getY() + epsilon, from.getZ() - halfWidth,
                from.getX() + halfWidth, from.getY() + from.getBoxMarginVertical() - epsilon,
                from.getZ() + halfWidth)) return fallback;
        double clear = 0.0;
        if (from.getY() != to.getY()) {
            double blocked = 1.0;
            for (int i = 0; i < 12; i++) {
                final double fraction = (clear + blocked) / 2;
                if (isBoatMoveBlocked(from, to, fraction)) blocked = fraction;
                else clear = fraction;
            }
        }
        // Stop both horizontal components together instead of deflecting a diagonal clip.
        return new SetBackEntry().set(from.getWorldName(),
                from.getX(),
                from.getY() + clear * (to.getY() - from.getY()),
                from.getZ(),
                to.getYaw(), to.getPitch(), fallback.getTime(), fallback.getMsTime());
    }

    /** Sweep the boat's body; touching a bank or leaving an existing overlap is allowed. */
    static boolean isBoatMoveBlocked(final RichEntityLocation from, final RichEntityLocation to) {
        return isBoatMoveBlocked(from, to, 1.0);
    }

    private static boolean isBoatMoveBlocked(final RichEntityLocation from, final RichEntityLocation to,
            final double fraction) {
        if (from.isSamePos(to)) return false;
        final double epsilon = 1.0E-7;
        final PassableAxisTracing trace = new PassableAxisTracing();
        trace.setMaxSteps(60);
        trace.setMargins(from.getBoxMarginVertical() - 2 * epsilon, from.getWidth() / 2 - epsilon);
        trace.setBlockCache(from.getBlockCache());
        trace.setIgnoreInitiallyColliding(true);
        try {
            // Vanilla resolves vertical motion before the horizontal axes.
            for (final List<Axis> order : List.of(Axis.AXIS_ORDER_YXZ, Axis.AXIS_ORDER_YZX)) {
                trace.setAxisOrder(order);
                trace.set(from.getX(), from.getY() + epsilon, from.getZ(),
                        from.getX() + fraction * (to.getX() - from.getX()),
                        from.getY() + fraction * (to.getY() - from.getY()) + epsilon,
                        from.getZ() + fraction * (to.getZ() - from.getZ()));
                trace.loop();
                // A traversal limit alone is not evidence of passing through a block.
                if (!trace.collides()) return false;
            }
            return true;
        }
        finally {
            trace.cleanup();
        }
    }

    private boolean checkEntity(final Player player, final Entity vehicle, 
                                final VehicleMoveData thisMove, final boolean isFake, 
                                final MovingData data, final MovingConfig cc,
                                final boolean debug, final VehicleMoveInfo moveInfo) {

        boolean violation = false;
        long now = System.currentTimeMillis();

        if (debug) {
            CONTEXT.get().debugDetails.add("inair: " + data.sfJumpPhase);
        }

        if ((moveInfo.from.getBlockFlags() & BlockFlags.F_BUBBLECOLUMN) != 0
            // Should use BlockTraceTracker instead blind leniency
            //|| (isBouncingBlock(moveInfo.from) && thisMove.yDistance >= 0.0 && thisMove.yDistance <= 1.0)
            ) {
            data.timeVehicletoss = System.currentTimeMillis();
        }

        // Medium dependent checking.
        // TODO: Try pigs on layered snow. Consider actual bounding box / lost-ground / ...
        // Maximum thinkable horizontal speed.
        // TODO: Further distinguish, best set in CheckDetails.
        if (vehicle instanceof LivingEntity) {
            Double speed = PotionUtil.getPotionEffectAmplifier((LivingEntity)vehicle, PotionEffectType.SPEED);
            // The most terrible code I ever written due to poor infrastructure. Should be thinking of recode the vehicle check after the hspeed refactor
            if (camel != null && camel.isAssignableFrom(vehicle.getClass())) {
                final VehicleMoveData firstPastMove = data.vehicleMoves.getFirstPastMove();
                double cap = getHDistCap(CONTEXT.get().details.simplifiedType, cc, thisMove, data);
                if (!Double.isInfinite(speed)) cap *= (1 + 0.2 * (speed + 1));
                if (thisMove.hDistance > cap) {
                    final long current = System.currentTimeMillis();
                    // TODO: This delay belong to the entity they are riding, not on player!
                    if (data.timeCamelDash + 2000 < current) {
                        data.timeCamelDash = current;
                        return maxDistHorizontal(thisMove, cap * 2.7);
                    } else {
                        return maxDistHorizontal(thisMove, firstPastMove.toIsValid ? (firstPastMove.hDistance * 0.98) : cap);
                    }
                }
            }
            if (!Double.isInfinite(speed)) {
                if (maxDistHorizontal(thisMove, getHDistCap(CONTEXT.get().details.simplifiedType, cc, thisMove, data) * (1 + 0.2 * (speed + 1)))) {
                    return true;
                }
            } 
            else if (maxDistHorizontal(thisMove, getHDistCap(CONTEXT.get().details.simplifiedType, cc, thisMove,data) + (CONTEXT.get().details.canJump ? 0.18 : 0.0))) {
                return true;
            }
        } 
        else if (maxDistHorizontal(thisMove, getHDistCap(CONTEXT.get().details.simplifiedType, cc, thisMove,data))) { // Override type for now.
            return true;
        }

        // TODO: Could limit descend by 2*maxDescend, ascend by much less
        // TODO: Split code to methods.
        // TODO: Get extended liquid specs (allow confine to certain flags, here: water). Contains info if water is only flowing down, surface properties (non liquid blocks?), still water.
        if (thisMove.from.inWeb) {
            // TODO: Check anything?
            if (debug) {
                CONTEXT.get().debugDetails.add("");
            }
            //            if (thisMove.yDistance > 0.0) {
            //                CONTEXT.get().tags.add("ascend_web");
            //                return true;
            //            }
            // TODO: Enforce not ascending ?
            // TODO: max speed.
        }
        else if (CONTEXT.get().details.canClimb && thisMove.from.onClimbable) {
            // TODO: Order.
            CONTEXT.get().details.checkAscendMuch = CONTEXT.get().details.checkDescendMuch = false;
            if (Math.abs(thisMove.yDistance) > MagicVehicle.climbSpeed) {
                violation = true;
                CONTEXT.get().tags.add("climbspeed");
            }
        }
        else if (CONTEXT.get().details.canRails && thisMove.fromOnRails) {
            // TODO: Might invert to trigger violation if exceeds distance (always disable a/d_much).
            if (Math.abs(thisMove.yDistance) < MagicVehicle.maxRailsVertical) {
                CONTEXT.get().details.checkAscendMuch = CONTEXT.get().details.checkDescendMuch = false;
            }
        }
        else if (thisMove.from.inWater && thisMove.to.inWater) {
            // Default in-medium move.
            if (debug) {
                CONTEXT.get().debugDetails.add("water-water");
            }
            // TODO: Should still cover extreme moves here.

            // Special case moving up after falling.
            // TODO: Check past moves for falling (not yet available).
            // TODO: Check if the target location somehow is the surface.
            if (MagicVehicle.oddInWater(thisMove, CONTEXT.get().details, data)) {
                // (Assume players can't control sinking boats for now.)
                CONTEXT.get().details.checkDescendMuch = CONTEXT.get().details.checkAscendMuch = false;
                violation = false;
            }
        }
        else if (thisMove.from.onGround && thisMove.to.onGround) {
            // Default on-ground move.
            // TODO: Should still cover extreme moves here.
            if (CONTEXT.get().details.canStepUpBlock && thisMove.yDistance > 0.0 && thisMove.yDistance <= 1.0) {
                CONTEXT.get().details.checkAscendMuch = false;
                CONTEXT.get().tags.add("step_up");
            }
            if (thisMove.from.onBlueIce && thisMove.to.onBlueIce) {
                // Default on-blueIce move.
                if (debug) {
                    CONTEXT.get().debugDetails.add("blueIce-blueIce");
                }
                // TODO: Should still cover extreme moves here.
            }
            else if (thisMove.from.onIce && thisMove.to.onIce) {
                // Default on-ice move.
                if (debug) {
                    CONTEXT.get().debugDetails.add("ice-ice");
                }
                // TODO: Should still cover extreme moves here.
            }
            else {
                // (TODO: actually a default on-ground move.)
                if (debug) {
                    CONTEXT.get().debugDetails.add("ground-ground");
                }
            }
        }
        else if (CONTEXT.get().details.inAir) {
            // In-air move.
            if (checkInAir(thisMove, data, debug, vehicle, moveInfo)) {
                violation = true;
            }
        }
        else {
            // Some transition to probably handle.
            if (debug) {
                CONTEXT.get().debugDetails.add("?-?");
            }
            // TODO: Lift off speed etc.
            // TODO: Clearly overlaps other cases.
            // TODO: Skipped vehicle move events happen here as well (...).
            if (!CONTEXT.get().details.toIsSafeMedium) {
                // TODO: At least do something here?
            }
        }

        if (vehicle instanceof LivingEntity) {
            Double levitation = Bridge1_9.getLevitationAmplifier((LivingEntity)vehicle);
            if (!Double.isInfinite(levitation)) {
                CONTEXT.get().details.maxAscend += 0.046 * (levitation + 1);
                violation = false;
            }
        }

        // Maximum ascend speed.
        if (CONTEXT.get().details.checkAscendMuch && thisMove.yDistance > CONTEXT.get().details.maxAscend) {
            CONTEXT.get().tags.add("ascend_much");
            violation = true;
        }
        
        // Workaround
        if (data.timeVehicletoss + 2000 > now && thisMove.yDistance < 4.0) {
            violation = false;
        }
        
        // Maximum descend speed.
        if (CONTEXT.get().details.checkDescendMuch && thisMove.yDistance < -MagicVehicle.maxDescend) {
            // TODO: At times it looks like one move is skipped, resulting in double distance ~ -5 and at the same time 'vehicle moved too quickly'. 
            // TODO: Test with log this to console to see the order of things.
            CONTEXT.get().tags.add("descend_much");
            violation = true;
        }

        if (vehicle instanceof LivingEntity) {
            final VehicleMoveData firstPastMove = data.vehicleMoves.getFirstPastMove();
            
            if (thisMove.hDistance > 0.1D && thisMove.yDistance == 0D && !thisMove.to.onGround && !thisMove.from.onGround
                && firstPastMove.valid && firstPastMove.yDistance == 0D 
                && thisMove.to.inLiquid && thisMove.from.inLiquid
                && !thisMove.headObstructed 
                && !((strider != null && strider.isAssignableFrom(vehicle.getClass())) && thisMove.to.inLava && thisMove.from.inLava) // The strider can walk on lava
                ) {
                violation = true;
                CONTEXT.get().tags.add("liquidwalk");
            }

            Material blockUnder = vehicle.getLocation().subtract(0, 0.3, 0).getBlock().getType();
            Material blockAbove = vehicle.getLocation().add(0, 0.10, 0).getBlock().getType();
            if (blockUnder != null && blockAbove != null && BlockProperties.isAir(blockAbove)
                && BlockProperties.isLiquid(blockUnder) && !(strider != null && strider.isAssignableFrom(vehicle.getClass()))
                ) {
                if (thisMove.hDistance > 0.11D && thisMove.yDistance <= 0.1D && !thisMove.to.onGround && !thisMove.from.onGround
                    && firstPastMove.valid && firstPastMove.yDistance == thisMove.yDistance || firstPastMove.yDistance == thisMove.yDistance * -1 
                    && firstPastMove.yDistance != 0D
                    && !thisMove.headObstructed) {

                    // Prevent being flagged if a vehicle transitions from a block to water and the player falls into the water.
                    if (!(thisMove.yDistance < 0 && thisMove.yDistance != 0 && firstPastMove.yDistance < 0 && firstPastMove.yDistance != 0)) {
                        violation = true;
                        CONTEXT.get().tags.add("liquidmove");
                    }
                }
            }
        }

        if (!violation) {
            // No violation.
            // TODO: sfJumpPhase is abused for in-air move counting here.
            if (CONTEXT.get().details.inAir) {
                data.sfJumpPhase ++;
            }
            else {
                // Adjust set back.
                if (CONTEXT.get().details.toIsSafeMedium) {
                    data.vehicleSetBacks.setSafeMediumEntry(thisMove.to);
                    data.sfJumpPhase = 0;
                }
                else if (CONTEXT.get().details.fromIsSafeMedium) {
                    data.vehicleSetBacks.setSafeMediumEntry(thisMove.from);
                    data.sfJumpPhase = 0;
                }
                // Reset the resetNotInAir workarounds.
                data.ws.resetConditions(WRPT.G_RESET_NOTINAIR);
            }
            data.vehicleSetBacks.setLastMoveEntry(thisMove.to);
        }

        return violation;
    }
    

  /**
    * @param from
    *
    */
    private boolean isBouncingBlock(RichEntityLocation from) {
        return (from.getBlockFlags() & BlockFlags.F_BOUNCE25) != 0;
    }


   /**
     * Prepare CONTEXT.get().details according to vehicle-specific interpretation of side
     * conditions.
     * 
     * @param vehicle
     * @param moveInfo Cheating.
     * @param thisMove
     */
    protected void prepareCheckDetails(final Entity vehicle, final VehicleMoveInfo moveInfo, final VehicleMoveData thisMove) {

        CONTEXT.get().details.reset();
        // TODO: These properties are for boats, might need to distinguish further.
        CONTEXT.get().details.fromIsSafeMedium = thisMove.from.inWater || thisMove.from.onGround || thisMove.from.inWeb;
        CONTEXT.get().details.toIsSafeMedium = thisMove.to.inWater || thisMove.to.onGround || thisMove.to.inWeb;
        CONTEXT.get().details.inAir = !CONTEXT.get().details.fromIsSafeMedium && !CONTEXT.get().details.toIsSafeMedium;
        // Distinguish by entity class (needs future proofing at all?).
        if (vehicle != null && MaterialUtil.isBoat(vehicle.getType())) {
            CONTEXT.get().details.simplifiedType = EntityType.BOAT;
            CONTEXT.get().details.maxAscend = MagicVehicle.maxAscend;
        }
        else if (vehicle instanceof Minecart) {
            CONTEXT.get().details.simplifiedType = EntityType.MINECART;
            // Bind to rails.
            CONTEXT.get().details.canRails = true;
            thisMove.setExtraMinecartProperties(moveInfo); // Cheating.
            if (thisMove.fromOnRails) {
                CONTEXT.get().details.fromIsSafeMedium = true;
                CONTEXT.get().details.inAir = false;
            }

            if (thisMove.toOnRails) {
                CONTEXT.get().details.toIsSafeMedium = true;
                CONTEXT.get().details.inAir = false;
            }
            CONTEXT.get().details.gravityTargetSpeed = 0.79;
        }
        else if (bestHorse != null && bestHorse.isAssignableFrom(vehicle.getClass())) {
            // TODO: Climbable? -> seems not.
            CONTEXT.get().details.simplifiedType = EntityType.HORSE; // TODO: 1.11 - Use AbstractHorse?
            CONTEXT.get().details.canJump = CONTEXT.get().details.canStepUpBlock = true;
        }
        else if (strider != null && strider.isAssignableFrom(vehicle.getClass())) {
            //CONTEXT.get().details.simplifiedType = EntityType.PIG;
            CONTEXT.get().details.canJump = false;
            CONTEXT.get().details.canStepUpBlock = true;
            CONTEXT.get().details.canClimb = true;
            // Step problem
            CONTEXT.get().details.maxAscend = 1.1;
            // Fall in lava
            if (thisMove.from.inLava || thisMove.to.inLava) CONTEXT.get().details.inAir = false;
            // ....
            if (!thisMove.from.onGround && thisMove.to.onGround) CONTEXT.get().details.gravityTargetSpeed = 0.07;
            // Updated by PlayerMoveEvent, hdist fps when a player want to ride on strider
        }
        else if (camel != null && camel.isAssignableFrom(vehicle.getClass())) {
            CONTEXT.get().details.canStepUpBlock = true;
            CONTEXT.get().details.canClimb = false;
            CONTEXT.get().details.canJump = false;
        }
        else if (vehicle instanceof Pig) {
            // TODO: Climbable!
            CONTEXT.get().details.simplifiedType = EntityType.PIG;
            CONTEXT.get().details.canJump = false;
            CONTEXT.get().details.canStepUpBlock = true;
            CONTEXT.get().details.canClimb = true;
        }
        else {
            CONTEXT.get().details.simplifiedType = thisMove.vehicleType;
        }

        // Generic settings.
        // (maxAscend is not checked for stepping up blocks)
        if (CONTEXT.get().details.canJump) {
            CONTEXT.get().details.maxAscend = 1.2; // Coarse envelope. Actual lift off gain should be checked on demand.
        }

        // Climbable
        if (CONTEXT.get().details.canClimb) {
            if (thisMove.from.onClimbable) {
                CONTEXT.get().details.fromIsSafeMedium = true;
                CONTEXT.get().details.inAir = false;
            }

            if (thisMove.to.onClimbable) {
                CONTEXT.get().details.toIsSafeMedium = true;
                CONTEXT.get().details.inAir = false;
            }
        }
    }


    /**
     * Generic in-air check.
     * @param thisMove
     * @param data
     * @return
     */
    private boolean checkInAir(final VehicleMoveData thisMove, final MovingData data,
                               final boolean debug, final Entity vehicle, final VehicleMoveInfo moveInfo) {

        final RichEntityLocation from = moveInfo.from;
        final RichEntityLocation to = moveInfo.to;

        // TODO: Distinguish sfJumpPhase and inAirDescendCount (after reaching the highest point).

        if (debug) {
            CONTEXT.get().debugDetails.add("air-air");
        }

        if (CONTEXT.get().details.canJump) {
            // TODO: Max. y-distance to set back.
            // TODO: Friction.
        }
        else {
            if (thisMove.yDistance > 0.0) {
                CONTEXT.get().tags.add("ascend_at_all");
                return true;
            }
        }

        boolean violation = false;
        // Absolute vertical distance to set back.
        // TODO: Add something like this.
        //            final double setBackYdistance = to.getY() - data.vehicleSetBacks.getValidSafeMediumEntry().getY();
        //            if (data.sfJumpPhase > 4) {
        //                double estimate = Math.min(2.0, MagicVehicle.boatGravityMin * ((double) data.sfJumpPhase / 4.0) * ((double) data.sfJumpPhase / 4.0 + 1.0) / 2.0);
        //                if (setBackYdistance > -estimate) {
        //                    CONTEXT.get().tags.add("slow_fall_vdistsb");
        //                    return true;
        //                }
        //            }
        // Enforce falling speed (vdist) envelope by in-air phase count.
        // Slow falling (vdist), do not bind to descending in general.
        final double minDescend = -(thisMove.yDistance < -MagicVehicle.boatLowGravitySpeed ? 
                                    MagicVehicle.boatGravityMinAtSpeed : MagicVehicle.boatGravityMin) * 
                                    (CONTEXT.get().details.canJump ? Math.max(data.sfJumpPhase - MagicVehicle.maxJumpPhaseAscend, 0) : data.sfJumpPhase);                     
        final double maxDescend = getInAirMaxDescend(thisMove, data);

        if (data.sfJumpPhase > (CONTEXT.get().details.canJump ? MagicVehicle.maxJumpPhaseAscend : 1)
            && thisMove.yDistance > Math.max(minDescend, -CONTEXT.get().details.gravityTargetSpeed)) {
            
            boolean noViolation = ColliesHoneyBlock(from) 
                    || (vehicle instanceof LivingEntity && !Double.isInfinite(Bridge1_13.getSlowfallingAmplifier((LivingEntity)vehicle)))
                    || !vehicle.hasGravity();
            // TODO: What is this? Vehicle slide on honey block?
            if (ColliesHoneyBlock(from)) data.sfJumpPhase = 5;

            if (!noViolation) {
                CONTEXT.get().tags.add("slow_fall_vdist");
                violation = true;
            }
        }
        // Fast falling (vdist).
        else if (data.sfJumpPhase > 1 && thisMove.yDistance < maxDescend) {
            // TODO: Allow one skipped move per jump phase (1, 2, 3).
            CONTEXT.get().tags.add("fast_fall_vdist");
            violation = true;
        }
        if (violation) {
            // Post violation detection workarounds.
            if (MagicVehicle.oddInAir(thisMove, minDescend, maxDescend, CONTEXT.get().details, data)) {
                violation = false;
                CONTEXT.get().details.checkDescendMuch = CONTEXT.get().details.checkAscendMuch = false; // (Full envelope has been checked.)
            }

            if (debug) {
                CONTEXT.get().debugDetails.add("maxDescend: " + maxDescend);
            }
        }
        return violation;
    }


  /**
    * @param thisMove
    * @param data
    *
    */
    private double getInAirMaxDescend(final PlayerMoveData thisMove, final MovingData data) {

        double maxDescend = -MagicVehicle.boatGravityMax * data.sfJumpPhase - 0.5;
        final VehicleMoveData firstPastMove = data.vehicleMoves.getFirstPastMove();

        if (thisMove.yDistance < maxDescend && firstPastMove.toIsValid) {
            if (firstPastMove.yDistance < maxDescend && firstPastMove.yDistance > maxDescend * 2.5) {
                // Simply continue with friction.
                maxDescend = Math.min(maxDescend, firstPastMove.yDistance - (MagicVehicle.boatGravityMax + MagicVehicle.boatGravityMin) / 2.0);
                CONTEXT.get().debugDetails.add("desc_frict");
            }
            else if (firstPastMove.specialCondition && thisMove.yDistance > -1.5) {
                // After special set-back confirm move, observed ca. -1.1.
                maxDescend = Math.min(maxDescend, -1.5);
                CONTEXT.get().debugDetails.add("desc_special");
            }
        }
        return maxDescend;
    }
    

  /**
    * @param thisMove
    * @param maxDistanceHorizontal
    *
    */
    private boolean maxDistHorizontal(final VehicleMoveData thisMove, final double maxDistanceHorizontal) {
        if (thisMove.hDistance > maxDistanceHorizontal) {
            CONTEXT.get().tags.add("hdist");
            return true;
        }
        else {
            return false;
        }
    }


  /**
    * @param from
    *
    */
    private boolean ColliesHoneyBlock(RichEntityLocation from) {
        return (from.getBlockFlags() & BlockFlags.F_STICKY) != 0;
    }
}
