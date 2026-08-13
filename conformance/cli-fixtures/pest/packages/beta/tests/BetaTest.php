<?php

use Affected\PestFixture\Beta\Beta;

test('beta package', function (): void {
    expect(Beta::value())->toBe('alpha-beta');
    file_put_contents(__DIR__ . '/../beta.marker', 'beta');
});
