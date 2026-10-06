package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.utility.MinecraftVersion;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.checks.net.protocollib.MovingFlying;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;

/** Production decoder with observed modern field layouts. Full native packet
 * admission and observation-only registration are exercised in the private lab. */
public class TestNoFallPacketObservation {
    private static MinecraftVersion previousProtocolVersion;
    @BeforeClass public static void protocolVersion() throws Exception {
        var field=MinecraftVersion.class.getDeclaredField("currentVersion");field.setAccessible(true);
        previousProtocolVersion=(MinecraftVersion)field.get(null);
        MinecraftVersion.setCurrentVersion(new MinecraftVersion(26,2,0));
    }
    @AfterClass public static void restoreProtocolVersion() { MinecraftVersion.setCurrentVersion(previousProtocolVersion); }
    @Test public void modernHorizontalCollisionIsNotPositionOrLookAndLegacyLayoutStillWorks() {
        String previous=ServerVersion.getMinecraftVersion();
        try {
            for(String version:new String[]{"26.2","1.19.4"}) {
                ServerVersion.setMinecraftVersion(version);
                for(PacketType type:new PacketType[]{PacketType.Play.Client.POSITION,PacketType.Play.Client.POSITION_LOOK,PacketType.Play.Client.LOOK,PacketType.Play.Client.GROUND}) {
                    for(boolean ground:new boolean[]{false,true}) for(boolean collision:new boolean[]{false,true}) {
                        boolean pos=type.equals(PacketType.Play.Client.POSITION)||type.equals(PacketType.Play.Client.POSITION_LOOK);
                        boolean look=type.equals(PacketType.Play.Client.LOOK)||type.equals(PacketType.Play.Client.POSITION_LOOK);
                        List<Boolean> flags=version.equals("1.19.4")?Arrays.asList(ground,pos,look):Arrays.asList(ground,collision,pos,look);
                        DataPacketFlying got=MovingFlying.decodePacket(type,flags,Arrays.asList(16d,81d,16d),Arrays.asList(0f,90f),123L);
                        assertNotNull(got);assertEquals(ground,got.onGround);assertEquals(pos,got.hasPos);assertEquals(look,got.hasLook);
                        if(got.hasPos){assertEquals(16,got.getX(),0);assertEquals(81,got.getY(),0);assertEquals(16,got.getZ(),0);}
                    }
                }
            }
        } finally {ServerVersion.setMinecraftVersion(previous);}
    }
    @Test public void missingFieldsCannotInventAcceptedPositionOrGround() {
        String previous=ServerVersion.getMinecraftVersion();
        try {
            ServerVersion.setMinecraftVersion("26.2");
            assertNull(MovingFlying.decodePacket(PacketType.Play.Client.POSITION,Arrays.asList(false,false,true,false),Collections.emptyList(),Collections.emptyList(),123));
            assertNull(MovingFlying.decodePacket(PacketType.Play.Client.LOOK,Arrays.asList(false,false,false,true),Collections.emptyList(),Collections.emptyList(),123));
            assertNull(MovingFlying.decodePacket(PacketType.Play.Client.GROUND,Arrays.asList(false,false),Collections.emptyList(),Collections.emptyList(),123));
        } finally {ServerVersion.setMinecraftVersion(previous);}
    }
}
