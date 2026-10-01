import unittest

from app import answer


class AppTest(unittest.TestCase):
    def test_answer(self) -> None:
        self.assertEqual(42, answer())
