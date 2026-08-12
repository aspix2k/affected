<?php

declare(strict_types=1);

namespace Affected\Fixture\Beta\Tests;

use Affected\Fixture\Beta\Beta;
use PHPUnit\Framework\TestCase;

final class BetaTest extends TestCase
{
    public function testPasses(): void
    {
        self::assertSame(3, Beta::value());
    }
}
