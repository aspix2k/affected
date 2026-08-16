import os
import unittest
from pathlib import Path

from .test_helpers import VALUE


class ConsumerTest(unittest.TestCase):
    def test_consumer(self) -> None:
        Path(__file__).with_name("consumer.marker").write_text(str(os.getpid()), encoding="utf-8")
        self.assertEqual("consumer", VALUE)
