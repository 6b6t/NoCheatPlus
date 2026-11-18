#!/usr/bin/env python3
"""Generate runtime profiles only from verified static Bedrock comparison data."""

import argparse
import gzip
import hashlib
import json
from pathlib import Path

from compare_collisions import DATASETS, boxes


EXCLUDED = {
    "bamboo": "position-dependent native offset",
    "pointed_dripstone": "position-dependent native offset",
    "sulfur_spike": "position-dependent native offset",
    "scaffolding": "entity and sneaking dependent collision",
    "piston": "paired and moving collision",
    "sticky_piston": "paired and moving collision",
    "piston_head": "paired and moving collision",
    "chorus_plant": "26.50 and 26.60 sampled collision geometry differs",
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


def generate(report, bedrock_version):
    dataset = DATASETS[bedrock_version]
    data_url = f"https://raw.githubusercontent.com/CloudburstMC/Data/{dataset['commit']}/blocks.json"
    if report["bedrock_sha256"] != dataset["sha256"] or report["bedrock_source"] != data_url:
        raise ValueError("Unverified Bedrock geometry source")
    if "Java Edition 26.3" not in report["versions"] or dataset["geyser_version"] not in report["versions"]:
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
    lines = [f"# Source: {data_url}", f"# SHA256: {dataset['sha256']}",
             f"# Bedrock {bedrock_version}; exact static states only; paired chest seams joined."]
    lines += [f"@{index}\t" + ",".join(format(v, ".6f").rstrip("0").rstrip(".") if v else "0" for v in shape)
              for shape, index in shapes.items()]
    lines += [f"{state}\t{index}" for state, index in sorted(states.items())]
    metadata = {"bedrock_version": bedrock_version, "source": data_url, "source_sha256": dataset["sha256"],
                "geyser_sha256": report["geyser_sha256"], "states": len(states), "shapes": len(shapes),
                "blocks": sorted({s.split("[", 1)[0] for s in states}), "excluded": excluded}
    return ("\n".join(lines) + "\n").encode(), metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=Path(__file__).parent / "collision-report/details.json")
    parser.add_argument("--bedrock-version", choices=DATASETS, default="26.50")
    parser.add_argument("--name", help="Output resource suffix, e.g. 26.50-26.60 for a verified shared profile")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent.parent /
                        "NCPCompatBukkit/src/main/resources/bedrock")
    args = parser.parse_args()
    data, metadata = generate(json.loads(args.report.read_text()), args.bedrock_version)
    args.output.mkdir(parents=True, exist_ok=True)
    name = args.name or args.bedrock_version
    (args.output / f"collisions-{name}.tsv.gz").write_bytes(gzip.compress(data, mtime=0))
    metadata["resource_sha256"] = hashlib.sha256(data).hexdigest()
    (args.output / f"collisions-{name}.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Generated {len(metadata['blocks'])} blocks / {metadata['states']} states / {metadata['shapes']} shapes; "
          f"{len(metadata['excluded'])} contextual block types retain native behavior.")


if __name__ == "__main__":
    main()
