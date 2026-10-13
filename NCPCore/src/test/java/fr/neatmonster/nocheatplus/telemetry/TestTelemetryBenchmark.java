package fr.neatmonster.nocheatplus.telemetry;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;

/** Repeatable microbenchmark, not a production or live-server performance assertion. */
public class TestTelemetryBenchmark {
    private static volatile Object blackhole;
    private Map<String, Object> setback() {
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("kind","setback"); r.put("time",System.currentTimeMillis()); r.put("worker","fox-eu-1"); r.put("uuid","00000000-0000-0000-0000-000000000001");
        r.put("name","BenchmarkPlayer"); r.put("check","moving.survivalfly"); r.put("tags","hdist+air"); r.put("vl",10d);
        r.put("from",List.of(1d,64d,1d,0f,0f));r.put("to",List.of(2d,64d,1d,0f,0f));
        r.put("flags",Map.of("gliding",true,"elytra_equipped",true,"on_ground",false,"rocket_remaining_ticks",10,"held",List.of("MACE","WIND_CHARGE"),"armour",List.of("AIR","ELYTRA","AIR","AIR")));
        List<Object> history=new ArrayList<>();for(int i=0;i<20;i++)history.add(List.of(50L,(double)i,64d,1d,0f,0f,false));r.put("history",history);
        List<Object> cells=new ArrayList<>();for(int i=0;i<36;i++)cells.add(List.of("minecraft:obsidian","256",2));
        Map<String,Object> blocks=Map.of("origin",List.of(0,63,0),"size",List.of(3,4,3),"cells",cells);
        r.put("blocks_from",blocks);r.put("blocks_to",blocks);return r;
    }
    private Map<String, Object> sample() { return Map.of("kind","move","time",System.currentTimeMillis(),"worker","fox-eu-1","uuid","00000000-0000-0000-0000-000000000001","dt",50,"pos",List.of(1d,64d,1d,0f,0f),"flags",List.of(false,true,false,0f),"bot",false); }
    @Test public void benchmarkImmutableCaptureOfferAndWriterThroughput() throws Exception {
        Path folder=Files.createTempDirectory("ncp-benchmark").toRealPath(); TelemetryWriter w=new TelemetryWriter(folder,"worker",50000,0,Long.MAX_VALUE,()->Long.MAX_VALUE,s->{});
        // Warm both construction paths, then measure bounded capture+offer, including full-queue drops.
        for(int i=0;i<10000;i++){blackhole=new TelemetryRecord(1,setback());blackhole=new TelemetryRecord(1,sample());}
        int count=10000;long started=System.nanoTime();
        for(int i=0;i<count;i++)w.offer(new TelemetryRecord(1,setback()));
        long setbackNs=(System.nanoTime()-started)/count; started=System.nanoTime();
        for(int i=0;i<count;i++)w.offer(new TelemetryRecord(1,sample()));
        long sampleNs=(System.nanoTime()-started)/count;
        TelemetryRecord record=new TelemetryRecord(1,setback());started=System.nanoTime();
        for(int i=0;i<count;i++)w.write(record,1000);w.close();
        long elapsed=System.nanoTime()-started;
        System.out.println("BENCH capture+offer setback_ns="+setbackNs+" sampled_move_ns="+sampleNs+" writer_records_per_sec="+(long)(count*1e9/elapsed)+" payload_bytes="+record.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+" (writer includes close+gzip; capture excludes Bukkit reads)");
    }
}
