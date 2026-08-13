<?php

namespace Affected\PestFixture\Beta;

use Affected\PestFixture\Alpha\Alpha;

final class Beta
{
    public static function value(): string
    {
        return Alpha::value() . '-beta';
    }
}
