#!/usr/bin/env python3
"""Compare Geyser's Java shapes with independently sampled Bedrock geometry."""

import argparse
import gzip
import hashlib
import io
import itertools
import json
import math
import os
import re
import struct
import subprocess
import sys
import urllib.request
import zipfile
from collections import Counter, defaultdict
from functools import lru_cache
from pathlib import Path


BEDROCK_VERSION = "26_50"
DATA_COMMIT = "88a2436a2d5c605da082a00fee5f103f888a9160"
DATA_URL = f"https://raw.githubusercontent.com/CloudburstMC/Data/{DATA_COMMIT}/blocks.json"
DATA_SHA256 = "8291ff5229f56a4a47196a202308ca9fa324e1c81f3cff6be4c2d5d2dad34983"
EPSILON = 1e-6  # Bedrock's sampled data is rounded to six decimal places.


def read_nbt(data):
    """Read the standard big-endian NBT used in Geyser's bundled mappings."""
    stream = io.BytesIO(gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data)

    def number(fmt):
        return struct.unpack(">" + fmt, stream.read(struct.calcsize(fmt)))[0]

    def string():
        return stream.read(number("H")).decode("utf-8")

    def payload(kind):
        if kind in (1, 2, 3, 4, 5, 6):
            return number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 7:
            return stream.read(number("i"))
        if kind == 8:
            return string()
        if kind == 9:
            element, count = number("B"), number("i")
            return [payload(element) for _ in range(count)]
        if kind == 10:
            result = {}
            while child := number("B"):
                key = string()
                result[key] = payload(child)
            return result
        if kind in (11, 12):
            return [number("i" if kind == 11 else "q") for _ in range(number("i"))]
        raise ValueError(f"Unsupported NBT tag {kind}")

    kind = number("B")
    string()
    result = payload(kind)
    if stream.read(1):
        raise ValueError("Trailing data after NBT root")
    return result


def boxes(values):
    result = []
    for value in values:
        if len(value) != 6 or not all(math.isfinite(v) for v in value):
            raise ValueError(f"Invalid collision box: {value}")
        if any(value[i] > value[i + 3] for i in range(3)):
            raise ValueError(f"Inverted collision box: {value}")
        if all(value[i] < value[i + 3] for i in range(3)):
            result.append(tuple(round(v, 6) for v in value))
    return tuple(sorted(set(result)))


def java_boxes(values):
    # Geyser stores center XYZ followed by full size XYZ, not min/max.
    return boxes([[v[i] - v[i + 3] / 2 for i in range(3)]
                  + [v[i] + v[i + 3] / 2 for i in range(3)] for v in values])


@lru_cache(maxsize=8192)
def shape_difference(java, bedrock):
    """Compare occupied volume, allowing different box decompositions."""
    if java == bedrock:
        return None
    all_boxes = java + bedrock
    axes = [sorted({b[i] for b in all_boxes} | {b[i + 3] for b in all_boxes})
            for i in range(3)]
    intervals = [list(zip(axis, axis[1:])) for axis in axes]
    java_only = bedrock_only = 0.0
    for cell in itertools.product(*intervals):
        point = [sum(span) / 2 for span in cell]
        in_java = any(all(b[i] < point[i] < b[i + 3] for i in range(3)) for b in java)
        in_bedrock = any(all(b[i] < point[i] < b[i + 3] for i in range(3)) for b in bedrock)
        volume = math.prod(high - low for low, high in cell)
        if in_java and not in_bedrock:
            java_only += volume
        if in_bedrock and not in_java:
            bedrock_only += volume
    if java_only + bedrock_only <= 1e-9:
        return None
    java_top = max((b[4] for b in java), default=0.0)
    bedrock_top = max((b[4] for b in bedrock), default=0.0)
    kind = "shape"
    if bool(java) != bool(bedrock):
        kind = "collision-presence"
    elif abs(java_top - bedrock_top) > EPSILON:
        kind = "support-height"
    return {"kind": kind, "java_top": java_top, "bedrock_top": bedrock_top,
            "java_only_volume": round(java_only, 9),
            "bedrock_only_volume": round(bedrock_only, 9)}


def state_key(name, states):
    return name, tuple(sorted(states.items()))


def compare(states, mappings, collisions, palette, samples, remappers):
    if len(states) != len(mappings) or len(states) != len(collisions["indices"]):
        raise ValueError("Geyser state/mapping/collision lengths differ")
    if len(states) != len(set(states)):
        raise ValueError("Duplicate Java block states")
    by_hash = {s["blockStateHash"]: s for s in samples}
    if len(by_hash) != len(samples):
        raise ValueError("Duplicate Bedrock sample hashes")
    palette_hashes = {p["network_id"] & 0xffffffff for p in palette}
    if palette_hashes != set(by_hash):
        raise ValueError("Bedrock sample hashes do not match the bundled 26.50 palette")
    by_state = {state_key(p["name"], p["states"]): by_hash[p["network_id"] & 0xffffffff]
                for p in palette}
    java_shapes = [java_boxes(s) for s in collisions["shapes"]]
    differences, unknown = [], []
    block_counts = Counter()
    equal = 0
    for index, (state, mapping) in enumerate(zip(states, mappings)):
        name = state.split("[", 1)[0]
        block_counts[name] += 1
        target = "minecraft:" + mapping.get("bedrock_identifier", name.split(":", 1)[1])
        target_states = mapping.get("state", {})
        sample = by_state.get(state_key(target, target_states))
        record = {"java_state": state, "bedrock_name": target, "bedrock_states": target_states}
        if sample is None:
            unknown.append({**record, "reason": "mapped-state-not-in-palette"})
            continue
        if sample["name"] != target:
            raise ValueError(f"Bedrock hash/name mismatch for {state}")
        geometry = sample.get("geometry", {})
        collision = geometry.get("collision", {})
        # Geyser's Java shape dump uses EmptyBlockGetter at BlockPos.ZERO.
        if collision.get("space") not in ("sampled", "model_base"):
            unknown.append({**record, "reason": "unsupported-geometry-space"})
            continue
        context = []
        bedrock_values = collision.get("boxes")
        if collision.get("boxes") and (collision["space"] == "model_base" or "modelPosition" in geometry):
            origin = next((p for p in geometry.get("modelPosition", {}).get("samples", [])
                           if p["position"] == [0, 0, 0]), None)
            if origin is None or "collisionBoxes" not in origin:
                unknown.append({**record, "reason": "missing-origin-collision-sample"})
                continue
            bedrock_values = origin["collisionBoxes"]
            context.append("position-dependent:origin-only")
        if bedrock_values is None:
            unknown.append({**record, "reason": "missing-collision-geometry"})
            continue
        java = java_shapes[collisions["indices"][index]]
        bedrock = boxes(bedrock_values)
        difference = shape_difference(java, bedrock)
        if difference is None:
            equal += 1
            continue
        fixes = [r["class"].rsplit(".", 1)[-1] for r in remappers
                 if re.search(r["regex"], name.split(":", 1)[1])]
        if "shapeSource" in geometry:
            context.append(geometry["shapeSource"])
        if any(k in state for k in ("shape=", "type=left", "type=right", "_wall[", "_fence[")):
            context.append("neighbour-or-paired-block")
        if name in ("minecraft:scaffolding", "minecraft:powder_snow", "minecraft:moving_piston"):
            context.append("entity-or-runtime-dependent")
        differences.append({**record, **difference, "java_boxes": java, "bedrock_boxes": bedrock,
                            "geyser_remappers": fixes, "context": context})
    return {"total_states": len(states), "total_blocks": len(block_counts), "equal_states": equal,
            "different_states": len(differences), "unknown_states": len(unknown),
            "block_state_counts": dict(sorted(block_counts.items())),
            "differences": differences, "unknown": unknown}


def write_reports(report, output):
    output.mkdir(parents=True, exist_ok=True)
    grouped = defaultdict(list)
    for record in report["differences"]:
        grouped[record["java_state"].split("[", 1)[0]].append(record)
    families = defaultdict(list)
    for name, records in sorted(grouped.items()):
        signature = tuple(sorted({json.dumps({k: r[k] for k in (
            "kind", "java_boxes", "bedrock_boxes", "geyser_remappers", "context")}, sort_keys=True)
                                  for r in records}))
        families[signature].append(name)
    report["difference_groups"] = list(families.values())
    (output / "details.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    lines = ["# Java / Bedrock collision comparison", "", report["versions"], "",
             f"{report['total_blocks']} Java blocks, {report['total_states']} states: "
             f"{report['equal_states']} equal, {report['different_states']} different, "
             f"{report['unknown_states']} unknown.", "",
             "Reference: origin (0,0,0), static state geometry. "
             "Differences are test candidates, not confirmed NCP false flags. "
             "Geyser remappers may already correct movement. Paired blocks, neighbours, "
             "position offsets and movement mechanics need runtime validation.", "",
             "| Blocks with the same differences | Different states | Kind | Java / Bedrock max top | Geyser remapper | Context |",
             "| --- | ---: | --- | --- | --- | --- |"]
    for names in families.values():
        records = [r for name in names for r in grouped[name]]
        label = ", ".join(name.removeprefix("minecraft:") for name in names[:3])
        if len(names) > 3:
            label += f" (+{len(names) - 3} blocks)"
        kinds = ", ".join(sorted({r["kind"] for r in records}))
        tops = ", ".join(sorted({f"{r['java_top']:g}/{r['bedrock_top']:g}" for r in records}))
        fixes = ", ".join(sorted({f for r in records for f in r["geyser_remappers"]})) or "none indexed"
        context = ", ".join(sorted({c for r in records for c in r["context"]}))
        lines.append(f"| {label} | {len(records)} | {kinds} | {tops} | {fixes} | {context} |")
    lines += ["", "## Unknown coverage", ""]
    counts = Counter((r["java_state"].split("[", 1)[0], r["reason"]) for r in report["unknown"])
    lines += [f"- {name}: {count} states ({reason})." for (name, reason), count in sorted(counts.items())]
    if not counts:
        lines.append("None.")
    lines += ["", "## Sources", "", f"- Geyser jar SHA-256: `{report['geyser_sha256']}`.",
              f"- [Bedrock geometry]({DATA_URL}), SHA-256: `{DATA_SHA256}`.",
              "- Java shapes, state order, Bedrock palette and translation come from the supplied Geyser jar.",
              "- [Java dump context](https://github.com/GeyserMC/mappings-generator/blob/ba3c931b46178d17e520d3d1aebc651829f025d9/src/main/java/org/geysermc/mappings/generator/shape/CollisionShapeMappingsGenerator.java): empty neighbours at (0,0,0).",
              "- `details.json` retains every differing or unknown state and every grouped block name; equal states are counted.", ""]
    (output / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    return len(grouped)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geyser", type=Path, required=True, help="Installed Geyser-Spigot or standalone jar")
    parser.add_argument("--java", default="java", help="Java executable with source-file launch support")
    parser.add_argument("--libraries", type=Path, help="Server libraries directory (default: beside plugins/)")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "collision-report")
    args = parser.parse_args()
    folder = Path(__file__).resolve().parent
    cache = folder / ".collision-cache"
    cache.mkdir(exist_ok=True)
    sample_path = cache / "bedrock-blocks.json"
    if not sample_path.exists():
        with urllib.request.urlopen(DATA_URL, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != DATA_SHA256:
            raise ValueError("Downloaded Bedrock data checksum differs")
        sample_path.write_bytes(data)
    data = sample_path.read_bytes()
    if hashlib.sha256(data).hexdigest() != DATA_SHA256:
        raise ValueError("Cached Bedrock data checksum differs; remove the cache file and retry")
    libraries = args.libraries or args.geyser.resolve().parent.parent / "libraries"
    classpath = os.pathsep.join([str(args.geyser.resolve())]
                               + [str(p) for p in sorted(libraries.rglob("*.jar"))])
    export = cache / "geyser-states.json"
    result = subprocess.run([args.java, "-cp", classpath, str(folder / "DumpGeyserStates.java"), str(export)],
                            capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError("Geyser registry export failed. Supply the server libraries and a compatible JDK:\n"
                           + result.stdout + result.stderr)
    definitions = json.loads(export.read_text(encoding="utf-8"))
    with zipfile.ZipFile(args.geyser) as jar:
        versions = jar.read("mappings/README.md").decode("utf-8")
        versions = next(line for line in versions.splitlines() if "generated for Minecraft" in line)
        if "Java Edition 26.3" not in versions or "Bedrock Edition 1.26.50" not in versions:
            raise ValueError("Pinned Bedrock dump requires Geyser's Java 26.3 / Bedrock 1.26.50 mappings")
        report = compare(definitions["states"],
                         read_nbt(jar.read("mappings/blocks.nbt"))["bedrock_mappings"],
                         read_nbt(jar.read("mappings/collisions.nbt")),
                         read_nbt(jar.read(f"bedrock/block_palette.{BEDROCK_VERSION}.nbt"))["blocks"],
                         json.loads(data), definitions["remappers"])
    with args.geyser.open("rb") as stream:
        geyser_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    report.update(versions=versions, geyser_sha256=geyser_hash,
                  bedrock_source=DATA_URL, bedrock_sha256=DATA_SHA256)
    count = write_reports(report, args.output)
    print(f"Compared {report['total_states']} states / {report['total_blocks']} Java blocks: "
          f"{report['equal_states']} equal, {report['different_states']} differences in {count} blocks, "
          f"{report['unknown_states']} unknown.")
    print(f"Report: {args.output / 'summary.md'}\nState details: {args.output / 'details.json'}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"Collision comparison failed: {error}", file=sys.stderr)
        raise SystemExit(1)
