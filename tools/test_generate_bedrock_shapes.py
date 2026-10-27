import unittest
from generate_bedrock_shapes import profile


class ProfileGenerationTest(unittest.TestCase):
    def test_paired_chest_seams_and_exterior_are_distinct(self):
        expected = {"north": (3, 0), "south": (0, 3), "east": (5, 2), "west": (2, 5)}
        for facing, (left_joint, right_joint) in expected.items():
            for kind, joint in [("left", left_joint), ("right", right_joint)]:
                state = f"minecraft:trapped_chest[facing={facing},type={kind},waterlogged=false]"
                shape = profile({"java_state": state, "bedrock_boxes": [[0.025, 0, 0.025, 0.975, 0.95, 0.975]]})
                self.assertEqual(1 if joint >= 3 else 0, shape[joint])
                self.assertEqual(0.95, shape[4])
                self.assertEqual(0.975 if joint < 3 else 0.025, shape[(joint + 3) % 6])

    def test_contextual_geometry_is_not_used_as_a_static_profile(self):
        for name in ["scaffolding", "piston_head", "bamboo"]:
            self.assertIsNone(profile({"java_state": "minecraft:" + name, "bedrock_boxes": []}))

    def test_source_geometry_is_preserved_for_static_profiles(self):
        self.assertEqual((0.25, 0.0, 0.25, 0.75, 0.5, 0.75), profile({
            "java_state": "minecraft:conduit[waterlogged=false]",
            "bedrock_boxes": [[0.25, 0, 0.25, 0.75, 0.5, 0.75]]}))


if __name__ == "__main__":
    unittest.main()
