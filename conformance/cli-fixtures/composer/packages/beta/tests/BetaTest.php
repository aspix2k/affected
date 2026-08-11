<?php

final class BetaTest extends PHPUnit\Framework\TestCase
{
    public function testPasses(): void
    {
        self::assertSame(2, 2);
    }
}
