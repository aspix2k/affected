from pathlib import Path

from value import value

MARKER = Path(__file__).resolve().parents[1] / "mixed-python.marker"


def test_value():
    MARKER.write_text("ran\n", encoding="utf-8")
    assert value() == 1
