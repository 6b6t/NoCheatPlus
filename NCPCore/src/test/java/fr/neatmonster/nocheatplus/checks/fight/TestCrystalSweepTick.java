package fr.neatmonster.nocheatplus.checks.fight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestCrystalSweepTick {

    @Test
    public void onlySweepDamageInCrystalHitTickIsTagged() {
        assertTrue(FightListener.isCrystalSweep(120, 120));
        assertFalse(FightListener.isCrystalSweep(121, 120));
        assertFalse(FightListener.isCrystalSweep(120, -1));
    }
}
