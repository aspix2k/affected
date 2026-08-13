import unittest
from pathlib import Path


class BetaTest(unittest.TestCase):
    def test_beta(self) -> None:
        Path(__file__).with_name("beta.marker").write_text("beta", encoding="utf-8")
