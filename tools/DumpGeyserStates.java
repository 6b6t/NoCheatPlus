import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import com.google.gson.Gson;
import org.geysermc.geyser.level.block.Blocks;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.translator.collision.CollisionRemapper;

/** Exports the state order from the exact Geyser jar being compared, without a server. */
class DumpGeyserStates {
    public static void main(String[] args) throws Exception {
        Blocks.bootstrap();
        var remappers = new ArrayList<Map<String, String>>();
        try (var input = DumpGeyserStates.class.getClassLoader().getResourceAsStream(
                "org.geysermc.geyser.translator.collision.CollisionRemapper")) {
            if (input == null) throw new IllegalStateException("Missing Geyser collision index");
            for (String name : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                if (name.isBlank()) continue;
                var annotation = Class.forName(name).getAnnotation(CollisionRemapper.class);
                if (name.contains(".fixes.")) {
                    remappers.add(Map.of("class", name, "regex", annotation.regex()));
                }
            }
        }
        try (var out = Files.newBufferedWriter(Path.of(args[0]))) {
            new Gson().toJson(Map.of("states", BlockRegistries.BLOCK_STATES.get().stream()
                    .map(Object::toString).toList(), "remappers", remappers), out);
        }
    }
}
