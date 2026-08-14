"""Lock #105 product claims to the executable matrix."""

from __future__ import annotations

import unittest
from pathlib import Path

from scripts import support_matrix

ROOT = Path(__file__).resolve().parents[2]

CLAIMED = {
    "intellij-idea",
    "android-studio",
    "rider",
    "goland",
    "clion",
    "pycharm",
    "webstorm",
    "phpstorm",
    "rubymine",
    "rustrover",
    "dataspell",
}
PLANNED = {"datagrip", "gateway"}
EXCLUDED = {"aqua", "appcode", "mps"}


class ProductClaimTest(unittest.TestCase):
    """README and Marketplace counts must match the claimed JetBrains products."""

    def test_claimed_products_match_the_generated_summaries(self) -> None:
        """A missing or extra product must fail before Marketplace text drifts."""
        matrix = support_matrix.load_matrix(ROOT)
        products = {product["id"]: product for product in matrix["products"]}
        claimed = {
            identifier
            for identifier, product in products.items()
            if product["support"] in {"verified", "platform"}
        }
        planned = {
            identifier for identifier, product in products.items() if product["support"] == "planned"
        }
        excluded = {
            identifier
            for identifier, product in products.items()
            if product["support"] == "excluded"
        }

        self.assertEqual(CLAIMED, claimed)
        self.assertEqual(PLANNED, planned)
        self.assertEqual(EXCLUDED, excluded)
        self.assertIn("120", products["datagrip"]["issue"])
        self.assertIn("127", products["gateway"]["issue"])
        self.assertIn("discontinued", products["aqua"]["reason"].lower())
        self.assertIn("discontinued", products["appcode"]["reason"].lower())

        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        plugin = (ROOT / "src/main/resources/META-INF/plugin.xml").read_text(encoding="utf-8")
        expected = (
            f"{len(matrix['adapters'])} supported build ecosystems and "
            f"{len(CLAIMED)} JetBrains products"
        )
        self.assertIn(expected, readme)
        self.assertIn(expected, plugin)
