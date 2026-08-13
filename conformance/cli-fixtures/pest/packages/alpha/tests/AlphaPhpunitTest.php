<?php

use PHPUnit\Framework\TestCase;

final class AlphaPhpunitTest extends TestCase
{
    public function testPhpunitStyleRunsThroughPest(): void
    {
        self::assertTrue(true);
        file_put_contents(__DIR__ . '/../phpunit.marker', 'phpunit');
    }
}
