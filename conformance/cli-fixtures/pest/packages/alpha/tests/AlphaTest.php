<?php

use Affected\PestFixture\Alpha\Alpha;

test('alpha package', function (): void {
    expect(Alpha::value())->toBe('alpha');
    file_put_contents(__DIR__ . '/../alpha.marker', 'alpha');
});

dataset('alpha values', ['first', 'second']);

test('alpha dataset', function (string $value): void {
    expect($value)->not->toBeEmpty();
    file_put_contents(__DIR__ . '/../dataset.marker', $value . PHP_EOL, FILE_APPEND);
})->with('alpha values');
