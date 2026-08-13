import unittest
from pathlib import Path


class OtherTest(unittest.TestCase):
    def test_other(self) -> None:
        Path(__file__).with_name("other.marker").write_text("other", encoding="utf-8")
