import unittest
from pathlib import Path


class AlphaTest(unittest.TestCase):
    def test_alpha(self) -> None:
        Path(__file__).with_name("alpha.marker").write_text("alpha", encoding="utf-8")
