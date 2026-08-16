import unittest
import os
from pathlib import Path


class OtherTest(unittest.TestCase):
    def test_other(self) -> None:
        Path(__file__).with_name("other.marker").write_text(str(os.getpid()), encoding="utf-8")
