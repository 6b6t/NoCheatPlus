# Collision comparison

Compare every Java block state in an installed Geyser jar against independently
sampled Bedrock collision boxes. No Minecraft server starts and no plugin or
world is modified.

Requirements: Python 3.11+, a JDK compatible with Geyser, and the server's
`libraries/` directory for Geyser-Spigot's unbundled dependencies. A standalone
Geyser jar can supply its own dependencies.

```sh
python3 tools/compare_collisions.py \
  --geyser /path/to/testserver/plugins/Geyser-Spigot.jar \
  --java /path/to/jdk/bin/java
```

The libraries directory is discovered beside `plugins/`. Use `--libraries PATH`
to select another directory. Use `--output PATH` to change the report directory.

- `collision-report/summary.md` groups identical differences across block types.
- `collision-report/details.json` contains every differing or unknown state,
  exact boxes, volume differences and block names in each group.
- Standard output contains only totals and report paths to keep repeated checks short.
- The first run caches the 21 MB Bedrock dump; later runs use it offline. Data and
  generated reports are ignored by Git.

## Coverage and interpretation

The source is [CloudburstMC/Data's sampled block geometry](https://github.com/CloudburstMC/Data/commit/88a2436a2d5c605da082a00fee5f103f888a9160),
pinned by commit and SHA-256. This dump covers Bedrock 1.26.50. The supplied jar
must contain matching Java 26.3 / Bedrock 1.26.50 mappings. Different versions
fail explicitly, rather than comparing incompatible state IDs. Updating the
baseline requires updating the pinned data, checksum, version guard and palette
path together.

The Java state order, Java collision shapes, Java-to-Bedrock translation, and
Bedrock palette come from that exact Geyser jar. Bedrock samples are joined by
the palette's unsigned network hashes; their complete hash sets must match.
The small Java exporter loads registries only. It does not start Geyser.

The comparison checks occupied volume, not just outer bounds or box ordering.
Equivalent shapes with different box decompositions compare equal. The source's
six-decimal precision is preserved; zero-volume boxes do not count as collisions.

Differences are **test candidates**, not proven NCP false flags. Indexed Geyser
collision remappers are listed, but their presence does not prove a correction
is sufficient. Neighbour-dependent shapes, double chests, door corrections,
entity-specific collisions and moving geometry need runtime validation. Maximum
shape height does not describe every support surface of a multipart block.
Geyser's [Java geometry dump](https://github.com/GeyserMC/mappings-generator/blob/ba3c931b46178d17e520d3d1aebc651829f025d9/src/main/java/org/geysermc/mappings/generator/shape/CollisionShapeMappingsGenerator.java)
uses empty neighbours at (0,0,0). Position-dependent Bedrock geometry is compared
with its native collision sample at that same position. This checks that
reference position only; missing origin samples are reported as unknown. Empty
shapes remain empty under a position offset. No missing sample is treated as empty.
This does not simulate Geyser's packet translation or NCP's checks and does not
change runtime collision models.

Run regression tests without network access or a server:

```sh
python3 -m unittest discover -s tools -p 'test_compare_collisions.py'
```
