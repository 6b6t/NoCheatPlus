# Bedrock movement shapes

`collisions-26.50-26.60.tsv.gz` contains additional static collision profiles
for authenticated Bedrock connections only when Floodgate supplies a verified
client version: 26.50 through 26.52 and 26.60. The worker-side identity fallback
does not carry a trusted client version, so identity-only players retain native
Java behavior. This includes the proxy-accepted 26.40-26.45 clients: their
shapes have not been compared and they are not eligible for the profile. Java
identities and all explicit versions outside the covered set also retain native
Java behavior. Do not add a proxy-to-worker version handoff without explicit
owner approval; until then this feature is fail-closed on Fox workers.
The original Java model and native collision API are unchanged. Ground and
passability may additionally accept a position valid for the Bedrock shape,
because Geyser can send raw client contacts or positions corrected to Java
geometry.

The shared profile covers 190 block types / 13,293 states, sharing 86 shape arrays.
Geyser custom block overrides retain their existing model. An incompatible
local Geyser registry disables these additions rather than guessing. No NCP
exemptions or extra collision tolerance are added.

Eight contextual or version-differing types are excluded: bamboo, pointed
dripstone, sulfur spike, scaffolding, piston, sticky piston, piston head and
chorus plant. Chorus plant's 63 sampled states differ between 26.50 and 26.60;
the other seven have native offset, entity or moving/paired behavior that
cannot be replaced with a static sample.

Source: [CloudburstMC/Data block geometry](https://github.com/CloudburstMC/Data/tree/0021592b1a4253528bad1e1d8b537e306e097557).
The source license is included in `LICENSE-Cloudburst.txt` (Apache 2.0).
The generated data selects static differences from the comparison report and
joins the internal seams of paired chest halves. Source, resource checksums,
covered block names and exclusions are recorded in
`collisions-26.50-26.60.json`.

To reproduce from a matching Geyser jar, run:

```sh
python3 tools/compare_collisions.py --geyser /path/to/geyser.jar --bedrock-version 26.60
python3 tools/generate_bedrock_shapes.py --report tools/collision-report/details.json \
  --bedrock-version 26.60 --name 26.50-26.60
```

When Geyser's accepted Bedrock versions change, the NCP maintainer must compare
each newly accepted release against this profile, exclude any differing block
types that are not needed for the main fix, regenerate the shared profile, add
tests and review the result before that version is enabled. The maintainer who
updates Geyser owns this refresh. The worker cannot detect a client version
when the proxy does not forward it, so identity-only profiles remain disabled;
do not enable an unverified version or add a proxy-to-worker handoff without
the required approval.

Regression tests exercise generated states through NCP ground detection,
multipart contacts, fractional world heights, passable interiors, opaque
volumes, custom-block exclusions, client versions and pooled cache reuse.
These checks do not replace gameplay with an actual Bedrock client.
