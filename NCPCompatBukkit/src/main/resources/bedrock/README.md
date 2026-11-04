# Bedrock movement shapes

`collisions-26.50.tsv.gz` contains additional static collision profiles for
authenticated Floodgate clients reporting Bedrock 26.50 through 26.52. Unknown
versions retain the existing native behavior. The original Java model and
native collision API are unchanged. Ground and passability may additionally
accept a position that is valid for the Bedrock shape, because Geyser can send
either raw client contacts or positions corrected to Java geometry.

The data covers 191 block types / 13,357 states, sharing 87 shape arrays.
Geyser custom block overrides retain their existing model. An incompatible
local Geyser registry disables these additions rather than guessing. No NCP
exemptions or extra collision tolerance are added.

Seven contextual types are excluded: bamboo, pointed dripstone, sulfur spike,
scaffolding, piston, sticky piston and piston head. Their native offset,
entity or moving/paired behavior cannot be replaced with a static sample.
These exclusions do not claim a confirmed false flag or a completed fix.

Source: [CloudburstMC/Data block geometry](https://github.com/CloudburstMC/Data/blob/88a2436a2d5c605da082a00fee5f103f888a9160/blocks.json).
The source license is included in `LICENSE-Cloudburst.txt` (Apache 2.0).
The generated data selects static differences from the comparison report and
joins the internal seams of paired chest halves. Source, resource checksums,
covered block names and exclusions are recorded in `collisions-26.50.json`.

To reproduce, first run `tools/compare_collisions.py`, then:

```sh
python3 tools/generate_bedrock_shapes.py
```

Regression tests exercise all generated states through NCP ground detection,
multipart contacts, fractional world heights, passable interiors, opaque
volumes, custom-block exclusions, client versions and pooled cache reuse.
These checks do not replace gameplay with an actual Bedrock client.
