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
package fr.neatmonster.nocheatplus.checks.moving.player;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import fr.neatmonster.nocheatplus.compat.Folia;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.TurtleEgg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.checks.moving.model.LocationData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.BridgeEnchant;
import fr.neatmonster.nocheatplus.compat.BridgeHealth;
import fr.neatmonster.nocheatplus.compat.BridgeMaterial;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.ReflectionUtil;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

/**
 * A check to see if people cheat by tricking the server to not deal them fall damage.
 */
public class NoFall extends Check {

    /*
     * TODO: Due to farmland/soil not converting back to dirt with the current
     * implementation: Implement packet sync with moving events. Then alter
     * packet on-ground and mc fall distance for a new default concept. As a
     * fall back either the old method, or an adaption with scheduled/later fall
     * damage dealing could be considered, detecting the actual cheat with a
     * slight delay. Packet sync will need a better tracking than the last n
     * packets, e.g. include the latest/oldest significant packet for (...) and
     * if a packet has already been related to a Bukkit event.
     */



    /**
     * Instantiates a new no fall check.
     */
    public NoFall() {
        super(CheckType.MOVING_NOFALL);
    }


    /**
     * Calculate the damage in hearts from the given fall distance.
     * @param fallDistance
     * @return
     */
    public static final double getDamage(final float fallDistance) {
        final double damage = fallDistance - Magic.FALL_DAMAGE_DIST;
        return ServerVersion.compareMinecraftVersion("26.1") >= 0 ? Math.floor(damage + 1.0e-6) : damage;
    }


    public static double getDamage(final Player player, final float fallDistance) {
        if (ServerVersion.compareMinecraftVersion("26.1") < 0) return getDamage(fallDistance);
        final double safe = fallAttribute(player, "SAFE_FALL_DISTANCE", "GENERIC_SAFE_FALL_DISTANCE", Magic.FALL_DAMAGE_DIST);
        final double multiplier = fallAttribute(player, "FALL_DAMAGE_MULTIPLIER", "GENERIC_FALL_DAMAGE_MULTIPLIER", 1.0);
        return Math.floor((fallDistance + 1.0e-6 - safe) * multiplier);
    }

    private static double fallAttribute(Player player, String name, String oldName, double fallback) {
        for (String candidate : new String[] {name, oldName}) {
            try {
                Attribute attribute = (Attribute) Attribute.class.getField(candidate).get(null);
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) return instance.getValue();
            } catch (ReflectiveOperationException unavailable) { /* Older Bukkit API. */ }
        }
        return fallback;
    }

    /**
     * Deal damage if appropriate. To be used for if the player is on ground
     * somehow. Contains checking for skipping conditions (getAllowFlight set +
     * configured to skip).
     * 
     * @param mcPlayer
     * @param data
     * @param y
     * @param previousSetBackY
     *            The set back y from lift-off. If not present:
     *            Double.NEGATIVE_INFINITY.
     */
    private void handleOnGround(final Player player, final double y, final double previousSetBackY,
                                final boolean reallyOnGround, final MovingData data, final MovingConfig cc,
                                final IPlayerData pData) {

        // Damage to be dealt.
        float fallDist = (float) getApplicableFallHeight(player, y, previousSetBackY, data);
        // TODO: Might want to clean up NoFall to only track for ground state and override as mention above 
        // (save performance, less code but packet dependent and precise ground state requirement)
        // or still maintain it
        // (lot of tracking stuffs for impulse, change blocks on land, damage recalculation -> degraded efficiency but packet independent
        if (fallDist - Magic.FALL_DAMAGE_DIST > 0.0 && data.noFallCurrentLocOnWindChargeHit != null) {
            final double lastImpluseY = data.noFallCurrentLocOnWindChargeHit.getY();
            data.clearWindChargeImpulse();
            fallDist = (float) (lastImpluseY < y ? 0.0 : lastImpluseY - y);
        }
        double maxD = getDamage(player, fallDist);
        maxD = calcDamagewithfeatherfalling(player, calcReducedDamageByBlock(player, data, maxD), 
                                            mcAccess.getHandle().dealFallDamageFiresAnEvent().decide());
        fallOn(player, fallDist);

        if (maxD >= Magic.FALL_DAMAGE_MINIMUM) {
            // Check skipping conditions.
            if (cc.noFallSkipAllowFlight && player.getAllowFlight()) {
                data.clearNoFallData();
                data.noFallSkipAirCheck = true;
                // Not resetting the fall distance here, let Minecraft or the issue tracker deal with that.
            }
            else {
                // TODO: more effects like sounds, maybe use custom event with violation added.
                if (pData.isDebugActive(type)) {
                    debug(player, "NoFall deal damage" + (reallyOnGround ? "" : "violation") + ": " + maxD);
                }
                // TODO: might not be necessary: if (mcPlayer.invulnerableTicks <= 0)  [no damage event for resetting]
                // TODO: Detect fake fall distance accumulation here as well.
                // Consume this landing before synchronous callbacks. Native damage
                // may be suppressed by cooldown or cancelled without firing our
                // damage listener; neither permits replay on later ground moves.
                data.clearNoFallData();
                data.clearGlideDebt();
                if (Folia.isFoliaServer()) FallStateSnapshot.clear(player);
                data.noFallSkipAirCheck = true;
                dealFallDamage(player, maxD);
            }
        }
        else {
            data.clearNoFallData();
            data.clearGlideDebt();
            player.setFallDistance(0);
        }
    }


    /**
     * Change state of some blocks when they fall on like Farmland
     * 
     * @param player
     * @param fallDist
     * @return if allow to change the block
     */
    private void fallOn(final Player player, final double fallDist) {

        // TODO: Need move data pTo, this location isn't updated
        // Each region can process a different player concurrently.
        Block block = player.getLocation().subtract(0.0, 1.0, 0.0).getBlock();
        if (block.getType() == BridgeMaterial.FARMLAND && fallDist > 0.5 && ThreadLocalRandom.current().nextFloat() < fallDist - 0.5) {
            final BlockState newState = block.getState();
            newState.setType(Material.DIRT);
            //if (Bridge1_13.hasIsSwimming()) newState.setBlockData(Bukkit.createBlockData(newState.getType()));
            if (canChangeBlock(player, block, newState, true, true, true)) {
                // Move up a little bit in order not to stuck in a block
                player.setVelocity(new Vector(player.getVelocity().getX() * -1, 0.062501, player.getVelocity().getZ() * -1));
                block.setType(Material.DIRT);
            }
            return;
        }
        if (Bridge1_13.hasIsSwimming() && block.getType() == Material.TURTLE_EGG && ThreadLocalRandom.current().nextInt(3) == 0) {
            final TurtleEgg egg = (TurtleEgg) block.getBlockData();
            final BlockState newState = block.getState();
            if (canChangeBlock(player, block, newState, true, false, false)) {
                if (egg.getEggs() - 1 > 0) {
                    egg.setEggs(egg.getEggs() - 1);
                } else block.setType(Material.AIR);
            }
        }
    }
    

    /**
     * Fire events to see if other plugins allow to change the block
     * 
     * @param player
     * @param block
     * @param newState the BlockState of new block
     * @param interact if fire PlayerInteractEvent
     * @param entityChangeBlock if fire EntityChangeBlockEvent
     * @param fade if fire BlockFadeEvent
     * @return if can change the block
     */
    private boolean canChangeBlock(final Player player, final Block block, final BlockState newState,
            final boolean interact, final boolean entityChangeBlock, final boolean fade) {

        if (interact) {
            final PlayerInteractEvent interactevent = new PlayerInteractEvent(player, Action.PHYSICAL, null, block, BlockFace.SELF);
            Bukkit.getPluginManager().callEvent(interactevent);
            if (interactevent.isCancelled()) return false;
        }

        if (entityChangeBlock) {
            if (!Bridge1_13.hasIsSwimming()) {
                // 1.6.4-1.12.2 backward compatibility
                Object o = ReflectionUtil.newInstance(
                    ReflectionUtil.getConstructor(EntityChangeBlockEvent.class, Entity.class, Block.class, Material.class, byte.class),
                    player, block, Material.DIRT, (byte)0
                );
                if (o instanceof EntityChangeBlockEvent) {
                    EntityChangeBlockEvent event = (EntityChangeBlockEvent)o;
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) return false;
                }
            } 
            else {
                final EntityChangeBlockEvent blockevent = new EntityChangeBlockEvent(player, block, newState.getBlockData()); 
                Bukkit.getPluginManager().callEvent(blockevent);
                if (blockevent.isCancelled()) return false;
            }
        }

        // Not fire on 1.8 below
        if (fade && Bridge1_9.hasGetItemInOffHand()) {
            final BlockState newstate = block.getState();
            newstate.setType(Material.DIRT);
            final BlockFadeEvent fadeevent = new BlockFadeEvent(block, newstate);
            Bukkit.getPluginManager().callEvent(fadeevent);
            if (fadeevent.isCancelled()) return false;
        }
        return true;
    }


    /**
     * Correct fall damage according to the feather fall enchant
     * 
     * @param player
     * @param damage
     * @param active
     * @return corrected fall damage
     */
    public static double calcDamagewithfeatherfalling(Player player, double damage, boolean active) {

        if (active) return damage;
        if (BridgeEnchant.hasFeatherFalling() && damage > 0.0) {
            int levelench = BridgeEnchant.getFeatherFallingLevel(player);
            if (levelench > 0) {
                int tmp = levelench * 3;
                if (tmp > 20) tmp = 20;
                return damage * (1.0 - tmp / 25.0);
            }
        }
        return damage;
    }
    

    /**
     * Reduce the fall damage if the player lands on a specific block
     * 
     * @param player
     * @param data
     * @param damage
     * @return reduced damage
     */
    public static double calcReducedDamageByBlock(final Player player, final MovingData data,final double damage) {

        // Current accepted destination during movement; actual entity position for
        // native damage callbacks. Historical support can be stale after a transfer.
        Location landing = player.getLocation();
        final PlayerMoveData current = data.playerMoves.getCurrentMove();
        if (current != null && current.toIsValid) {
            Location destination = new Location(landing.getWorld(), current.to.getX(),
                    current.to.getY(), current.to.getZ());
            if (Folia.isOwnedByCurrentRegion(destination)) landing = destination;
        }
        final Material feet = landing.getBlock().getType();
        final Material support = landing.clone().subtract(0.0, 0.2, 0.0).getBlock().getType();
        if (BlockProperties.isPowderSnow(feet) || BlockProperties.isPowderSnow(support)) return 0.0;
        double reduced = damage;
        if ((BlockFlags.getBlockFlags(support) & BlockFlags.F_STICKY) != 0
                || Bridge1_9.hasEndRod() && support == Material.HAY_BLOCK) reduced /= 5.0;
        else if (ServerVersion.compareMinecraftVersion("1.12") >= 0 && MaterialUtil.BEDS.contains(support)) reduced /= 2.0;
        // 26.x floors after the block and attribute multipliers (LivingEntity.calculateFallDamage).
        return ServerVersion.compareMinecraftVersion("26.1") >= 0 ? Math.floor(reduced) : reduced;
    }


    /**
     * Estimate the applicable fall height for the given data.
     * 
     * @param player
     * @param y
     * @param previousSetBackY
     *            The set back y from lift-off. If not present:
     *            Double.NEGATIVE_INFINITY.
     * @param data
     * @return
     */
    private static double getApplicableFallHeight(final Player player, final double y, final double previousSetBackY, final MovingData data) {

        //return getDamage(Math.max((float) (data.noFallMaxY - y), Math.max(data.noFallFallDistance, player.getFallDistance())));
        final double yDistance = Math.max(data.noFallMaxY - y, data.noFallFallDistance);
        if (ServerVersion.compareMinecraftVersion("26.1") < 0
            && yDistance > 0.0 && data.jumpAmplifier > 0.0
            && previousSetBackY != Double.NEGATIVE_INFINITY) {
            // Fall height counts below previous set-back-y.
            // TODO: Likely updating the amplifier after lift-off doesn't make sense.
            // TODO: In case of velocity... skip too / calculate max exempt height?
            final double correction = data.noFallMaxY - previousSetBackY;
            if (correction > 0.0) {
                final float effectiveDistance = (float) Math.max(0.0, yDistance - correction);
                return effectiveDistance;
            }
        }
        return yDistance;
    }


    public static double getApplicableFallHeight(final Player player, final double y, final MovingData data) {
        return getApplicableFallHeight(player, y, 
                data.hasSetBack() ? data.getSetBackY() : Double.NEGATIVE_INFINITY, data);
    }


    /**
     * Test if fall damage would be dealt accounting for the given data.
     * 
     * @param player
     * @param y
     * @param previousSetBackY
     *            The set back y from lift-off. If not present:
     *            Double.NEGATIVE_INFINITY.
     * @param data
     * @return
     */
    public boolean willDealFallDamage(final Player player, final double y, 
                                      final double previousSetBackY, final MovingData data) {

        return getDamage((float) getApplicableFallHeight(player, y, previousSetBackY, data))
                                 - Magic.FALL_DAMAGE_DIST >= Magic.FALL_DAMAGE_MINIMUM;
    }

    /**
     * 
     * @param player
     * @param minY
     * @param reallyOnGround
     * @param data
     * @param cc
     */
    private void adjustFallDistance(final Player player, final double minY, final boolean reallyOnGround, 
                                    final MovingData data, final MovingConfig cc) {

        // Debt kept for a native reset this check caused (cancelled airborne
        // FALL event, own set-back, denied contact) is owed at the first
        // contact, also when the move that reaches it starts on that support.
        // A pending landing confirmation is not owed descent: the movement
        // listener settles it (resolveHandoff) before this check runs.
        final float landingDistance = Math.max(retainedNativeResetDebt(data),
                Math.max(data.noFallFallDistance, (float) (data.noFallMaxY - minY)));
        float noFallFallDistance = landingDistance;
        if (ServerVersion.compareMinecraftVersion("26.1") >= 0) {
            // PlayerMoveEvent precedes the native collision/fall-distance update.
            // Restore the distance before this packet; vanilla adds its descent.
            final PlayerMoveData current = data.playerMoves.getCurrentMove();
            if (current != null && current.toIsValid && current.yDistance < 0) {
                noFallFallDistance = Math.max(0f, noFallFallDistance + (float) current.yDistance);
            }
        }
        boolean restored = false;
        if (landingDistance >= Magic.FALL_DAMAGE_DIST) {
            final float fallDistance = player.getFallDistance();

            if (noFallFallDistance - fallDistance >= 0.5f // TODO: Why not always adjust, if greater?
                || landingDistance >= Magic.FALL_DAMAGE_DIST
                && fallDistance < Magic.FALL_DAMAGE_DIST // Ensure damage.
                ) {
                player.setFallDistance(noFallFallDistance);
                restored = true;
            }
        }
        data.clearNoFallDescent();
        data.clearGlideDebt();
        if (restored && ServerVersion.compareMinecraftVersion("26.1") >= 0
                && getDamage(player, landingDistance) >= Magic.FALL_DAMAGE_MINIMUM) {
            // Native distance was reset by something this check did not accept.
            // Restoring it is only a request: native can still revert this
            // landing packet (tiny upward packet before the contact). The first
            // owner observation after native processing settles it
            // (resolveHandoff); a FALL event clears it.
            final PlayerMoveData move = data.playerMoves.getCurrentMove();
            final Location at = move != null && move.toIsValid ? null : player.getLocation();
            final boolean fromContact = at == null && move.to.getY() > move.from.getY();
            data.noFallHandoffDistance = landingDistance;
            data.noFallHandoffX = at != null ? at.getX() : fromContact ? move.from.getX() : move.to.getX();
            data.noFallHandoffY = minY;
            data.noFallHandoffZ = at != null ? at.getZ() : fromContact ? move.from.getZ() : move.to.getZ();
            data.noFallHandoffSilent = nativeLandingMayBeSilent(player, data, landingDistance);
        }
        // Force damage on event fire, no need air checking!
        // TODO: Later on use deal damage and override on ground at packet level
        // (don't have to calculate reduced damage or account for block change things)
        data.noFallSkipAirCheck = true;
    }


    /**
     * Whether native can process this restored landing without a FALL event.
     * LivingEntity.hurtServer returns before the event while the damage cooldown
     * blocks it (invulnerableTime > 10 and damage <= lastHurt), for an
     * invulnerable or dead player; causeFallDamage sends nothing at zero damage
     * (landing block, current impulse). Native processes the landing in this
     * same packet, so this state is the one native uses. Any other processed
     * landing fires the event, which consumes the confirmation itself.
     */
    private boolean nativeLandingMayBeSilent(final Player player, final MovingData data, final float distance) {
        if (player.isInvulnerable() || player.isDead()) return true;
        if (mcAccess != null && mcAccess.getHandle().isIgnoringFallDamageFromCurrentImpulse(player)) return true;
        double damage = getDamage(player, distance);
        try {
            damage = calcReducedDamageByBlock(player, data, damage);
        } catch (RuntimeException unavailable) {
            // Unreduced is the stricter value: fewer landings count as silent.
        }
        if (damage < Magic.FALL_DAMAGE_MINIMUM) return true;
        return player.getNoDamageTicks() > player.getMaximumNoDamageTicks() / 2 && damage <= player.getLastDamage();
    }

    /**
     * A boat, minecart, or other collision box under the feet. Vanilla still
     * applies fall damage for that landing. The block-only ground test does not
     * see it, and cancelling the event lets the server wipe the distance.
     */
    public static boolean boxSupportsFeet(final double x, final double y, final double z,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        return boxSupportsFeet(x, y, z, 0.3, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static boolean boxSupportsFeet(final double x, final double y, final double z, final double halfWidth,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        if (halfWidth <= 0 || maxX <= minX || maxZ <= minZ || maxY <= minY) return false;
        // Require top-face contact and a positive player-footprint overlap.
        if (Math.abs(y - maxY) > 0.00001) return false;
        if (x + halfWidth <= minX || x - halfWidth >= maxX) return false;
        if (z + halfWidth <= minZ || z - halfWidth >= maxZ) return false;
        return true;
    }

    /**
     * A server-confirmed glide uses vanilla fall-distance accounting immediately.
     * Deploy duration is not a vanilla fall-damage rule. In particular, never
     * resurrect the pre-deploy descent when a legitimate short glide closes.
     * These handlers only rebase check state; they never write native distance.
     */
    public static void onGlidingMove(final Player player, final MovingData data, final double yDistance) {
        onGlidingMove(player, data, yDistance, false);
    }

    /**
     * @param acceptedMove yDistance is the current accepted move (NoFall.check),
     *            not a checkpoint restore; only then can it be judged a pull-up.
     */
    public static void onGlidingMove(final Player player, final MovingData data, final double yDistance,
            final boolean acceptedMove) {
        final float nativeDistance = Math.max(0f, player.getFallDistance());
        float contactDebt = data.noFallContactFallDebt;
        final float cancelledDebt = data.noFallCancelledAirFallDebt;
        // Our own set-back removed the downward speed, so the glide limit it
        // then triggers cannot forgive the accepted descent before it.
        final float setBackDebt = data.noFallSetBackFallDebt;
        // A pending landing confirmation (clearNoFallDescent keeps it) belongs
        // to a contact before this glide; neither deploy nor limit settles it.
        if (!data.noFallGlideEstablished) {
            // A real deployment starts with native distance. Never resurrect
            // descent from before deployment. Distance native lost to our own
            // cancellation is not resurrected descent; the limit still applies.
            data.clearNoFallDescent();
            data.clearGlideDebt();
            data.noFallFallDistance = nativeDistance;
            data.noFallCancelledAirFallDebt = cancelledDebt;
        }
        // updateFallFlying applies its limit once per native tick, before
        // the next packet descent. Ground spoofing can erase native distance,
        // but cannot invent that velocity-based reset or repeat it per packet.
        final int tick = player.getTicksLived();
        final boolean limit = nativeGlideLimitApplied(player, data, nativeDistance);
        final float accumulated = Math.max(data.noFallFallDistance, nativeDistance);
        if (acceptedMove && (limit || data.noFallGlideShadowContact) && accumulated > 1f
                && getDamage(player, accumulated) >= Magic.FALL_DAMAGE_MINIMUM
                && isImplausibleGlideReversal(player, data, yDistance, false)) {
            // Not a pull-up: the forgiven descent is owed at the next contact.
            contactDebt = Math.max(contactDebt, accumulated);
        }
        if (limit) {
            data.clearNoFallDescent();
            data.noFallFallDistance = Math.min(1f, accumulated);
            // A FALL event this check cancelled while airborne was a contact
            // the client claimed and vanilla would have charged on the spot
            // (LiquidBounce Verus, REVIEW-LAB-7 #1): the limit cannot forgive it.
            data.noFallCancelledAirFallDebt = cancelledDebt;
        } else data.noFallFallDistance = accumulated;
        recordGlideAccounting(player, data, tick, nativeDistance, yDistance);
        data.noFallFallDistance += (float) Math.max(0.0, -yDistance);
        data.noFallMaxY = player.getLocation().getY() + yDistance + data.noFallFallDistance;
        data.noFallGlideEstablished = true;
        data.noFallContactFallDebt = contactDebt;
        data.noFallSetBackFallDebt = setBackDebt;
    }

    /**
     * Native LivingEntity#updateFallFlying caps distance at exactly one, once
     * per server tick, when the server's own velocity is above -0.5. That
     * velocity is the server's glide simulation of the player from the last
     * received position (tickPlayer: travel, then the position is restored);
     * it reaches zero when that simulation meets the ground. The limit applied
     * since the last accounted move if a native tick passed and either the
     * velocity seen at that move was above -0.5, or native distance went from
     * above one to exactly one (the simulation met the ground at a tick with no
     * packet). Resets go to zero; only this limit leaves exactly one.
     */
    static boolean nativeGlideLimitApplied(final Player player, final MovingData data, final float nativeDistance) {
        if (player.getTicksLived() == data.noFallGlideAccountingTick) return false;
        return data.noFallGlideLimitPending
                || data.noFallGlideNativeAbove && Math.abs(nativeDistance - 1f) <= 1.0e-6f;
    }

    private static void recordGlideAccounting(final Player player, final MovingData data, final int tick,
            final float nativeDistance, final double yDistance) {
        data.noFallGlideAccountingTick = tick;
        data.noFallGlideLimitPending = player.getVelocity().getY() > -0.5;
        // Native adds this packet's descent after the event; an upward packet
        // resets it. Only from at least one, still above one, is exactly one later
        // the limit's own value (a reset needs an exact refill to get there).
        data.noFallGlideNativeAbove = nativeDistance >= 1f - 1.0e-6f && yDistance <= 0.0
                && nativeDistance - yDistance > 1.0 + 1.0e-6;
        data.noFallGlideShadowContact = false;
    }

    /**
     * A contact that ends an established glide: on the touchdown move
     * (yDistance, from fromY) or at an owner callback (yDistance NaN, fromY the
     * contact). The native limit applies before native processes this landing,
     * as on any glide move: vanilla charges only what is left after it. Debt
     * that proves a suppressed or unprocessed contact (cancelled FALL, own
     * set-back, denied ground, impossible reversal) stays owed. A descending
     * touchdown is cut short by the collision and is never judged a reversal.
     */
    public static void onGlideContact(final Player player, final MovingData data, final double fromY,
            final double yDistance) {
        if (!data.noFallGlideEstablished) return;
        final float nativeDistance = Math.max(0f, player.getFallDistance());
        final boolean limit = nativeGlideLimitApplied(player, data, nativeDistance);
        float contactDebt = data.noFallContactFallDebt;
        final float setBackDebt = data.noFallSetBackFallDebt;
        final float cancelledDebt = data.noFallCancelledAirFallDebt;
        final float accumulated = Math.max(data.noFallFallDistance, nativeDistance);
        if ((limit || data.noFallGlideShadowContact) && accumulated > 1f
                && getDamage(player, accumulated) >= Magic.FALL_DAMAGE_MINIMUM
                && isImplausibleGlideReversal(player, data, yDistance, true)) {
            contactDebt = Math.max(contactDebt, accumulated);
        }
        if (limit) {
            data.clearNoFallDescent();
            data.noFallFallDistance = Math.min(1f, accumulated);
            data.noFallMaxY = fromY + data.noFallFallDistance;
            data.noFallContactFallDebt = contactDebt;
            data.noFallSetBackFallDebt = setBackDebt;
            data.noFallCancelledAirFallDebt = cancelledDebt;
            // Consumed: a second contact callback for the same tick must not repeat it.
            data.noFallGlideAccountingTick = player.getTicksLived();
            data.noFallGlideLimitPending = false;
            data.noFallGlideNativeAbove = false;
        } else data.noFallContactFallDebt = contactDebt;
        data.noFallGlideShadowContact = false;
    }

    /** The server's glide simulation met a surface below the accepted position. */
    public static void onGlideShadowContact(final MovingData data) {
        if (data.noFallGlideEstablished) data.noFallGlideShadowContact = true;
    }


    /** The server starts a jump only from its own ground state. A gliding
     * player reaching that state with damaging descent has met the ground, and
     * vanilla would have charged that landing. Keep it as an explicit receipt so
     * the glide limit the jump velocity triggers cannot forgive it. */
    public static void onServerJump(final Player player, final MovingData data) {
        if (ServerVersion.compareMinecraftVersion("26.1") < 0 || player.isInsideVehicle()
                || !Bridge1_9.isGlidingWithElytra(player)) return;
        final float owed = Math.max(retainedNativeResetDebt(data),
                (float) getApplicableFallHeight(player, player.getLocation().getY(), data));
        if (getDamage(player, owed) >= Magic.FALL_DAMAGE_MINIMUM) {
            data.noFallContactFallDebt = Math.max(data.noFallContactFallDebt, owed);
        }
    }

    /** The server ended the glide (or a checkpoint restore did). */
    public static void stopGlide(final Player player, final MovingData data) {
        stopGlide(player, data, Double.NaN, false);
    }

    public static void stopGlide(final Player player, final MovingData data, final double yDistance) {
        stopGlide(player, data, yDistance, false);
    }

    /**
     * @param yDistance the accepted move that ends the glide; NaN outside move
     *            processing (checkpoint restore), where no pull-up can be judged.
     * @param landed that move ends on support (a descent there is a collision).
     */
    public static void stopGlide(final Player player, final MovingData data, final double yDistance,
            final boolean landed) {
        if (!data.noFallGlideEstablished && data.noFallGlideTicks == 0 && data.noFallGlideCarry == 0f) return;
        final boolean tracked = data.noFallGlideEstablished;
        float contactDebt = data.noFallContactFallDebt;
        final float setBackDebt = data.noFallSetBackFallDebt;
        final boolean limited = tracked && nativeGlideLimitApplied(player, data, Math.max(0f, player.getFallDistance()));
        if ((limited || tracked && data.noFallGlideShadowContact) && data.noFallFallDistance > 1f
                && getDamage(player, data.noFallFallDistance) >= Magic.FALL_DAMAGE_MINIMUM
                && isImplausibleGlideReversal(player, data, yDistance, landed)) {
            contactDebt = Math.max(contactDebt, data.noFallFallDistance);
        }
        // A cancelled airborne FALL stays owed through the limit (see onGlidingMove).
        final float cancelledDebt = data.noFallCancelledAirFallDebt;
        final float retained = limited ? Math.min(1f, data.noFallFallDistance) : data.noFallFallDistance;
        final float distance = tracked ? Math.max(retained, player.getFallDistance())
                : Math.max(0f, player.getFallDistance());
        data.clearNoFallDescent();
        data.clearGlideDebt();
        data.noFallFallDistance = distance;
        data.noFallMaxY = player.getLocation().getY() + distance;
        data.noFallContactFallDebt = contactDebt;
        data.noFallSetBackFallDebt = setBackDebt;
        data.noFallCancelledAirFallDebt = cancelledDebt;
    }

    /** Vanilla glide constants (LivingEntity#updateFallFlyingMovement; the drags are floats). */
    private static final double GLIDE_GRAVITY = 0.08, GLIDE_LIFT = 0.1, GLIDE_CLIMB = 0.04 * 3.2,
            GLIDE_VERTICAL_DRAG = (double) 0.98f, GLIDE_HORIZONTAL_DRAG = (double) 0.99f;
    /** Rounding above the vanilla bound. */
    private static final double GLIDE_REVERSAL_MARGIN = 0.01;
    /** Longer moves are not judged (timing too uncertain). */
    private static final int MAX_JUDGED_MOVE_PACKETS = 4;

    /**
     * The native glide limit reads the server's own velocity. Under packet
     * bunching that velocity can already show the ground collision of a damaging
     * dive whose landing the client then hides (LiquidBounce PacketJump's +1e-9
     * split move; SpoofLanding's jump after an absorbed landing packet). The
     * accepted moves must also allow a pull-up: a move higher than vanilla glide
     * physics can reach from the last move, over the client ticks it spans, with
     * no rocket, velocity entry, wind charge or riptide, is not one.
     * <p>
     * Bound, per tick, from LivingEntity#updateFallFlyingMovement for any pitch
     * (each term at its own maximum): vy' = 0.98 (vy - 0.25 g + 0.1 max(0, g - vy)
     * + 0.128 h), never below vy (the first air tick after a glide repeats the
     * last velocity), and h' = 0.99 (h + 0.1 max(0, g - vy)); g = 0.08, h the
     * horizontal speed (the climb term converts it). The last move must be one
     * received position packet (one client tick); this move spans the received
     * packets since it, and may reach the highest partial sum over 1..span ticks.
     * Unknown timing, slow falling and levitation defer to native accounting
     * (no debt).
     *
     * @param landed the move ends on support: a descent there is a collision.
     */
    static boolean isImplausibleGlideReversal(final Player player, final MovingData data, final double yDistance,
            final boolean landed) {
        if (!Double.isFinite(yDistance) || data.playerMoves == null) return false;
        if (landed && yDistance <= 0.0) return false;
        final PlayerMoveData last = data.playerMoves.getFirstPastMove();
        final PlayerMoveData current = data.playerMoves.getCurrentMove();
        if (last == null || !last.toIsValid || current == null
                || !Double.isFinite(last.yDistance) || !Double.isFinite(last.hDistance)) return false;
        // Only consecutive accepted moves: a teleport, worker join or reset
        // starts elsewhere and leaves an older move behind.
        if (Math.abs(current.from.getX() - last.to.getX()) > 1.0e-6
                || Math.abs(current.from.getY() - last.to.getY()) > 1.0e-6
                || Math.abs(current.from.getZ() - last.to.getZ()) > 1.0e-6) return false;
        // Timing: one client tick for the last move, known span for this one.
        final int ticks = data.noFallMovePackets;
        if (ticks <= 0 || ticks > MAX_JUDGED_MOVE_PACKETS || data.noFallLastMovePackets != 1
                || !(Math.abs(data.noFallLastMoveX - last.to.getX()) <= 1.0e-9)
                || !(Math.abs(data.noFallLastMoveY - last.to.getY()) <= 1.0e-9)
                || !(Math.abs(data.noFallLastMoveZ - last.to.getZ()) <= 1.0e-9)) return false;
        // Only velocity that can lift the player explains the gain. The fly-check
        // transition workaround queues the landing's descent and zero itself.
        if (last.verVelUsed != null && last.verVelUsed.value > 0.0
                || current.verVelUsed != null && current.verVelUsed.value > 0.0
                || data.fireworksBoostDuration > 0 || data.hasUpwardVerticalVelocity()
                || data.windChargeImpulseEntityTick != Integer.MIN_VALUE
                || data.windChargeImpulseSource != null || data.nativeWindChargeSource != null) return false;
        try {
            if (player.isRiptiding()
                    || Bridge1_13.getSlowfallingAmplifier(player) != Double.NEGATIVE_INFINITY
                    || Bridge1_9.getLevitationAmplifier(player) != Double.NEGATIVE_INFINITY) return false;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
        // Highest reach after 1..ticks ticks: a received packet that is not a
        // physics tick (a teleport confirmation) can only make the bound looser.
        double vy = last.yDistance, h = last.hDistance, sum = 0.0, reach = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ticks; i++) {
            final double lift = GLIDE_LIFT * Math.max(0.0, GLIDE_GRAVITY - vy);
            final double next = Math.max(vy,
                    GLIDE_VERTICAL_DRAG * (vy - 0.25 * GLIDE_GRAVITY + lift + GLIDE_CLIMB * h));
            h = GLIDE_HORIZONTAL_DRAG * (h + lift);
            vy = next;
            sum += vy;
            reach = Math.max(reach, sum);
        }
        return yDistance > reach + GLIDE_REVERSAL_MARGIN;
    }

    /**
     * Received position packets from the last matched accepted move to this
     * endpoint (0: unknown). One per client tick for a moving client.
     */
    public static int receivedPacketSpan(final LocationData to, final MovingData data, final IPlayerData pData) {
        if (to == null || pData == null || data.noFallMatchedPacketSequence <= 0L) return 0;
        final DataPacketFlying match = findReceivedPosition(to.getX(), to.getY(), to.getZ(), data, pData);
        if (match == null) return 0;
        final long span = match.getSequence() - data.noFallMatchedPacketSequence;
        return span > 0L && span <= 64L ? (int) span : 0;
    }

    /** Before any NoFall hook of this move: its received packet span. */
    public static void beginMove(final PlayerMoveData move, final MovingData data, final IPlayerData pData) {
        data.noFallMovePackets = move != null && move.toIsValid && ServerVersion.compareMinecraftVersion("26.1") >= 0
                ? receivedPacketSpan(move.to, data, pData) : 0;
    }

    /**
     * Re-anchor only accepted descent after our own corrective placement, and
     * never charge more than the physical fall (REVIEW-LAB-7 #2, #3). The
     * correction puts the player at target; the fall goes on from there. What
     * stays owed is the accepted descent above the target: the drop from the
     * top of this fall (tracked maximum, or the last accepted position plus the
     * owed distance) down to the target, never more than was owed before.
     * Native distance survives the correction teleport, so it is written too:
     * otherwise a correction up to the start of a fall pays that fall twice
     * (short glide: FD 6.594 kept at y 93, then the whole 12 again).
     */
    public static void afterSetBack(final Player player, final Location target, final MovingData data) {
        final float nativeDistance = Math.max(0f, player.getFallDistance());
        final boolean tracked = data.noFallMaxY > BlockProperties.getMinWorldY();
        // Also when only this check's own tracked descent is left (a
        // non-damaging fall: no set-back debt, native reset by the teleport):
        // otherwise it is added to the fall from the target again
        // (REVIEW-LAB-7 #3, boat r7C-lat-mount-c on-6; f8bT-lat-mount-a on-2).
        if (data.noFallSetBackFallDebt <= 0f
                && !(tracked && (nativeDistance > 0f || data.noFallFallDistance > 0f))) return;
        final float owed = Math.max(data.noFallSetBackFallDebt,
                Math.max(nativeDistance, tracked ? Math.max(0f, data.noFallFallDistance) : 0f));
        double top = tracked ? data.noFallMaxY : Double.NEGATIVE_INFINITY;
        final PlayerMoveData move = data.playerMoves == null ? null : data.playerMoves.getCurrentMove();
        if (move != null && move.toIsValid) top = Math.max(top, move.from.getY() + owed);
        // Debt from an impossible glide reversal (no legit client makes one) is
        // the cheat's own descent and stays owed in full, as in round 7.
        final boolean reversal = data.noFallSetBackReversal;
        data.noFallSetBackReversal = false;
        final float distance = Double.isFinite(top) && !reversal
                ? Math.min(owed, (float) Math.max(0.0, top - target.getY())) : owed;
        data.noFallFallDistance = distance;
        data.noFallMaxY = target.getY() + distance;
        data.noFallSetBackFallDebt = distance;
        // Lower only: owed descent above native distance stays retained debt.
        if (nativeDistance > distance + 1.0e-6f) player.setFallDistance(distance);
    }

    /**
     * Settle a restored landing (see adjustFallDistance) before anything uses
     * it as owed descent. Native zero while the player is still on that same
     * supported contact proves native processed it, with or without a FALL
     * event (damage immunity skips the event: LivingEntity.hurtServer). A
     * reverted landing (still airborne, distance never reset, moved away) is
     * owed at the next real contact.
     */
    public static void resolveHandoff(final Player player, final MovingData data, final boolean supported) {
        final float handoff = data.noFallHandoffDistance;
        if (!(handoff > 0f)) return;
        final Location at = player.getLocation();
        // A landing native can process silently (damage cooldown, ...) is
        // consumed by native zero on that contact. Any other one is consumed
        // only by its FALL event: native zero there means it was reverted or
        // never processed (a tiny upward packet resets distance, and our own
        // untracked-move correction can put the player back on the contact).
        final boolean consumed = supported && data.noFallHandoffSilent && player.getFallDistance() <= 0f
                && isHandoffContact(data, at.getX(), at.getY(), at.getZ());
        data.clearNoFallHandoff();
        if (!consumed) data.noFallContactFallDebt = Math.max(data.noFallContactFallDebt, handoff);
    }

    /** Same contact: the exact processed height, and no further away than one
     * sub-event packet (Bukkit fires no move event below 1/16 block). */
    public static boolean isHandoffContact(final MovingData data, final double x, final double y, final double z) {
        return Math.abs(y - data.noFallHandoffY) <= 1.0e-7
                && Math.abs(x - data.noFallHandoffX) <= 0.0625 && Math.abs(z - data.noFallHandoffZ) <= 0.0625;
    }

    public static float retainedNativeResetDebt(final MovingData data) {
        return Math.max(data.noFallContactFallDebt,
                Math.max(data.noFallCancelledAirFallDebt, data.noFallSetBackFallDebt));
    }

    /** Match accepted XYZ to the oldest unconsumed received packet, not the
     * latest network-thread packet (which may already be a future tiny move).
     * Packet ground alone is never support or accepted movement. */
    private static DataPacketFlying matchAcceptedPacket(final LocationData to,
            final MovingData data, final IPlayerData pData) {
        final DataPacketFlying match = findReceivedPosition(to.getX(), to.getY(), to.getZ(), data, pData);
        if (match != null) data.noFallMatchedPacketSequence = match.getSequence();
        return match;
    }

    private static DataPacketFlying findReceivedPosition(final double x, final double y, final double z,
            final MovingData data, final IPlayerData pData) {
        final NetData net = pData.getGenericInstance(NetData.class);
        if (net == null) return null;
        DataPacketFlying match = null;
        for (final DataPacketFlying packet : net.copyNoFallPackets()) {
            if (packet.isCancelled() || !packet.hasPos
                    || !Double.isFinite(packet.getX()) || !Double.isFinite(packet.getY()) || !Double.isFinite(packet.getZ())
                    || packet.getSequence() <= data.noFallMatchedPacketSequence
                    || Math.abs(packet.getX() - x) > 1.0e-7
                    || Math.abs(packet.getY() - y) > 1.0e-7
                    || Math.abs(packet.getZ() - z) > 1.0e-7) continue;
            if (match == null || packet.getSequence() < match.getSequence()) match = packet;
        }
        return match;
    }

    /** A tiny honest boat contact may reset native distance without a Bukkit
     * movement event. Require exact received true-ground XYZ and owned boat
     * boxes. The first internal split can end at that same received native point;
     * a native zero or ground claim by itself is never reset proof. */
    private Location rebaseMissedNativeBoatContact(final Player player, final PlayerMoveData move,
            final MovingData data, final MovingConfig cc, final IPlayerData pData) {
        if (cc.noFallDealDamage || ServerVersion.compareMinecraftVersion("26.1") < 0
                || mcAccess == null
                || player.isInsideVehicle() || player.isFlying()
                || cc.noFallSkipAllowFlight && player.getAllowFlight()
                || move.from.resetCond || move.to.resetCond
                || mcAccess.getHandle().isIgnoringFallDamageFromCurrentImpulse(player)
                || !Folia.isOwnedByCurrentRegion(player)) return null;
        final Location feet = player.getLocation();
        if (feet == null || !Double.isFinite(feet.getX()) || !Double.isFinite(feet.getY())
                || !Double.isFinite(feet.getZ()) || feet.getY() >= move.from.getY() - 1.0e-7) return null;
        final DataPacketFlying contact = findReceivedPosition(feet.getX(), feet.getY(), feet.getZ(), data, pData);
        final DataPacketFlying current = findReceivedPosition(move.to.getX(), move.to.getY(), move.to.getZ(), data, pData);
        if (contact == null || !contact.onGround || current == null
                || contact.getSequence() > current.getSequence()) return null;
        final double halfWidth = player.getBoundingBox().getWidthX() / 2.0;
        if (!(halfWidth > 0.0) || !Double.isFinite(halfWidth)) return null;
        boolean supported = false;
        try {
            for (final Entity boat : player.getNearbyEntities(1.5, 8.0, 1.5)) {
                if (!Folia.isOwnedByCurrentRegion(boat) || !boat.isValid() || !MaterialUtil.isBoat(boat.getType())
                        || !mcAccess.getHandle().canCollideWith(player, boat)) continue;
                final org.bukkit.util.BoundingBox box = boat.getBoundingBox();
                if (boxSupportsFeet(feet.getX(), feet.getY(), feet.getZ(), halfWidth,
                        box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())
                        || mcAccess.getHandle().hasSweptBoatSupport(boat, feet.getX() - halfWidth,
                            feet.getY(), feet.getZ() - halfWidth, feet.getX() + halfWidth, feet.getZ() + halfWidth)) {
                    supported = true;
                    break;
                }
            }
        } catch (RuntimeException foreignEntity) {
            return null;
        }
        if (!supported) return null;
        final float owed = Math.max(0f, Math.max(retainedNativeResetDebt(data),
                (float) getApplicableFallHeight(player, feet.getY(), data)));
        if (pData.isDebugActive(type)) {
            debug(player, "Missed native boat contact: y=" + feet.getY()
                    + " seq=" + contact.getSequence() + " current=" + current.getSequence() + " owed=" + owed);
        }
        data.noFallMatchedPacketSequence = contact.getSequence();
        data.clearNoFallData();
        data.clearGlideDebt();
        // Below ordinary damage distance the honest native landing is already
        // consumed. Do not replay block effects. Larger retained check debt can
        // still require one missing native landing after earlier air spoofs.
        if (owed >= Magic.FALL_DAMAGE_DIST) {
            player.setFallDistance(owed);
            mcAccess.getHandle().dispatchNativeLanding(player);
        }
        data.noFallMaxY = feet.getY();
        return feet;
    }

    /** Clients draw a falling boat a few ticks behind the server. An honest
     * client can land on that displayed top after the server boat moved on, and
     * vanilla accepts its ground packet. Require the exact received true-ground
     * position and an owned, collidable boat whose recent native top passed
     * through it. Each query also keeps the nearby boat's recent native frames. */
    private boolean receivedBoatTopContact(final Player player, final LocationData to,
            final MovingData data, final IPlayerData pData) {
        if (mcAccess == null || player.isInsideVehicle() || !Folia.isOwnedByCurrentRegion(player)) return false;
        final double halfWidth = player.getBoundingBox().getWidthX() / 2.0;
        if (!(halfWidth > 0.0) || !Double.isFinite(halfWidth)) return false;
        final int latencyTicks = boatContactLatencyTicks(player);
        boolean supported = false;
        try {
            for (final Entity boat : player.getNearbyEntities(1.5, 8.0, 1.5)) {
                if (!Folia.isOwnedByCurrentRegion(boat) || !boat.isValid() || !MaterialUtil.isBoat(boat.getType())
                        || !mcAccess.getHandle().canCollideWith(player, boat)) continue;
                if (hasSweptBoatSupport(mcAccess.getHandle(), boat, to.getX() - halfWidth, to.getY(),
                        to.getZ() - halfWidth, to.getX() + halfWidth, to.getZ() + halfWidth, latencyTicks)) supported = true;
            }
        } catch (RuntimeException foreignEntity) {
            return false;
        }
        if (!supported) return false;
        final DataPacketFlying received = findReceivedPosition(to.getX(), to.getY(), to.getZ(), data, pData);
        return received != null && received.onGround;
    }

    /** At most one second of client latency widens a boat-top contact window. */
    private static final int MAX_BOAT_CONTACT_LATENCY_TICKS = 20;

    /**
     * Native ticks a client's displayed boat can lag beyond vanilla
     * interpolation: one round trip (the server's keep-alive average) and half
     * again for jitter. Such a client lands on that older top, and vanilla
     * accepts its ground packet and charges the landing there.
     */
    public static int boatContactLatencyTicks(final Player player) {
        final int ping;
        try {
            ping = player.getPing();
        } catch (RuntimeException | LinkageError unavailable) {
            return 0;
        }
        if (ping <= 0) return 0;
        return (int) Math.min(MAX_BOAT_CONTACT_LATENCY_TICKS, Math.ceil(ping * 1.5 / 50.0));
    }

    /** Swept boat support, widened by latencyTicks only when there is latency. */
    public static boolean hasSweptBoatSupport(final MCAccess access, final Entity boat, final double minX,
            final double feetY, final double minZ, final double maxX, final double maxZ, final int latencyTicks) {
        return latencyTicks > 0 ? access.hasSweptBoatSupport(boat, minX, feetY, minZ, maxX, maxZ, latencyTicks)
                : access.hasSweptBoatSupport(boat, minX, feetY, minZ, maxX, maxZ);
    }

    /** A descending true-ground step can follow a falling boat without a
     * damaging event. Validate its final native point before consuming the
     * sub-threshold geometric step; neither a received flag nor FD zero alone
     * proves that it was processed or physically supported. */
    private Location consumeProcessedBoatStep(final Player player, final MovingData data) {
        final DataPacketFlying step = data.noFallBoatGroundStep;
        if (step == null || mcAccess == null
                || retainedNativeResetDebt(data) > 0f || getDamage(player, data.noFallFallDistance) >= Magic.FALL_DAMAGE_MINIMUM
                || !Folia.isOwnedByCurrentRegion(player)) return null;
        final Location feet = player.getLocation();
        if (feet == null || Math.abs(feet.getX() - step.getX()) > 1.0e-7
                || feet.getY() > step.getY() + 1.0e-7
                || Math.abs(feet.getZ() - step.getZ()) > 1.0e-7
                || getDamage(player, (float) Math.max(data.noFallFallDistance, data.noFallMaxY - step.getY())) >= Magic.FALL_DAMAGE_MINIMUM) return null;
        final float nativeDistance = player.getFallDistance();
        // A later no-event packet may already have begun the new fall. Its
        // native distance must equal only descent since the processed receipt.
        if (Math.abs(nativeDistance - Math.max(0.0, step.getY() - feet.getY())) > 1.0e-5) return null;
        final double halfWidth = player.getBoundingBox().getWidthX() / 2.0;
        if (!(halfWidth > 0.0) || !Double.isFinite(halfWidth)) return null;
        boolean supported = false;
        try {
            for (final Entity boat : player.getNearbyEntities(1.5, 8.0, 1.5)) {
                if (!Folia.isOwnedByCurrentRegion(boat) || !boat.isValid() || !MaterialUtil.isBoat(boat.getType())
                        || !mcAccess.getHandle().canCollideWith(player, boat)) continue;
                final org.bukkit.util.BoundingBox box = boat.getBoundingBox();
                if (boxSupportsFeet(feet.getX(), step.getY(), feet.getZ(), halfWidth,
                        box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())
                        || mcAccess.getHandle().hasSweptBoatSupport(boat, feet.getX() - halfWidth,
                            step.getY(), feet.getZ() - halfWidth, feet.getX() + halfWidth, feet.getZ() + halfWidth)) {
                    supported = true;
                    break;
                }
            }
        } catch (RuntimeException foreignEntity) { return null; }
        if (!supported) return null;
        data.clearNoFallData();
        data.clearGlideDebt();
        data.noFallFallDistance = nativeDistance;
        data.noFallMaxY = feet.getY() + nativeDistance;
        return feet;
    }

    /** A reset/contact/teleport invalidates callbacks captured for an earlier fall. */
    public static boolean mayRestoreCancelledFall(final MovingData data, final long generation) {
        return data.noFallGeneration == generation;
    }

    /** The vehicle is on the ground, so this landing is the vehicle's, not a later foot landing. */
    public static void onVehicleLanded(final MovingData data) {
        data.clearNoFallData();
        data.clearGlideDebt();
        data.noFallSkipAirCheck = true;
    }

    /** A confirmed ride/dismount uses native resets, even while the vehicle is airborne. */
    public static void onVehicleAir(final Player player, final MovingData data, final boolean leaving) {
        data.clearNoFallData();
        data.clearGlideDebt();
        data.clearWindChargeImpulse();
        data.noFallFallDistance = Math.max(0f, player.getFallDistance());
        data.noFallMaxY = player.getLocation().getY();
    }

    /** Consume a server-confirmed slime contact even though bounce handling skips NoFall. */
    public static void consumeSlimeContact(PlayerLocation to, MovingData data, double yOnGround) {
        if (to.getTypeIdBelow() == Material.SLIME_BLOCK && to.isOnGround(yOnGround)) {
            data.clearNoFallData();
            data.clearGlideDebt();
            data.noFallMaxY = to.getY();
            data.noFallSkipAirCheck = true;
            data.clearWindChargeImpulse();
        }
    }

    /**
     * An accepted ordinary placement must respect authoritative native resets.
     * Packet ground claims and NCP corrections are handled on their own paths;
     * check distance must not resurrect a plugin-cleared descent here.
     */
    public static float distanceForTeleport(final Player player, final MovingData data) {
        return Math.max(0f, player.getFallDistance());
    }

    /** Rebase an accepted ordinary placement without overriding native reset/retention. */
    public static void afterTeleport(Player player, MovingData data, Location to, float sourceDistance, boolean preserve) {
        final float nativeDistance = player.getFallDistance();
        final float distance = Math.max(0f, nativeDistance);
        final PlayerMoveData placement = data.playerMoves.getFirstPastMove();
        final boolean contact = placement != null && (placement.from.onGround || placement.from.resetCond);
        data.clearNoFallData();
        data.noFallMaxY = to.getY();
        // Contact is consumed now. Native collision owns the one landing, even
        // if immunity/cancellation suppresses its event; later moves cannot repay it.
        data.noFallFallDistance = contact ? 0f : distance;
        data.noFallSkipAirCheck = !preserve || contact;
        if (contact) data.clearGlideDebt();
        if (Folia.isFoliaServer()) FallStateSnapshot.clear(player);
    }

    private void dealFallDamage(final Player player, final double damage) {
        if (mcAccess.getHandle().dealFallDamageFiresAnEvent().decide()) {
            // TODO: Better decideOptimistically?
            mcAccess.getHandle().dealFallDamage(player, damage);
        }
        else {
            final EntityDamageEvent event = BridgeHealth.getEntityDamageEvent(player, DamageCause.FALL, damage);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                // Legacy API fallback runs only in the current entity callback.
                // Do not retain a player or replay damage after a lifecycle change.
                player.setLastDamageCause(event);
                mcAccess.getHandle().dealFallDamage(player, BridgeHealth.getRawDamage(event));
            }
        }

        // Currently resetting is done from within the damage event handler.
        // TODO: MUST detect if event fired at all (...) and override, if necessary. Best probe once per class (with YES).
        //        data.clearNoFallData();
        player.setFallDistance(0);
    }

    /**
     * Checks a player. Expects from and to using cc.yOnGround.
     * 
     * @param player
     *            the player
     * @param from
     *            the from
     * @param to
     *            the to
     * @param previousSetBackY
     *            The set back y from lift-off. If not present:
     *            Double.NEGATIVE_INFINITY.
     */
    public void check(final Player player, final PlayerLocation pFrom, final PlayerLocation pTo, 
                      final double previousSetBackY,
                      final MovingData data, final MovingConfig cc, final IPlayerData pData) {

        final boolean debug = pData.isDebugActive(type);
        final PlayerMoveData thisMove = data.playerMoves.getCurrentMove();
        final LocationData from = thisMove.from;
        final LocationData to = thisMove.to;
        final Location processedBoatStep = consumeProcessedBoatStep(player, data);
        final Location missedContact = rebaseMissedNativeBoatContact(player, thisMove, data, cc, pData);
        final double fromY = missedContact != null ? missedContact.getY()
                : processedBoatStep != null ? processedBoatStep.getY() : from.getY();
        final double toY = to.getY();
        final double yDiff = toY - fromY;
        if (yDiff < 0.0) data.noFallNearGroundProbeTries = 0;
        final double oldNFDist = data.noFallFallDistance;
        // Reset-cond is not touched by yOnGround.
        // TODO: Distinguish water depth vs. fall distance ?
        /*
         * TODO: Account for flags instead (F_FALLDIST_ZERO and
         * F_FALLDIST_HALF). Resetcond as trigger: if (resetFrom) { ...
         */
        // TODO: Also handle from and to independently (rather fire twice than wait for next time).
        final boolean fromReset = from.resetCond;
        final boolean toReset = to.resetCond;
        final boolean fromOnGround, toOnGround;
        
        // Adapt yOnGround if necessary (sf uses another setting).
        final boolean nativeLanding = !cc.noFallDealDamage && ServerVersion.compareMinecraftVersion("26.1") >= 0;
        if (nativeLanding && mcAccess != null
                && mcAccess.getHandle().isIgnoringFallDamageFromCurrentImpulse(player)) {
            // Native checkFallDistanceAccumulation can reduce distance to one
            // during an upward wind impulse. Old geometric descent must not
            // undo that reduction. Retain the current packet descent below;
            // only an actual native impulse can select this accounting.
            data.clearNoFallData();
            data.noFallFallDistance = Math.max(0f, player.getFallDistance());
            data.noFallMaxY = fromY + data.noFallFallDistance;
        }
        if (nativeLanding) {
            // The movement checks' step tolerance is not a completed contact.
            fromOnGround = missedContact != null || pFrom.isOnGround(0.00001) || pFrom.standsOnEntityContact(0.00001, 0, 0);
            toOnGround = pTo.isOnGround(0.00001) || pTo.standsOnEntityContact(0.00001, 0, 0)
                    || !fromOnGround && !fromReset && !toReset && yDiff <= 0.0 && receivedBoatTopContact(player, to, data, pData);
        }
        else if (yDiff < 0 && cc.yOnGround < cc.noFallyOnGround) {
            // In fact this is somewhat heuristic, but it seems to work well.
            // Missing on-ground seems to happen with running down pyramids rather.
            // TODO: Should be obsolete.
            adjustYonGround(pFrom, pTo , cc.noFallyOnGround);
            fromOnGround = pFrom.isOnGround();
            toOnGround = pTo.isOnGround();
        } 
        else {
            fromOnGround = from.onGround;
            toOnGround = to.onGround;
        }

        beginMove(thisMove, data, pData);
        if (Bridge1_9.isGlidingWithElytra(player) && !toOnGround && !fromReset && !toReset) {
            onGlidingMove(player, data, yDiff, true);
        }

        // Keep an explicit receipt only when both accepted geometry and its
        // matching received packet prove a suppressed native contact. A tiny
        // later upward packet can reset native FD without a Bukkit move event.
        final DataPacketFlying acceptedPacket = nativeLanding ? matchAcceptedPacket(to, data, pData) : null;
        final float pendingContact = data.noFallContactFallDebt;
        final float acceptedContactDistance = toOnGround && !fromReset && !toReset
                ? Math.max(pendingContact, Math.max(player.getFallDistance(),
                    Math.max(data.noFallFallDistance + (float) Math.max(0.0, -yDiff),
                        (float) Math.max(0.0, data.noFallMaxY - toY)))) : 0f;
        if (nativeLanding && toOnGround && !fromOnGround && !fromReset && !toReset) {
            // Touchdown of an established glide: native applies its glide limit
            // before it processes this landing (the receipt above keeps a denied
            // contact owed in full).
            onGlideContact(player, data, fromY, yDiff);
        }

        // TODO: early returns (...) 

        final double minY = Math.min(fromY, toY);
        boolean landedFromAir = false;

        if (fromReset || toReset) {
            // Water, web, powder snow, climbables: vanilla ends the fall here.
            data.clearNoFallData();
            data.clearGlideDebt();
            // Ensure very big/strange moves don't yield violations.
            if (toY - fromY <= -Magic.FALL_DAMAGE_DIST) {
                data.noFallSkipAirCheck = true;
            }
        }
        else if (fromOnGround || !nativeLanding && !toOnGround && thisMove.touchedGround) {
            // Check if to deal damage (fall back damage check).
            touchDown(player, minY, previousSetBackY, data, cc, pData); // Includes the current y-distance on descend!
            // Ensure very big/strange moves don't yield violations.
            if (toY - fromY <= -Magic.FALL_DAMAGE_DIST) {
                data.noFallSkipAirCheck = true;
            }
        }
        else if (toOnGround) {
            // Check if to deal damage.
            if (yDiff < 0) {
                // In this case the player has traveled further: add the difference.
                data.noFallFallDistance -= yDiff;
            }
            touchDown(player, minY, previousSetBackY, data, cc, pData);
            landedFromAir = true;
        }
        else {
            // Ensure fall distance is correct, or "anyway"?
        }

        if (nativeLanding && toOnGround && !fromReset && !toReset
                && (pendingContact > 0f || acceptedPacket != null && !acceptedPacket.onGround)) {
            data.noFallContactFallDebt = acceptedContactDistance;
        }

        // Set reference y for nofall (always).
        /*
         * TODO: Consider setting this before handleOnGround (at least for
         * resetTo). This is after dealing damage, needs to be done differently.
         */
        // Native resets at the contact itself. A support that falls or breaks
        // away (falling boat) must not charge the pre-contact height again.
        data.noFallMaxY = Math.max(nativeLanding && landedFromAir ? toY : Math.max(fromY, toY), data.noFallMaxY);

        // TODO: fall distance might be behind (!)
        // TODO: should be the data.noFallMaxY be counted in ?
        final float mcFallDistance = player.getFallDistance(); // Note: it has to be fetched here.
        // SKIP: data.noFallFallDistance = Math.max(mcFallDistance, data.noFallFallDistance);

        // Add y distance.
        if (!Bridge1_9.isGlidingWithElytra(player) && !toReset && !toOnGround && yDiff < 0) {
            data.noFallFallDistance -= yDiff;
        }
        else if (cc.noFallAntiCriticals && (toReset || toOnGround || (fromReset || fromOnGround || thisMove.touchedGround) && yDiff >= 0)) {
            final double max = Math.max(data.noFallFallDistance, mcFallDistance);
            if (max > 0.0 && max < 0.75) { // (Ensure this does not conflict with deal-damage set to false.) 

                if (debug) {
                    debug(player, "NoFall: Reset fall distance (anticriticals): mc=" + mcFallDistance +" / nf=" + data.noFallFallDistance);
                }

                if (data.noFallFallDistance > 0) {
                    data.noFallFallDistance = 0;
                }
                
                if (mcFallDistance > 0f) {
                    player.setFallDistance(0f);
                }
            }
        }

        // Preserve one immutable receipt until the owner can confirm that the
        // native true-ground step actually reset distance on the falling boat.
        // Explicit suppressed contacts and damaging geometric debt cannot be
        // forgiven by this sub-threshold confirmation.
        data.noFallBoatGroundStep = nativeLanding && fromOnGround && !toOnGround && !fromReset && !toReset
                && !Bridge1_9.isGlidingWithElytra(player) && acceptedPacket != null && acceptedPacket.onGround
                && getDamage(player, data.noFallFallDistance) < Magic.FALL_DAMAGE_MINIMUM && retainedNativeResetDebt(data) == 0f
                ? acceptedPacket : null;

        if (ServerVersion.compareMinecraftVersion("26.1") >= 0 && (toOnGround || toReset)) {
            data.clearWindChargeImpulse();
        }

        if (nativeLanding) {
            // Timing for the next move's reversal judgement: this move's received span.
            data.noFallLastMovePackets = acceptedPacket != null ? data.noFallMovePackets : 0;
            data.noFallLastMoveX = to.getX();
            data.noFallLastMoveY = to.getY();
            data.noFallLastMoveZ = to.getZ();
        }

        if (debug) {
            debug(player, "NoFall: mc=" + mcFallDistance +" / nf=" + data.noFallFallDistance + (oldNFDist < data.noFallFallDistance ? " (+" + (data.noFallFallDistance - oldNFDist) + ")" : "") + " | ymax=" + data.noFallMaxY);
        }

    }

    /**
     * Called during check.
     * 
     * @param player
     * @param minY
     * @param previousSetBackY
     *            The set back y from lift-off. If not present:
     *            Double.NEGATIVE_INFINITY.
     * @param data
     * @param cc
     */
    private void touchDown(final Player player, final double minY, final double previousSetBackY,
            final MovingData data, final MovingConfig cc, IPlayerData pData) {
        if (cc.noFallDealDamage) {
            handleOnGround(player, minY, previousSetBackY, true, data, cc, pData);
        }
        else {
            adjustFallDistance(player, minY, true, data, cc);
        }
    }

    /**
     * Set yOnGround for from and to, if needed, should be obsolete.
     * @param from
     * @param to
     * @param cc
     */
    private void adjustYonGround(final PlayerLocation from, final PlayerLocation to, final double yOnGround) {
        if (!from.isOnGround()) {
            from.setyOnGround(yOnGround);
        }
        if (!to.isOnGround()) {
            to.setyOnGround(yOnGround);
        }
    }

    /**
     * Quit or kick: adjust fall distance if necessary.
     * @param player
     */
    public void onLeave(final Player player, final MovingData data, 
            final IPlayerData pData) {
        // A worker departure can be the first owner callback after this native
        // contact. Refresh only an existing checkpoint; ordinary teleports must
        // never recreate an explicitly invalidated one.
        if (consumeProcessedBoatStep(player, data) != null && FallStateSnapshot.exists(player)) {
            FallStateSnapshot.save(player, data, player.getLocation());
        }
        final float fallDistance = player.getFallDistance();
        // TODO: Might also detect too high mc fall dist.
        if (data.noFallFallDistance > fallDistance) {
            final double playerY = player.getLocation().getY();
            if (player.isFlying() || player.getGameMode() == GameMode.CREATIVE
                    || player.getAllowFlight() 
                    && pData.getGenericInstance(MovingConfig.class).noFallSkipAllowFlight) {
                // Forestall potential issues with flying plugins.
                player.setFallDistance(0f);
                data.noFallFallDistance = 0f;
                data.noFallMaxY = playerY;
            } else {
                // Might use tolerance, might log, might use method (compare: MovingListener.onEntityDamage).
                // Might consider triggering violations here as well.
                final float yDiff = (float) (data.noFallMaxY - playerY);
                // TODO: Consider to only use one accounting method (maxY). 
                final float maxDist = Math.max(yDiff, data.noFallFallDistance);
                player.setFallDistance(maxDist);
            }
        }
    }

    /**
     * This is called if a player fails a check and gets set back, to avoid using that to avoid fall damage the player might be dealt damage here. 
     * @param player
     * @param data
     */
    public void checkDamage(final Player player,  final double y, 
            final MovingData data, final IPlayerData pData) {
        final MovingConfig cc = pData.getGenericInstance(MovingConfig.class);
        if (Bridge1_9.isGlidingWithElytra(player)) {
            // A gliding player's rejected move keeps native glide accounting,
            // unless it is an impossible reversal out of a damaging dive (see
            // onGlidingMove): then its descent is kept like any rejected move's.
            final PlayerMoveData move = data.playerMoves == null ? null : data.playerMoves.getCurrentMove();
            if (cc.noFallDealDamage || ServerVersion.compareMinecraftVersion("26.1") < 0
                    || move == null || !move.toIsValid) return;
            beginMove(move, data, pData);
            if (getDamage(player, (float) getApplicableFallHeight(player, player.getLocation().getY(), data))
                        < Magic.FALL_DAMAGE_MINIMUM
                    || !isImplausibleGlideReversal(player, data, move.yDistance, move.to.onGround)) return;
            data.noFallSetBackReversal = true;
        }
        else data.noFallSetBackReversal = false;
        if (!cc.noFallDealDamage && ServerVersion.compareMinecraftVersion("26.1") >= 0) {
            // A rejected move/correction is not a landing. Keep its descent for
            // native contact instead of consuming it through immediate damage.
            if (!(cc.noFallSkipAllowFlight && player.getAllowFlight())) {
                // The rejected endpoint is not accepted descent. Use the
                // current owned position and previously accepted accounting.
                final float distance = (float) getApplicableFallHeight(player, player.getLocation().getY(), data);
                data.noFallSetBackFallDebt = Math.max(data.noFallSetBackFallDebt,
                        Math.max(distance, player.getFallDistance()));
                if (distance > player.getFallDistance()) player.setFallDistance(distance);
            }
            return;
        }
        // Deal damage.
        handleOnGround(player, y, data.hasSetBack() ? data.getSetBackY() : Double.NEGATIVE_INFINITY, 
                false, data, cc, pData);
    }

}
