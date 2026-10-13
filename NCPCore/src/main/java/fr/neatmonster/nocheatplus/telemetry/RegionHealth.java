package fr.neatmonster.nocheatplus.telemetry;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Resolve optional Folia report APIs once, including negative lookups. */
public final class RegionHealth {
    private static final Method CURRENT = currentMethod();
    private static final ClassValue<Map<String, Optional<Method>>> METHODS = new ClassValue<>() {
        @Override protected Map<String, Optional<Method>> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private static Method currentMethod() {
        try { return Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler").getMethod("getCurrentRegion"); }
        catch (ReflectiveOperationException | LinkageError e) { return null; }
    }
    static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = method(target, name);
        return name.equals("getTickReport15s") ? method.invoke(target, System.nanoTime()) : method.invoke(target);
    }
    private static Method method(Object target, String name) throws NoSuchMethodException {
        Optional<Method> cached = METHODS.get(target.getClass()).computeIfAbsent(name, key -> {
            try { return Optional.of(key.equals("getTickReport15s") ? target.getClass().getMethod(key, long.class) : target.getClass().getMethod(key)); }
            catch (NoSuchMethodException e) { return Optional.empty(); }
        });
        if (cached.isEmpty()) throw new NoSuchMethodException(name);
        return cached.get();
    }
    public static Map<String, Object> capture() {
        if (CURRENT == null) return Collections.singletonMap("unavailable", "region-api-unavailable");
        try {
            Object region = CURRENT.invoke(null);
            if (region == null) return Collections.singletonMap("unavailable", "no-owning-region");
            Object handle = call(call(region, "getData"), "getRegionSchedulingHandle");
            Object report = call(handle, "getTickReport15s");
            if (report == null) return Collections.singletonMap("unavailable", "region-report-not-ready");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tps_15s", call(call(call(report, "tpsData"), "segmentAll"), "average"));
            result.put("utilisation", call(report, "utilisation"));
            return result;
        } catch (ReflectiveOperationException | LinkageError e) { return Collections.singletonMap("unavailable", "region-api-unavailable"); }
    }
}
