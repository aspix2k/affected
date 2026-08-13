<?php

declare(strict_types=1);

namespace Affected\Fixture\Alpha\Tests;

use Affected\Fixture\Alpha\Alpha;
use PHPUnit\Framework\TestCase;

final class AlphaTest extends TestCase
{
    public function testPasses(): void
    {
        self::assertSame(1, Alpha::value());
    }
}
