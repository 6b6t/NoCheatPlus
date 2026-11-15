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
package fr.neatmonster.nocheatplus.compat.bukkit.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.bukkit.Material;
import org.bukkit.Bukkit;
import fr.neatmonster.nocheatplus.logging.StaticLog;

/** Versioned, sampled Bedrock shapes, loaded only for authenticated supported clients. */
public final class BedrockBlockShapes {
    private BedrockBlockShapes() {}

    public static boolean supportsVersion(final String version) {
        if (version == null) return false;
        final String normalized = version.startsWith("1.") ? version.substring(2) : version;
        final String[] parts = normalized.split("\\.");
        return parts.length >= 2 && parts[0].equals("26")
                && (parts[1].equals("50") || parts[1].equals("51") || parts[1].equals("52"));
    }

    public static boolean contains(final Material material) {
        return Data.MATERIALS.contains(material.getKey().toString());
    }

    public static double[] get(final String state) {
        return Data.STATES.get(state);
    }

    public static boolean permitsVanillaProfile(final String state) {
        return Geyser.ACCESS.permits(state);
    }

    private static final class Geyser {
        private static final RegistryAccess ACCESS = load();

        private static RegistryAccess load() {
            if (Bukkit.getServer() == null) return registryAccess(null);
            final var plugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
            if (plugin == null || !plugin.isEnabled()) return registryAccess(null);
            try {
                return registryAccess(plugin.getClass().getClassLoader().loadClass("org.geysermc.geyser.registry.BlockRegistries"));
            } catch (ClassNotFoundException | LinkageError failure) {
                StaticLog.logWarning("Unsupported Geyser custom-block registry; keeping native collision shapes.");
                return new RegistryAccess(null, null, null, null, false);
            }
        }
    }

    static RegistryAccess registryAccess(final Class<?> type) {
        if (type == null) return new RegistryAccess(null, null, null, null, true);
        try {
            final Object states = type.getField("JAVA_BLOCK_STATE_IDENTIFIER_TO_ID").get(null);
            final Object custom = type.getField("CUSTOM_BLOCK_STATE_OVERRIDES").get(null);
            return new RegistryAccess(states, states.getClass().getMethod("get"),
                    custom, custom.getClass().getMethod("get"), false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            StaticLog.logWarning("Unsupported Geyser custom-block registry; keeping native collision shapes.");
            return new RegistryAccess(null, null, null, null, false);
        }
    }

    record RegistryAccess(Object states, Method stateGetter, Object custom, Method customGetter, boolean absent) {
        boolean permits(final String state) {
            if (states == null) return absent;
            try {
                final Object id = ((Map<?, ?>) stateGetter.invoke(states)).get(state);
                return id != null && !((Map<?, ?>) customGetter.invoke(custom)).containsKey(id);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return false;
            }
        }
    }

    private static final class Data {
        private static final Map<String, double[]> STATES;
        private static final Set<String> MATERIALS;
        static {
            final var states = new HashMap<String, double[]>();
            final var materials = new HashSet<String>();
            final var shapes = new HashMap<Integer, double[]>();
            try (var resource = BedrockBlockShapes.class.getResourceAsStream("/bedrock/collisions-26.50.tsv.gz")) {
                if (resource == null) throw new IOException("Missing Bedrock collision resource");
                try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(resource), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        final String[] parts = line.split("\t", -1);
                        if (parts.length != 2) throw new IOException("Invalid Bedrock collision row");
                        if (parts[0].startsWith("@")) {
                            final String[] coordinates = parts[1].isEmpty() ? new String[0] : parts[1].split(",");
                            if (coordinates.length % 6 != 0) throw new IOException("Invalid Bedrock collision boxes");
                            final double[] bounds = new double[coordinates.length];
                            for (int i = 0; i < bounds.length; i++) {
                                bounds[i] = Double.parseDouble(coordinates[i]);
                                if (!Double.isFinite(bounds[i])) throw new IOException("Non-finite Bedrock collision");
                            }
                            for (int i = 0; i < bounds.length; i += 6) for (int axis = 0; axis < 3; axis++) {
                                if (bounds[i + axis] >= bounds[i + axis + 3]) throw new IOException("Empty or inverted Bedrock box");
                            }
                            if (shapes.put(Integer.parseInt(parts[0].substring(1)), bounds) != null) throw new IOException("Duplicate Bedrock shape");
                        } else {
                            final double[] shape = shapes.get(Integer.parseInt(parts[1]));
                            if (shape == null || states.put(parts[0], shape) != null) throw new IOException("Invalid Bedrock state reference");
                            materials.add(parts[0].split("\\[", 2)[0]);
                        }
                    }
                }
            } catch (IOException | RuntimeException failure) {
                throw new ExceptionInInitializerError(failure);
            }
            STATES = Map.copyOf(states);
            MATERIALS = Set.copyOf(materials);
        }
    }
}
