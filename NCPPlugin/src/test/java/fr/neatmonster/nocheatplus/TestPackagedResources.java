package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.*;
import org.junit.Test;

/** Also run after shading in package, so validation covers the delivered ZIP entries. */
public class TestPackagedResources {
    private void plugin(InputStream in) throws Exception {
        PluginDescriptionFile description = new PluginDescriptionFile(in);
        assertEquals("NoCheatPlus", description.getName());
        assertEquals("fr.neatmonster.nocheatplus.NoCheatPlus", description.getMain());
        assertEquals(java.util.List.of("LuckPerms", "ProtocolLib", "floodgate", "Geyser-Spigot"), description.getSoftDepend());
    }
    @Test public void bukkitParsesProcessedAndPackagedYamlResources() throws Exception {
        String path = System.getProperty("telemetry.packagedJar");
        if (path == null) {
            try (InputStream in = getClass().getResourceAsStream("/plugin.yml")) { assertNotNull(in); plugin(in); }
            return;
        }
        int yaml = 0;
        try (JarFile jar = new JarFile(path)) {
            assertNotNull(jar.getJarEntry("plugin.yml"));
            assertNotNull("Bedrock geometry must survive shading", jar.getJarEntry("bedrock/collisions-26.50.tsv.gz"));
            for (var entry : java.util.Collections.list(jar.entries())) {
                if (entry.isDirectory() || !(entry.getName().endsWith(".yml") || entry.getName().endsWith(".yaml"))) continue;
                yaml++;
                try (InputStream in = jar.getInputStream(entry)) {
                    if (entry.getName().equals("plugin.yml")) plugin(in);
                    else new YamlConfiguration().load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            }
        }
        assertTrue(yaml > 0);
        System.out.println("PACKAGED YAML PASS: " + yaml + " resources in " + path);
    }
    @Test public void rejectsOriginalMixedIndentSoftDependRegression() throws Exception {
        String bad = "name: NoCheatPlus\nversion: test\nmain: fr.neatmonster.nocheatplus.NoCheatPlus\nsoftdepend:\n  - LuckPerms\n- ProtocolLib\n";
        try { new PluginDescriptionFile(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))); fail("Invalid YAML accepted"); }
        catch (InvalidDescriptionException | org.yaml.snakeyaml.error.YAMLException expected) {}
    }
}
