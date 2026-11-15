#!/usr/bin/env python3
"""Generate runtime profiles only from verified static Bedrock comparison data."""

import argparse
import gzip
import hashlib
import json
from pathlib import Path

from compare_collisions import DATA_SHA256, DATA_URL, boxes


EXCLUDED = {
    "bamboo": "position-dependent native offset",
    "pointed_dripstone": "position-dependent native offset",
    "sulfur_spike": "position-dependent native offset",
    "scaffolding": "entity and sneaking dependent collision",
    "piston": "paired and moving collision",
    "sticky_piston": "paired and moving collision",
    "piston_head": "paired and moving collision",
}


def profile(record):
    name = record["java_state"].split("[", 1)[0].removeprefix("minecraft:")
    if name in EXCLUDED:
        return None
    result = boxes(record["bedrock_boxes"])
    if name.endswith("chest") and "[" in record["java_state"]:
        properties = dict(p.split("=") for p in record["java_state"].split("[", 1)[1][:-1].split(","))
        kind = properties.get("type", "single")
        if kind in ("left", "right"):
            if result != ((0.025, 0.0, 0.025, 0.975, 0.95, 0.975),):
                raise ValueError("Unexpected source chest geometry")
            dx, dz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[properties["facing"]]
            side = 1 if kind == "left" else -1
            joint_x, joint_z = -side * dz, side * dx
            chest = list(result[0])
            if joint_x: chest[3 if joint_x > 0 else 0] = 1.0 if joint_x > 0 else 0.0
            if joint_z: chest[5 if joint_z > 0 else 2] = 1.0 if joint_z > 0 else 0.0
            result = (tuple(chest),)
    return tuple(v for box in result for v in box)


def generate(report):
    if report["bedrock_sha256"] != DATA_SHA256 or report["bedrock_source"] != DATA_URL:
        raise ValueError("Unverified Bedrock geometry source")
    if "Java Edition 26.3 and Minecraft: Bedrock Edition 1.26.50." not in report["versions"]:
        raise ValueError("Unsupported source versions")
    states, shapes, excluded = {}, {}, {}
    for record in report["differences"]:
        state = record["java_state"]
        shape = profile(record)
        if shape is None:
            name = state.split("[", 1)[0].removeprefix("minecraft:")
            excluded[name] = EXCLUDED[name]
            continue
        if state in states:
            raise ValueError("Duplicate block state")
        states[state] = shapes.setdefault(shape, len(shapes))
    lines = [f"# Source: {DATA_URL}", f"# SHA256: {DATA_SHA256}",
             "# Bedrock 26.50-26.52; exact static states only; paired chest seams joined."]
    lines += [f"@{index}\t" + ",".join(format(v, ".6f").rstrip("0").rstrip(".") if v else "0" for v in shape)
              for shape, index in shapes.items()]
    lines += [f"{state}\t{index}" for state, index in sorted(states.items())]
    metadata = {"source": DATA_URL, "source_sha256": DATA_SHA256,
                "geyser_sha256": report["geyser_sha256"], "states": len(states), "shapes": len(shapes),
                "blocks": sorted({s.split("[", 1)[0] for s in states}), "excluded": excluded}
    return ("\n".join(lines) + "\n").encode(), metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=Path(__file__).parent / "collision-report/details.json")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent.parent /
                        "NCPCompatBukkit/src/main/resources/bedrock")
    args = parser.parse_args()
    data, metadata = generate(json.loads(args.report.read_text()))
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "collisions-26.50.tsv.gz").write_bytes(gzip.compress(data, mtime=0))
    metadata["resource_sha256"] = hashlib.sha256(data).hexdigest()
    (args.output / "collisions-26.50.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Generated {len(metadata['blocks'])} blocks / {metadata['states']} states / {metadata['shapes']} shapes; "
          f"{len(metadata['excluded'])} contextual block types retain native behavior.")


if __name__ == "__main__":
    main()
