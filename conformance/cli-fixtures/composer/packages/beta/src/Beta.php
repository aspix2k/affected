<?php

declare(strict_types=1);

namespace Affected\Fixture\Beta;

use Affected\Fixture\Alpha\Alpha;

final class Beta
{
    public static function value(): int
    {
        return Alpha::value() + 2;
    }
}
