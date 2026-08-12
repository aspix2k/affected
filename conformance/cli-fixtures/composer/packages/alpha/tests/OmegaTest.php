<?php

declare(strict_types=1);

namespace Affected\Fixture\Alpha\Tests;

use Affected\Fixture\Alpha\Omega;
use PHPUnit\Framework\TestCase;

final class OmegaTest extends TestCase
{
    public function testPasses(): void
    {
        self::assertSame(2, Omega::value());
    }
}
