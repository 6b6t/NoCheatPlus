package audit;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache.IBlockCacheNode;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Compares NCP with vanilla for every block state on a real server, run with audit/run.sh.
 * <ul>
 * <li>collision: NCP collidesBlock (with the block's flags) against the vanilla collision shape on a 1/16 grid,
 * including parts that reach into neighbor blocks.</li>
 * <li>ground: NCP isOnGround on every vanilla top surface.</li>
 * <li>sight: NCP canSeeBox against vanilla ray tracing (outline shapes, like the client), with the block between
 * eye and target, from all 6 sides. Only "NCP hidden, vanilla visible" fails, the other way round is leniency.</li>
 * <li>shulker: ground on moving and sideways shulker box lids (block entity, not part of the block states).</li>
 * </ul>
 * Writes plugins/NcpAudit/report.txt ending in "RESULT: PASS" or "RESULT: FAIL n", then stops the server.
 */
public class Audit extends JavaPlugin {

    /** Test block, in an empty flat world. */
    static final int X = 8, Y = 100, Z = 8;
    /** Send to clients, no shape/neighbor updates. */
    static final int FLAGS = 2 | 16;
    /** Same points as CollisionUtil.canSeeBox. */
    static final double[] SAMPLES = {0.5, 0.02, 0.98};
    static final String[] KINDS = {"collision", "ground", "sight"};
    /** Grid points: cell centers, shifted so none lies exactly on a shape edge (chains have edges at 1/32). */
    static final double GRID_START = 1 / 32.0 + 0.0001;

    static final class Result {
        int states;
        final int[] fails = new int[KINDS.length];
        final String[] examples = new String[KINDS.length];

        void fail(final int kind, final String example) {
            if (fails[kind]++ == 0) {
                examples[kind] = example;
            }
        }
    }

    final BlockPos pos = new BlockPos(X, Y, Z);
    final List<BlockState> states = new ArrayList<>();
    final Map<String, Result> results = new TreeMap<>();
    final List<String> shulkerFails = new ArrayList<>();
    int index, errors, lenient;
    String firstError = "";
    World world;
    ServerLevel level;
    MCAccess mcAccess;

    @Override
    public void onEnable() {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> start(), 100);
    }

    void start() {
        world = Bukkit.getWorlds().get(0);
        level = ((CraftWorld) world).getHandle();
        mcAccess = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstanceHandle(MCAccess.class).getHandle();
        for (Block b : BuiltInRegistries.BLOCK) {
            states.addAll(b.getStateDefinition().getPossibleStates());
        }
        world.getChunkAtAsync(X >> 4, Z >> 4).thenAccept(c -> next());
    }

    void next() {
        Bukkit.getRegionScheduler().runDelayed(this, world, X >> 4, Z >> 4, t -> batch(), 1);
    }

    void batch() {
        final long end = System.nanoTime() + 25_000_000L;
        while (index < states.size() && System.nanoTime() < end) {
            try {
                auditOne(states.get(index++));
            }
            catch (Throwable t) {
                if (errors++ == 0) {
                    firstError = states.get(index - 1) + ": " + t;
                }
            }
        }
        if (index < states.size()) {
            next();
            return;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        shulker();
        report();
        Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
    }

    BlockCache cache() {
        final BlockCache bc = mcAccess.getBlockCache();
        bc.setAccess(world);
        return bc;
    }

    void auditOne(final BlockState state) {
        level.setBlock(pos, state, FLAGS);
        final BlockState placed = level.getBlockState(pos);
        final Result r = results.computeIfAbsent(BuiltInRegistries.BLOCK.getKey(placed.getBlock()).getPath(), k -> new Result());
        r.states++;
        final BlockCache bc = cache();
        final IBlockCacheNode node = bc.getOrCreateBlockCacheNode(X, Y, Z, true);
        final long flags = BlockFlags.getBlockFlags(node.getType());
        // NCP models liquids by level, not by shape.
        if ((flags & BlockFlags.F_LIQUID) == 0) {
            collision(r, placed, bc, node, flags);
            ground(r, placed, bc);
        }
        bc.cleanup();
        sight(r, placed);
    }

    void collision(final Result r, final BlockState placed, final BlockCache bc, final IBlockCacheNode node, final long flags) {
        final double[] bounds = node.getBounds(bc, X, Y, Z);
        // Open gates and trapdoors are passable by data (BlockProperties.isPassableWorkaround).
        final boolean open = (flags & BlockFlags.F_PASSABLE_X4) != 0 && (node.getData(bc, X, Y, Z) & 0x4) != 0;
        final boolean solid = (flags & BlockFlags.F_GROUND) != 0 && bounds != null && !open;
        final List<AABB> vanilla = placed.getCollisionShape(level, pos).toAabbs();
        final double[] range = range(bounds, vanilla);
        int onlyNcp = 0, onlyVanilla = 0;
        // 1/16 grid.
        for (double px = range[0] + GRID_START; px < range[3]; px += 1 / 16.0) {
            for (double py = range[1] + GRID_START; py < range[4]; py += 1 / 16.0) {
                for (double pz = range[2] + GRID_START; pz < range[5]; pz += 1 / 16.0) {
                    final boolean n = solid && BlockProperties.collidesBlock(bc, X + px, Y + py, Z + pz, X + px, Y + py, Z + pz,
                            X, Y, Z, node, null, flags);
                    if (n != inside(vanilla, px, py, pz)) {
                        if (n) {
                            onlyNcp++;
                        }
                        else {
                            onlyVanilla++;
                        }
                    }
                }
            }
        }
        if (onlyNcp + onlyVanilla > 0) {
            r.fail(0, String.format("%s onlyNcp=%.3f onlyVanilla=%.3f flags=%s%n      ncp=%s%n      vanilla=%s",
                    placed, onlyNcp / 4096.0, onlyVanilla / 4096.0, BlockFlags.getFlagNames(flags), Arrays.toString(bounds), vanilla));
        }
    }

    /** The block up to 1.5 high, widened in 1/16 steps (at most 0.5) where a shape reaches out. */
    static double[] range(final double[] bounds, final List<AABB> vanilla) {
        final double[] r = {0.0, 0.0, 0.0, 1.0, 1.5, 1.0};
        if (bounds != null) {
            for (int i = 0; i + 5 < bounds.length; i += 6) {
                for (int a = 0; a < 3; a++) {
                    r[a] = Math.min(r[a], bounds[i + a]);
                    r[a + 3] = Math.max(r[a + 3], bounds[i + a + 3]);
                }
            }
        }
        for (AABB b : vanilla) {
            r[0] = Math.min(r[0], b.minX);
            r[1] = Math.min(r[1], b.minY);
            r[2] = Math.min(r[2], b.minZ);
            r[3] = Math.max(r[3], b.maxX);
            r[4] = Math.max(r[4], b.maxY);
            r[5] = Math.max(r[5], b.maxZ);
        }
        for (int a = 0; a < 6; a++) {
            final double v = (a < 3 ? Math.floor(r[a] * 16.0) : Math.ceil(r[a] * 16.0)) / 16.0;
            r[a] = Math.max(-0.5, Math.min(1.5, v));
        }
        return r;
    }

    static boolean inside(final List<AABB> boxes, final double x, final double y, final double z) {
        for (AABB a : boxes) {
            if (a.minX < x && x < a.maxX && a.minY < y && y < a.maxY && a.minZ < z && z < a.maxZ) {
                return true;
            }
        }
        return false;
    }

    void ground(final Result r, final BlockState placed, final BlockCache bc) {
        final List<AABB> vanilla = placed.getCollisionShape(level, pos).toAabbs();
        // Columns every 1/8 block, including parts that reach into neighbor blocks.
        for (double px = -7 / 16.0; px < 1.5; px += 1 / 8.0) {
            for (double pz = -7 / 16.0; pz < 1.5; pz += 1 / 8.0) {
                double top = Double.NaN;
                for (AABB a : vanilla) {
                    if (a.minX < px && px < a.maxX && a.minZ < pz && pz < a.maxZ) {
                        top = Double.isNaN(top) ? a.maxY : Math.max(top, a.maxY);
                    }
                }
                // Feet box like RichBoundsLocation.isOnGround: from yOnGround below the feet up to them.
                if (!Double.isNaN(top) && !BlockProperties.isOnGround(bc, X + px - 0.05, Y + top - 0.001, Z + pz - 0.05,
                        X + px + 0.05, Y + top, Z + pz + 0.05, 0L)) {
                    r.fail(1, String.format("%s column=%.4f,%.4f top=%.4f", placed, px, pz, top));
                    return;
                }
            }
        }
    }

    void sight(final Result r, final BlockState placed) {
        if (placed.getShape(level, pos).isEmpty()) {
            return;
        }
        for (final Direction d : Direction.values()) {
            final BlockPos target = pos.relative(d);
            level.setBlock(target, Blocks.STONE.defaultBlockState(), FLAGS);
            for (final double off : new double[] {0.0, -0.4, 0.4}) {
                // Eye 1.7 blocks in front of the block's center, target block right behind it.
                final double ex = X + 0.5 - 1.7 * d.getStepX() + (d.getAxis() == Direction.Axis.X ? 0.0 : off);
                final double ey = Y + 0.5 - 1.7 * d.getStepY();
                final double ez = Z + 0.5 - 1.7 * d.getStepZ() + (d.getAxis() == Direction.Axis.X ? off : 0.0);
                final BlockCache bc = cache();
                final boolean ncp = CollisionUtil.canSeeBox(bc, ex, ey, ez,
                        target.getX(), target.getY(), target.getZ(), target.getX() + 1, target.getY() + 1, target.getZ() + 1,
                        target.getX(), target.getY(), target.getZ());
                bc.cleanup();
                final boolean vanilla = vanillaSees(new Vec3(ex, ey, ez), target);
                if (!ncp && vanilla) {
                    r.fail(2, String.format("%s seen from %s, offset %.1f", placed, d.getOpposite(), off));
                }
                else if (ncp && !vanilla) {
                    lenient++;
                }
            }
            level.setBlock(target, Blocks.AIR.defaultBlockState(), FLAGS);
        }
    }

    /** Vanilla ray tracing against outline shapes (like the client), to the points canSeeBox uses. */
    boolean vanillaSees(final Vec3 eye, final BlockPos target) {
        for (final double sX : SAMPLES) {
            for (final double sY : SAMPLES) {
                for (final double sZ : SAMPLES) {
                    final Vec3 point = new Vec3(target.getX() + sX, target.getY() + sY, target.getZ() + sZ);
                    final BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE, CollisionContext.empty()));
                    if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Ground on shulker box lids, which move with the block entity. */
    void shulker() {
        level.setBlock(pos, Blocks.SHULKER_BOX.defaultBlockState(), 3);
        final ShulkerBoxBlockEntity upright = (ShulkerBoxBlockEntity) level.getBlockEntity(pos);
        upright.triggerEvent(1, 1); // One viewer: lid opens.
        for (int step = 0; step < 28; step++) {
            if (step == 14) {
                upright.triggerEvent(1, 0); // Viewer gone: lid closes.
            }
            final BlockState state = level.getBlockState(pos);
            ShulkerBoxBlockEntity.tick(level, pos, state, upright);
            final double top = state.getCollisionShape(level, pos).max(Direction.Axis.Y);
            if (!onGround(0.2, 0.8, top)) {
                shulkerFails.add("upright lid, step " + step + ": no ground on the lid at " + top);
            }
        }
        // Facing north, the lid reaches 0.5 into the block at z - 1.
        level.setBlock(pos, Blocks.SHULKER_BOX.defaultBlockState().setValue(ShulkerBoxBlock.FACING, Direction.NORTH), 3);
        final ShulkerBoxBlockEntity sideways = (ShulkerBoxBlockEntity) level.getBlockEntity(pos);
        sideways.triggerEvent(1, 1);
        for (int i = 0; i < 12; i++) {
            ShulkerBoxBlockEntity.tick(level, pos, level.getBlockState(pos), sideways);
        }
        if (!onGround(-0.9, -0.3, 1.0)) {
            shulkerFails.add("sideways lid: no ground on the part that sticks out");
        }
        if (onGround(-1.2, -0.6, 1.0)) {
            shulkerFails.add("sideways lid: ground past the lid");
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
    }

    /** Feet box x 0.2 - 0.8 on top of the block, z range relative to it. */
    boolean onGround(final double minDz, final double maxDz, final double top) {
        final BlockCache bc = cache();
        final boolean ground = BlockProperties.isOnGround(bc, X + 0.2, Y + top - 0.001, Z + minDz, X + 0.8, Y + top, Z + maxDz, 0L);
        bc.cleanup();
        return ground;
    }

    /** Lines "kind block", comments after #. */
    static Set<String> allowlist() {
        final Set<String> allowed = new HashSet<>();
        final String path = System.getProperty("audit.allowlist");
        if (path == null) {
            return allowed;
        }
        try {
            for (String line : Files.readAllLines(Paths.get(path))) {
                line = line.replaceAll("#.*", "").trim().replaceAll("\\s+", " ");
                if (!line.isEmpty()) {
                    allowed.add(line);
                }
            }
        }
        catch (Exception ex) {
            throw new IllegalStateException("allowlist: " + ex, ex);
        }
        return allowed;
    }

    void report() {
        getDataFolder().mkdirs();
        try (PrintWriter out = new PrintWriter(new File(getDataFolder(), "report.txt"))) {
            final Set<String> allowed = allowlist();
            final Set<String> used = new HashSet<>();
            int total = 0;
            int failures = errors > 0 ? 1 : 0;
            for (Map.Entry<String, Result> e : results.entrySet()) {
                final Result r = e.getValue();
                total += r.states;
                for (int k = 0; k < KINDS.length; k++) {
                    if (r.fails[k] == 0) {
                        continue;
                    }
                    final String key = KINDS[k] + " " + e.getKey();
                    final boolean ok = allowed.contains(key);
                    if (ok) {
                        used.add(key);
                    }
                    else {
                        failures++;
                    }
                    out.printf("%s %s %d/%d%n    %s%n", ok ? "ALLOWED" : "FAIL", key, r.fails[k], r.states, r.examples[k]);
                }
            }
            for (String s : shulkerFails) {
                out.println("FAIL shulker " + s);
                failures++;
            }
            for (String key : allowed) {
                if (!used.contains(key)) {
                    out.println("STALE allowlist entry, passes now: " + key);
                }
            }
            out.println("states=" + total + " blocks=" + results.size() + " sightLenient=" + lenient + " errors=" + errors
                    + (errors > 0 ? " first error: " + firstError : ""));
            out.println(failures == 0 ? "RESULT: PASS" : "RESULT: FAIL " + failures);
        }
        catch (Exception ex) {
            getLogger().severe("report: " + ex);
        }
    }
}
