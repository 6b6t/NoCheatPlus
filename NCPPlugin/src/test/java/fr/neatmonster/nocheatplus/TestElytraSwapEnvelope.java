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
package fr.neatmonster.nocheatplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.ElytraResidual;

/** Elytra unequip window: the legit lag and coast pass, held speed and hovering fail. */
public class TestElytraSwapEnvelope {

    /** CreativeFly elytra limit on live: horizontal speed 450 x 0.6. */
    private static final double CAP = 2.7;

    /** Live commit the change is based on. */
    private static final String BASE = "6b87164494266f6e2bec3719c5cf93adb57bb382";

    private static double fall(final double y) {
        return (y - 0.08) * 0.98;
    }

    @Test
    public void settledCoastIsAcceptedUntilItClosesAtNormalAirSpeed() {
        for (final int ping : new int[] {0, 160, 300}) {
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, 1.8, -0.15, ping, CAP));
            double h = 1.8;
            double y = -0.15;
            int moves = 0;
            while (true) {
                h *= 0.91;
                y = fall(y);
                moves++;
                assertTrue("ping " + ping + " coast move " + moves + " h " + h, ElytraResidual.allow(state, h, y, false, false));
                if (h <= ElytraResidual.NORMAL_AIR_H) {
                    break;
                }
                assertTrue(state.open);
            }
            assertFalse("closed at a normal air move", state.open);
            assertFalse(ElytraResidual.allow(state, h * 0.91, fall(y), false, false));
            assertEquals(18, moves);
        }
        // Measured vanilla coast after the lag (ELYTRA-V2 section 1).
        final ElytraResidual.State measured = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(measured, 0.786, -0.15, 0, CAP));
        for (int i = 0; i < measured.lagMoves; i++) {
            assertTrue(ElytraResidual.allow(measured, 0.786, -0.15, false, false));
        }
        double y = -0.15;
        for (final double h : new double[] {0.715, 0.651}) {
            y = fall(y);
            assertTrue("measured " + h, ElytraResidual.allow(measured, h, y, false, false));
        }
        assertTrue(measured.open);
    }

    @Test
    public void lagGrowthThenDragIsAccepted() {
        for (final int ping : new int[] {50, 160}) {
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, 1.45, -0.15, ping, CAP));
            for (final double h : new double[] {1.47, 1.49, 1.51, 1.61}) {
                assertTrue("ping " + ping + " lag " + h, ElytraResidual.allow(state, h, -0.15, false, false));
            }
            double h = 1.61;
            double y = -0.15;
            int moves = 4;
            while (state.open) {
                h *= 0.91;
                y = fall(y);
                moves++;
                assertTrue("ping " + ping + " move " + moves + " h " + h, ElytraResidual.allow(state, h, y, false, false));
            }
            assertTrue(h <= ElytraResidual.NORMAL_AIR_H);
        }
    }

    @Test
    public void heldSpeedOrHoverFailsOnTheFirstCoastMove() {
        final ElytraResidual.State held = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(held, 1.55, -0.15, 0, CAP));
        assertEquals(3, held.lagMoves);
        for (int i = 0; i < held.lagMoves; i++) {
            assertTrue("lag " + i, ElytraResidual.allow(held, 1.55, -0.15, false, false));
        }
        assertFalse("constant 1.55", ElytraResidual.allow(held, 1.55, fall(-0.15), false, false));
        assertFalse(held.open);

        final ElytraResidual.State hover = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(hover, 1.55, 0.0, 160, CAP));
        double h = 1.55;
        for (int i = 0; i < hover.lagMoves; i++) {
            h *= 0.91;
            assertTrue("lag " + i, ElytraResidual.allow(hover, h, 0.0, false, false));
        }
        assertFalse("hover at y=0", ElytraResidual.allow(hover, h * 0.91, 0.0, false, false));
        assertFalse(hover.open);

        // A glide that keeps its vertical speed after the lag is not a coast either.
        final ElytraResidual.State glide = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(glide, 1.55, -0.15, 0, CAP));
        for (int i = 0; i < glide.lagMoves; i++) {
            assertTrue(ElytraResidual.allow(glide, 1.40, -0.15, false, false));
        }
        assertFalse(ElytraResidual.allow(glide, 1.40 * 0.91, -0.15, false, false));
    }

    @Test
    public void lagIsCappedAtTheElytraLimitAndTenMoves() {
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 2.65, -0.15, 0, CAP));
        assertFalse("above the elytra cap", ElytraResidual.allow(state, 2.75, -0.15, false, false));
        assertFalse(state.open);

        final ElytraResidual.State clamped = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(clamped, 3.5, -0.15, 0, CAP));
        assertEquals(CAP, clamped.lastH, 0.0);
        assertFalse(ElytraResidual.allow(clamped, 2.71, -0.15, false, false));

        final ElytraResidual.State growth = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(growth, 1.0, -0.15, 0, CAP));
        assertFalse("more than 0.15 growth", ElytraResidual.allow(growth, 1.16, -0.15, false, false));

        assertEquals(3, ElytraResidual.lagMoves(0));
        assertEquals(3, ElytraResidual.lagMoves(-5));
        assertEquals(4, ElytraResidual.lagMoves(1));
        assertEquals(7, ElytraResidual.lagMoves(160));
        assertEquals(9, ElytraResidual.lagMoves(300));
        assertEquals(10, ElytraResidual.lagMoves(2000));
        assertEquals(10, ElytraResidual.lagMoves(Integer.MAX_VALUE));

        final ElytraResidual.State slow = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(slow, 1.55, -0.15, 2000, CAP));
        assertEquals(10, slow.lagMoves);
        for (int i = 0; i < 10; i++) {
            assertTrue("lag " + i, ElytraResidual.allow(slow, 1.55, -0.15, false, false));
        }
        assertFalse("11th held move", ElytraResidual.allow(slow, 1.55, fall(-0.15), false, false));
    }

    @Test
    public void levelWindowRejectsAFreshDiveAndAClimb() {
        final ElytraResidual.State dive = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(dive, 1.5, -0.15, 160, CAP));
        assertFalse("fresh -3.9", ElytraResidual.allow(dive, 1.4, -3.9, false, false));
        assertFalse(dive.open);

        final ElytraResidual.State steeper = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(steeper, 1.5, -0.15, 160, CAP));
        assertTrue(ElytraResidual.allow(steeper, 1.5, -0.44, false, false));
        assertTrue(ElytraResidual.allow(steeper, 1.5, -0.73, false, false));
        assertFalse("more than 0.30 below the last move", ElytraResidual.allow(steeper, 1.5, -1.05, false, false));

        final ElytraResidual.State climb = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(climb, 1.5, -0.15, 160, CAP));
        assertFalse("climb above the start + 0.15", ElytraResidual.allow(climb, 1.4, 0.01, false, false));

        final ElytraResidual.State floor = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(floor, 1.5, -3.80, 160, CAP));
        assertFalse("below the -3.95 floor", ElytraResidual.allow(floor, 1.4, -3.96, false, false));
    }

    @Test
    public void oneWindowPerFortyMovesAndEveryEndCondition() {
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));
        assertFalse("already open", ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));
        assertTrue(ElytraResidual.allow(state, 1.5, -0.15, false, false));
        ElytraResidual.end(state);
        assertFalse(ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));
        // 1 counted so far; 38 more SurvivalFly moves plus one CreativeFly move make 40.
        for (int i = 0; i < 38; i++) {
            assertFalse(ElytraResidual.allow(state, 0.2, -0.08, false, false));
        }
        assertFalse("39 moves", ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));
        ElytraResidual.creativeFlyMove(state);
        assertTrue("40 moves", ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));

        // A CreativeFly move (re-equip) ends the window.
        final ElytraResidual.State reequip = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(reequip, 1.5, -0.15, 0, CAP));
        assertTrue(ElytraResidual.allow(reequip, 1.5, -0.15, false, false));
        ElytraResidual.creativeFlyMove(reequip);
        assertFalse(reequip.open);
        assertFalse(ElytraResidual.allow(reequip, 1.5, -0.15, false, false));

        // Ground ends it.
        final ElytraResidual.State ground = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(ground, 1.5, -0.15, 0, CAP));
        assertFalse(ElytraResidual.allow(ground, 1.0, 0.0, true, false));
        assertFalse(ground.open);
        assertFalse(ElytraResidual.allow(ground, 1.0, -0.15, false, false));

        // Liquid or climbable (reset condition) ends it.
        final ElytraResidual.State liquid = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(liquid, 1.5, -0.15, 0, CAP));
        assertFalse(ElytraResidual.allow(liquid, 1.0, -0.15, false, true));
        assertFalse(liquid.open);
        assertFalse(ElytraResidual.allow(liquid, 1.0, -0.15, false, false));

        // A move outside the window ends it for good.
        final ElytraResidual.State broken = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(broken, 1.5, -0.15, 0, CAP));
        assertFalse(ElytraResidual.allow(broken, 2.0, -0.15, false, false));
        assertFalse(ElytraResidual.allow(broken, 1.5, -0.15, false, false));

        // Unusable history never opens a window.
        assertFalse(ElytraResidual.begin(new ElytraResidual.State(), Double.NaN, -0.15, 0, CAP));
        assertFalse(ElytraResidual.begin(new ElytraResidual.State(), 1.5, Double.POSITIVE_INFINITY, 0, CAP));
        assertFalse(ElytraResidual.begin(new ElytraResidual.State(), 1.5, -0.15, 0, 0.0));
    }

    @Test
    public void coastCannotHoverAboveTheCloseThreshold() {
        // Drag plus margin settles near 0.44; the coast still ends after MAX_COAST_MOVES.
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 0.5, -0.15, 0, CAP));
        double h = 0.5;
        double y = -0.15;
        for (int i = 0; i < state.lagMoves; i++) {
            assertTrue(ElytraResidual.allow(state, h, y, false, false));
        }
        int coast = 0;
        while (true) {
            h = h * ElytraResidual.COAST_DRAG + ElytraResidual.COAST_H_MARGIN;
            y = fall(y);
            if (!ElytraResidual.allow(state, h, y, false, false)) {
                break;
            }
            coast++;
            assertTrue(coast <= ElytraResidual.MAX_COAST_MOVES);
        }
        assertEquals(ElytraResidual.MAX_COAST_MOVES, coast);
        assertFalse(state.open);
    }

    // Source check -------------------------------------------------------------

    private static final String MOVING = "NCPCore/src/main/java/fr/neatmonster/nocheatplus/checks/moving/";

    /** Tokens of a stored credential, server-made motion, scheduling, clock or callback. */
    private static final String[] FORBIDDEN = {
            "PersistentDataContainer", "PersistentDataType", "NamespacedKey", "getPersistentDataContainer",
            "setVelocity", "getScheduler", "BukkitRunnable", "BukkitScheduler", "runTask", "runTaskLater",
            "RegionScheduler", "EntityScheduler", "Consumer<", "Runnable", "Callable",
            "ElytraResidualCarry", "currentTimeMillis", "nanoTime",
    };

    private static File root() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, MOVING + "ElytraResidual.java").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("NCPCore sources not found");
    }

    private static String read(final File root, final String path) throws IOException {
        return new String(Files.readAllBytes(new File(root, path).toPath()), StandardCharsets.UTF_8);
    }

    private static int count(final String text, final String token) {
        int n = 0;
        int i = text.indexOf(token);
        while (i >= 0) {
            n++;
            i = text.indexOf(token, i + token.length());
        }
        return n;
    }

    @Test
    public void elytraResidualHasNoStoredCredentialMotionSchedulerOrCallback() throws IOException {
        final String source = read(root(), MOVING + "ElytraResidual.java");
        for (final String token : FORBIDDEN) {
            assertEquals(token, 0, count(source, token));
        }
        for (final String token : new String[] {"import org.bukkit", "import java.util.UUID", "System."}) {
            assertEquals(token, 0, count(source, token));
        }
        // No static callback or other mutable static state: every static field is a final constant.
        for (final Field field : ElytraResidual.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
                assertTrue(field.getName(), field.getType().isPrimitive());
            }
        }
        for (final Field field : ElytraResidual.State.class.getDeclaredFields()) {
            assertFalse(field.getName(), Modifier.isStatic(field.getModifiers()));
            assertTrue(field.getName(), field.getType().isPrimitive());
        }
    }

    @Test
    public void wiringAddsNoStoredCredentialMotionSchedulerOrCallback() throws IOException, InterruptedException {
        final File root = root();
        // Pinned to the live base: the change may not add any of these to the files it touches.
        final String[][] pinned = {
                {"MovingListener.java", "currentTimeMillis", "7"},
                {"player/SurvivalFly.java", "setVelocity", "3"},
                {"player/SurvivalFly.java", "currentTimeMillis", "2"},
                {"MovingData.java", "setVelocity", "4"},
                {"MovingData.java", "currentTimeMillis", "3"},
                {"MovingData.java", "Callable", "1"},
        };
        for (final String file : new String[] {"MovingListener.java", "player/SurvivalFly.java", "MovingData.java"}) {
            final String source = read(root, MOVING + file);
            for (final String token : FORBIDDEN) {
                int expected = 0;
                for (final String[] pin : pinned) {
                    if (pin[0].equals(file) && pin[1].equals(token)) {
                        expected = Integer.parseInt(pin[2]);
                    }
                }
                assertEquals(file + " " + token, expected, count(source, token));
            }
        }
        // When this is a git checkout of the branch, check the added lines of the diff itself.
        final List<String> added = addedLinesSinceBase(root);
        if (added != null) {
            assertFalse("diff is empty", added.isEmpty());
            for (final String line : added) {
                for (final String token : FORBIDDEN) {
                    assertFalse(token + " in: " + line, line.contains(token));
                }
                assertFalse(line, line.contains("import org.bukkit.persistence"));
                assertFalse(line, line.contains("static Consumer") || line.contains("static Runnable"));
            }
        }
    }

    /** Added main-source lines since {@link #BASE}, or null without git or the base commit. */
    private static List<String> addedLinesSinceBase(final File root) throws IOException, InterruptedException {
        final Process process;
        try {
            process = new ProcessBuilder("git", "-C", root.getPath(), "diff", "--unified=0", BASE, "--",
                    "NCPCore/src/main", "NCPCompatBukkit/src/main", "NCPPlugin/src/main")
                    .redirectErrorStream(true).start();
        }
        catch (final IOException e) {
            return null;
        }
        final List<String> lines = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    lines.add(line);
                }
            }
        }
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            return null;
        }
        return lines;
    }
}
