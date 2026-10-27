import gzip
import struct
import unittest

from compare_collisions import boxes, compare, java_boxes, read_nbt, shape_difference


CUBE = boxes([[0, 0, 0, 1, 1, 1]])


class CollisionComparisonTest(unittest.TestCase):
    def test_java_center_and_size_conversion(self):
        self.assertEqual(CUBE, java_boxes([[0.5, 0.5, 0.5, 1, 1, 1]]))
        self.assertEqual(boxes([[0, 0, -0.25, 1, 1, 1.25]]),
                         java_boxes([[0.5, 0.5, 0.5, 1, 1, 1.5]]))

    def test_equivalent_decompositions_and_overlap(self):
        split = boxes([[0, 0, 0, 0.75, 1, 1], [0.25, 0, 0, 1, 1, 1]])
        self.assertIsNone(shape_difference(CUBE, split))
        self.assertIsNone(shape_difference((), ()))

    def test_equal_outer_bounds_can_hide_missing_collision(self):
        frame = boxes([[0, 0, 0, 0.25, 1, 1], [0.75, 0, 0, 1, 1, 1]])
        delta = shape_difference(CUBE, frame)
        self.assertEqual("shape", delta["kind"])
        self.assertEqual(0.5, delta["java_only_volume"])
        self.assertEqual(0, delta["bedrock_only_volume"])

    def test_ender_chest_support_difference(self):
        java = java_boxes([[0.5, 0.4375, 0.5, 0.875, 0.875, 0.875]])
        bedrock = boxes([[0.025, 0, 0.025, 0.975, 0.95, 0.975]])
        delta = shape_difference(java, bedrock)
        self.assertEqual("support-height", delta["kind"])
        self.assertEqual(0.875, delta["java_top"])
        self.assertEqual(0.95, delta["bedrock_top"])
        self.assertEqual(0, delta["java_only_volume"])
        self.assertAlmostEqual(0.187453125, delta["bedrock_only_volume"])

    def test_empty_collision_is_a_real_difference(self):
        delta = shape_difference(CUBE, ())
        self.assertEqual("collision-presence", delta["kind"])
        self.assertEqual(1, delta["java_only_volume"])

    def test_invalid_boxes_are_rejected(self):
        for value in ([[0, 1]], [[0, 0, 0, float("nan"), 1, 1]], [[1, 0, 0, 0, 1, 1]]):
            with self.assertRaises(ValueError):
                boxes(value)

    def test_big_endian_nbt_roundtrip_fixture(self):
        # Compound containing a string, a list of compounds and signed integer array.
        data = (b"\x0a\x00\x00\x08\x00\x04name\x00\x03air"
                b"\x09\x00\x06blocks\x0a\x00\x00\x00\x01\x01\x00\x03lit\x01\x00"
                b"\x0b\x00\x03ids\x00\x00\x00\x02" + struct.pack(">ii", -1, 123) + b"\x00")
        expected = {"name": "air", "blocks": [{"lit": 1}], "ids": [-1, 123]}
        self.assertEqual(expected, read_nbt(data))
        self.assertEqual(expected, read_nbt(gzip.compress(data)))
        with self.assertRaises(ValueError):
            read_nbt(data + b"x")

    def fixture(self, geometry=None):
        return dict(states=["minecraft:stone"], mappings=[{}],
                    collisions={"indices": [0], "shapes": [[[0.5, 0.5, 0.5, 1, 1, 1]]]},
                    palette=[{"name": "minecraft:stone", "states": {}, "network_id": -1}],
                    samples=[{"name": "minecraft:stone", "blockStateHash": 0xffffffff,
                              "geometry": geometry or {"collision": {"space": "sampled", "boxes": CUBE}}}],
                    remappers=[])

    def test_exact_state_translation_and_unsigned_network_hash(self):
        fixture = self.fixture()
        fixture["mappings"] = [{"bedrock_identifier": "granite", "state": {"axis": "x"}}]
        fixture["palette"][0].update(name="minecraft:granite", states={"axis": "x"})
        fixture["samples"][0]["name"] = "minecraft:granite"
        result = compare(**fixture)
        self.assertEqual(1, result["equal_states"])
        self.assertEqual(0, result["unknown_states"])

    def test_missing_state_and_missing_origin_are_unknown(self):
        fixture = self.fixture()
        fixture["mappings"][0] = {"state": {"axis": "y"}}
        self.assertEqual("mapped-state-not-in-palette", compare(**fixture)["unknown"][0]["reason"])
        result = compare(**self.fixture({"collision": {"space": "model_base", "boxes": CUBE}}))
        self.assertEqual("missing-origin-collision-sample", result["unknown"][0]["reason"])
        self.assertEqual(0, result["different_states"])

    def test_position_dependent_geometry_uses_actual_origin_sample(self):
        fixture = self.fixture({"collision": {"space": "model_base", "boxes": CUBE},
                                "modelPosition": {"samples": [
                                    {"position": [1, 0, 0], "collisionBoxes": CUBE},
                                    {"position": [0, 0, 0], "collisionBoxes": [[0, 0, 0, 0.5, 1, 1]]}]}})
        record = compare(**fixture)["differences"][0]
        self.assertEqual(0.5, record["java_only_volume"])
        self.assertEqual(["position-dependent:origin-only"], record["context"])

    def test_remapper_regex_uses_search_like_geyser(self):
        fixture = self.fixture({"collision": {"space": "sampled", "boxes": []}})
        fixture["remappers"] = [{"regex": "one$", "class": "example.SuffixCollision"}]
        self.assertEqual(["SuffixCollision"], compare(**fixture)["differences"][0]["geyser_remappers"])

    def test_visual_offsets_do_not_hide_empty_collision(self):
        fixture = self.fixture({"collision": {"space": "model_base", "boxes": []}, "modelPosition": {}})
        fixture["collisions"] = {"indices": [0], "shapes": [[]]}
        self.assertEqual(1, compare(**fixture)["equal_states"])

    def test_incompatible_or_duplicate_data_fails(self):
        fixture = self.fixture()
        fixture["samples"][0]["blockStateHash"] = 123
        with self.assertRaises(ValueError):
            compare(**fixture)
        fixture = self.fixture()
        fixture["samples"] *= 2
        with self.assertRaises(ValueError):
            compare(**fixture)
        fixture = self.fixture()
        fixture["collisions"]["indices"] = []
        with self.assertRaises(ValueError):
            compare(**fixture)


if __name__ == "__main__":
    unittest.main()
