package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

/** Accepted-movement boundary tests, NOT a Minecraft packet decoder/physics simulator.
 * G/R and item actions have no XYZ: the adapter records them without inventing a move.
 * Terrain flags below are authoritative fixtures, never the client's ground claim.
 * Native distance is deliberately erased after claimed ground to stress restoration.
 */
@RunWith(Parameterized.class)
public class TestClientNoFallSequences {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases() {
        List<Object[]> out=new ArrayList<>();
        String[] ordinary={"Vanilla/control", "Meteor/Packet", "Autism/Packet/main", "Autism/Packet/pilot",
            "Wurst/NoFall", "BleachHack/Simple", "BleachHack/NoGround", "Lambda/Grim",
            "LB/SpoofGround/reset-on", "LB/SpoofGround/reset-off", "LB/NoGround",
            "LB/Packet/Full", "LB/Packet/OnGroundOnly", "LB/Packet/PositionAndOnGround", "LB/Packet/LookAndOnGround",
            "LB/Spartan524Flag", "LB/HypixelPacket", "LB/Verus", "LB/Vulcan277",
            "LB/PacketJump/Landing/Full", "LB/PacketJump/Landing/Position", "LB/PacketJump/Falling/Full", "LB/PacketJump/Falling/Position",
            "LB/Hypixel", "LB/BlocksMC", "LB/Blink/landing", "LB/Blink/max-distance", "LB/Blink/disable",
            "LB/Cancel/reset-on", "LB/Cancel/reset-off/resumed", "LB/SpoofLanding/corrected",
            "LB/VulcanTP288/corrected", "BleachHack/Packet/corrected", "LB/ForceJump/rejected",
            "LB/Grim2371/next-position", "LB/Mount/rejected", "LB/Rettungsplatform/no-custom-effect"};
        for(String s:ordinary) out.add(new Object[]{s,"stone"});
        for(String client:new String[]{"Meteor","Autism/main","Autism/pilot"}) {
            for(String threshold:new String[]{"BeforeDamage","BeforeDeath"})
                out.add(new Object[]{client+"/AirPlace/"+threshold+"/rejected","stone"});
            for(String item:new String[]{"Bucket","PowderSnow","HayBale","Cobweb","SlimeBlock"}) {
                out.add(new Object[]{client+"/Place/"+item+"/rejected","stone"});
                out.add(new Object[]{client+"/Place/"+item+"/accepted",item});
            }
        }
        for(String item:new String[]{"Bucket","PowderSnow","HayBale","Cobweb","SlimeBlock","Scaffolding","Ladder"})
            out.add(new Object[]{"LB/MLG/"+item+"/accepted",item});
        return out;
    }
    final String name, terrain;
    public TestClientNoFallSequences(String name,String terrain){this.name=name;this.terrain=terrain;}
    static void field(Object o,Class<?> c,String n,Object v)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    @Test public void acceptedSequenceRetainsDescentUntilAuthoritativeContact() throws Exception {
        String old=ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try {
            Player player=mock(Player.class); IPlayerData pd=mock(IPlayerData.class);
            MovingConfig cc=mock(MovingConfig.class); MovingData data=mock(MovingData.class,CALLS_REAL_METHODS);
            MoveTrace trace=mock(MoveTrace.class); field(data,MovingData.class,"playerMoves",trace);
            NoFall check=mock(NoFall.class,CALLS_REAL_METHODS);
            when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
            float[] nativeDistance={0};
            when(player.getFallDistance()).thenAnswer(c->nativeDistance[0]);
            doAnswer(c->{nativeDistance[0]=c.getArgument(0);return null;}).when(player).setFallDistance(anyFloat());
            World world=mock(World.class);
            double y=93; data.noFallMaxY=y;
            // After the server rejects an impossible endpoint, only accepted descent is owed.
            for(double next:new double[]{92.5,91.5,90,88,85,83,81}) {
                if(name.contains("Cancel") && next==88) continue; // omitted wire position; next received delta spans it
                if(name.contains("Grim2371") && next==81) {
                    assertEquals(10,data.noFallFallDistance,1e-5); // status-only landing sends no destination
                }
                if(name.contains("Landing") && next==81) move(check,player,pd,cc,data,trace,y,y+1e-9,false,false);
                if(name.equals("Lambda/Grim") || name.contains("Falling"))
                    move(check,player,pd,cc,data,trace,y,y+1e-10,false,false);
                if(name.contains("Verus") || name.contains("Vulcan277"))
                    move(check,player,pd,cc,data,trace,y,y,false,false); // velocity-stop subsequent position
                if(name.contains("corrected") || name.contains("rejected") && name.contains("ForceJump")) {
                    when(player.getLocation()).thenReturn(new Location(world,16,y,16));
                    check.checkDamage(player,-339.69,data,pd);
                    assertTrue("Rejected endpoint cannot add descent",nativeDistance[0]<=12.0001);
                }
                boolean contact=next==81;
                boolean reset=contact && Arrays.asList("Bucket","PowderSnow","Cobweb","Scaffolding","Ladder").contains(terrain);
                boolean slime=contact && terrain.equals("SlimeBlock");
                // Client flag-only packets/local reset/item uses cannot clear the check debt.
                nativeDistance[0]=0;
                move(check,player,pd,cc,data,trace,y,next,contact&&!reset&&!slime,reset);
                if(slime){PlayerLocation to=mock(PlayerLocation.class);when(to.getTypeIdBelow()).thenReturn(Material.SLIME_BLOCK);
                    when(to.isOnGround(anyDouble())).thenReturn(true);when(to.getY()).thenReturn(next);NoFall.consumeSlimeContact(to,data,.0625);}
                if(!contact) assertEquals(93-next,data.noFallFallDistance,1e-5);
                y=next;
            }
            if(!terrain.equals("stone")&&!terrain.equals("HayBale")) assertEquals(0,data.noFallFallDistance,1e-5);
            else {
                // Production restores PRE-packet distance; native adds the final two-block descent.
                assertEquals(12,nativeDistance[0]+2,1e-5);
                assertEquals(9,NoFall.getDamage(nativeDistance[0]+2),0);
            }
        } finally {ServerVersion.setMinecraftVersion(old);}
    }
    static void move(NoFall check,Player p,IPlayerData pd,MovingConfig cc,MovingData d,MoveTrace trace,
                     double from,double to,boolean ground,boolean reset) {
        PlayerMoveData m=new PlayerMoveData();m.from.setLocation("world",16,from,16,0,0);
        m.to.setLocation("world",16,to,16,0,0);m.toIsValid=true;m.yDistance=to-from;
        m.to.onGround=ground;m.to.resetCond=reset;
        when(trace.getCurrentMove()).thenReturn(m);
        PlayerLocation start=mock(PlayerLocation.class),end=mock(PlayerLocation.class);
        when(end.isOnGround(anyDouble())).thenReturn(ground);
        check.check(p,start,end,Double.NEGATIVE_INFINITY,d,cc,pd);
    }
}
