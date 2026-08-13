import pytest

from packages.alpha.alpha import value


@pytest.mark.parametrize("expected", [1, 1])
def test_alpha(expected):
    assert value == expected
