package fr.neatmonster.nocheatplus.telemetry;

import static org.junit.Assert.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.*;
import org.junit.Test;

public class TestTelemetryIdleBenchmark {
    @Test public void benchmarkIdleGuardAndRecoveryWithManyArchives() throws Exception {
        Method recovery = TelemetryWriter.class.getDeclaredMethod("recoverClosedHour", long.class); recovery.setAccessible(true);
        var cpu = ManagementFactory.getThreadMXBean();
        for (int count : new int[]{4, 104, 1004}) {
            Path folder = Files.createTempDirectory("ncp-idle").toRealPath();
            for (int i=0;i<count;i++) Files.writeString(folder.resolve("archive-"+i+".jsonl.gz"), "x");
            TelemetryWriter writer = new TelemetryWriter(folder,"worker",1,0,Long.MAX_VALUE,null,s->{});
            for(int i=0;i<5;i++){assertTrue(writer.diskAllowed());recovery.invoke(writer,System.currentTimeMillis());}
            long start = cpu.getCurrentThreadCpuTime();
            for(int i=0;i<100;i++){assertTrue(writer.diskAllowed());recovery.invoke(writer,System.currentTimeMillis());}
            long ns=(cpu.getCurrentThreadCpuTime()-start)/100;
            System.out.println("BENCH writer-idle files="+count+" cpu_ns_per_iteration="+ns+" projected_core_percent_at_10hz="+(ns/1e6));
        }
    }
}
