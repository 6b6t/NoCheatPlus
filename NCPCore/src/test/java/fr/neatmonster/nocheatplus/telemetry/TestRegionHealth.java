package fr.neatmonster.nocheatplus.telemetry;

import static org.junit.Assert.*;
import java.lang.reflect.*;
import org.junit.Test;

public class TestRegionHealth {
    public static class Handle {
        public long getTickReport15s(long now) { return now; }
        public double utilisation() { return 0.25; }
    }
    @Test public void positiveAndNegativeRegionMethodsAreCachedPerRuntimeClass() throws Exception {
        Handle handle = new Handle();
        assertEquals(0.25, RegionHealth.call(handle,"utilisation"));
        Field field=RegionHealth.class.getDeclaredField("METHODS");field.setAccessible(true);
        ClassValue<?> cache=(ClassValue<?>)field.get(null);
        var methods=(java.util.Map<?,?>)cache.get(Handle.class);Object method=methods.get("utilisation");
        for(int i=0;i<100;i++)assertEquals(0.25,RegionHealth.call(handle,"utilisation"));
        assertSame(method,methods.get("utilisation"));
        assertTrue((long)RegionHealth.call(handle,"getTickReport15s")>0);
        for(int i=0;i<2;i++){
            try{RegionHealth.call(handle,"absent");fail();}catch(NoSuchMethodException expected){}
        }
        assertEquals(java.util.Optional.empty(),methods.get("absent"));
        assertEquals("region-api-unavailable",RegionHealth.capture().get("unavailable"));
    }
}
