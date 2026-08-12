<?php

declare(strict_types=1);

namespace {
    if (!defined('AFFECTED_PHPUNIT_LOADER')) {
        define('AFFECTED_PHPUNIT_LOADER', true);
        spl_autoload_register(static function (string $class): void {
            if ($class === 'Affected\\Phpunit\\Extension' && !defined('AFFECTED_PHPUNIT_IMPLEMENTATION')) {
                define('AFFECTED_PHPUNIT_IMPLEMENTATION', true);
                require __FILE__;
            }
        });
    }
}

namespace Affected\Phpunit {
    use PHPUnit\Event\Code\TestMethod;
    use PHPUnit\Event\Test\ConsideredRiskySubscriber;
    use PHPUnit\Event\Test\ErroredSubscriber;
    use PHPUnit\Event\Test\FailedSubscriber;
    use PHPUnit\Event\Test\FinishedSubscriber as TestFinishedSubscriber;
    use PHPUnit\Event\Test\MarkedIncompleteSubscriber;
    use PHPUnit\Event\Test\SkippedSubscriber as TestSkippedSubscriber;
    use PHPUnit\Event\TestRunner\ChildProcessStartedSubscriber;
    use PHPUnit\Event\TestRunner\ExecutionAbortedSubscriber;
    use PHPUnit\Event\TestRunner\ExecutionFinishedSubscriber;
    use PHPUnit\Event\TestSuite\FinishedSubscriber as SuiteFinishedSubscriber;
    use PHPUnit\Event\TestSuite\LoadedSubscriber;
    use PHPUnit\Event\TestSuite\SkippedSubscriber as SuiteSkippedSubscriber;
    use PHPUnit\Event\TestSuite\TestSuiteForTestClass;
    use PHPUnit\Runner\Extension\Extension as PhpunitExtension;
    use PHPUnit\Runner\Extension\Facade;
    use PHPUnit\Runner\Extension\ParameterCollection;
    use PHPUnit\TextUI\Configuration\Configuration;
    use ReflectionClass;
    use RuntimeException;
    use Throwable;

    if (defined('AFFECTED_PHPUNIT_IMPLEMENTATION')) {
    final class Extension implements PhpunitExtension
    {
        public function bootstrap(Configuration $configuration, Facade $facade, ParameterCollection $parameters): void
        {
            $state = State::create();
            if ($configuration->hasBootstrap() || $configuration->hasConfigurationFile()) {
                $state->invalidate();
            }
            register_shutdown_function(static fn () => $state->write());
            $facade->registerSubscribers(
                new class($state) implements LoadedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\TestSuite\Loaded $event): void
                    {
                        $this->state->loaded($event->testSuite()->tests()->asArray());
                    }
                },
                new class($state) implements SuiteFinishedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\TestSuite\Finished $event): void
                    {
                        $suite = $event->testSuite();
                        if ($suite instanceof TestSuiteForTestClass) {
                            $this->state->classFinished($suite->className(), $suite->file());
                        }
                    }
                },
                new class($state) implements TestFinishedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\Test\Finished $event): void
                    {
                        $this->state->testFinished($event->test());
                    }
                },
                new class($state) implements ExecutionFinishedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\TestRunner\ExecutionFinished $event): void
                    {
                        $this->state->executionFinished();
                    }
                },
                new class($state) implements ExecutionAbortedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\TestRunner\ExecutionAborted $event): void
                    {
                        $this->state->invalidate();
                    }
                },
                new class($state) implements ChildProcessStartedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\TestRunner\ChildProcessStarted $event): void
                    {
                        $this->state->invalidate();
                    }
                },
                new class($state) implements TestSkippedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\Test\Skipped $event): void
                    {
                        $this->state->invalidate();
                    }
                },
                new class($state) implements SuiteSkippedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\TestSuite\Skipped $event): void
                    {
                        $this->state->invalidate();
                    }
                },
                new class($state) implements FailedSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\Test\Failed $event): void
                    {
                        $this->state->invalidate();
                    }
                },
                new class($state) implements ErroredSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\Test\Errored $event): void
                    {
                        $this->state->invalidate();
                    }
                },
                new class($state) implements MarkedIncompleteSubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\Test\MarkedIncomplete $event): void
                    {
                        $this->state->invalidate();
                    }
                },
                new class($state) implements ConsideredRiskySubscriber {
                    public function __construct(private readonly State $state) {}
                    public function notify(\PHPUnit\Event\Test\ConsideredRisky $event): void
                    {
                        $this->state->invalidate();
                    }
                },
            );
        }
    }

    final class State
    {
        private const MAX_CONTEXT_BYTES = 1048576;
        private const MAX_TESTS = 65536;
        private const MAX_CLASSES = 4096;
        private const MAX_ARTIFACTS = 4096;
        private const MAX_DEPENDENCIES = 65536;
        private const MAX_METADATA_BYTES = 8388608;
        private const MAX_OUTPUT_BYTES = 16777216;

        private bool $supported = true;
        private bool $loaded = false;
        private bool $executionFinished = false;
        private array $tests = [];
        private array $classes = [];
        private array $finishedTests = [];
        private array $finishedClasses = [];
        private array $dependencies = [];
        private array $capturedIncludes = [];
        private int $dependencyCount = 0;
        private int $metadataBytes = 0;

        private function __construct(
            private readonly string $root,
            private readonly string $output,
            private readonly bool $full,
            private readonly array $artifacts,
        ) {}

        public static function create(): self
        {
            $contextPath = getenv('AFFECTED_PHPUNIT_CONTEXT');
            if (!is_string($contextPath) || $contextPath === '' || is_link($contextPath) || !is_file($contextPath)) {
                throw new RuntimeException('Affected PHPUnit context is unavailable');
            }
            $size = filesize($contextPath);
            if (!is_int($size) || $size < 1 || $size > self::MAX_CONTEXT_BYTES) {
                throw new RuntimeException('Affected PHPUnit context is invalid');
            }
            $raw = file_get_contents($contextPath);
            $context = is_string($raw) ? json_decode($raw, true, 16, JSON_THROW_ON_ERROR) : null;
            if (!is_array($context) || ($context['schema'] ?? null) !== 1 || !is_bool($context['full'] ?? null)) {
                throw new RuntimeException('Affected PHPUnit context is invalid');
            }
            $root = self::realDirectory($context['root'] ?? null);
            $output = self::outputPath($context['output'] ?? null);
            $rawArtifacts = $context['artifacts'] ?? null;
            if (!is_array($rawArtifacts) || $rawArtifacts === [] || count($rawArtifacts) > self::MAX_ARTIFACTS) {
                throw new RuntimeException('Affected PHPUnit artifacts are invalid');
            }
            $artifacts = [];
            foreach ($rawArtifacts as $relative) {
                if (!is_string($relative) || !self::relativePath($relative)) {
                    throw new RuntimeException('Affected PHPUnit artifact is invalid');
                }
                $real = realpath($root . DIRECTORY_SEPARATOR . str_replace('/', DIRECTORY_SEPARATOR, $relative));
                if (!is_string($real) || !is_file($real) || !self::within($real, $root) || isset($artifacts[$real])) {
                    throw new RuntimeException('Affected PHPUnit artifact is invalid');
                }
                $artifacts[$real] = $relative;
            }
            return new self($root, $output, $context['full'], $artifacts);
        }

        public function loaded(array $tests): void
        {
            if ($this->loaded || $tests === [] || count($tests) > self::MAX_TESTS) {
                $this->supported = false;
                return;
            }
            $this->loaded = true;
            foreach ($tests as $test) {
                if (!$test instanceof TestMethod
                    || !$test->metadata()->isDepends()->isEmpty()
                    || !$test->metadata()->isDataProvider()->isEmpty()) {
                    $this->supported = false;
                    continue;
                }
                $class = $test->className();
                $id = $test->id();
                if (strlen($class) > 4096 || strlen($id) > 4096) {
                    $this->supported = false;
                    return;
                }
                try {
                    $file = (new ReflectionClass($class))->getFileName();
                } catch (Throwable) {
                    $this->supported = false;
                    continue;
                }
                $real = is_string($file) ? realpath($file) : false;
                if (!is_string($real) || !self::within($real, $this->root) || isset($this->tests[$id])) {
                    $this->supported = false;
                    continue;
                }
                $relative = self::relative($real, $this->root);
                if (strlen($relative) > 4096) {
                    $this->supported = false;
                    return;
                }
                $newClass = !isset($this->classes[$class]);
                if (!$newClass && $this->classes[$class] !== $relative) {
                    $this->supported = false;
                    continue;
                }
                $this->tests[$id] = $class;
                $this->classes[$class] = $relative;
                $this->dependencies[$class] ??= [];
                $this->metadataBytes += strlen($id) + strlen($class);
                if ($newClass) {
                    $this->metadataBytes += strlen($class) + strlen($relative);
                }
                if ($this->metadataBytes > self::MAX_METADATA_BYTES) {
                    $this->supported = false;
                    return;
                }
            }
            if ($this->classes === [] || count($this->classes) > self::MAX_CLASSES) {
                $this->supported = false;
            }
        }

        public function classFinished(string $class, string $file): void
        {
            $real = realpath($file);
            if (!is_string($real) || !self::within($real, $this->root)
                || ($this->classes[$class] ?? null) !== self::relative($real, $this->root)) {
                $this->supported = false;
                return;
            }
            $this->capture($class);
            $this->finishedClasses[$class] = true;
        }

        public function testFinished(\PHPUnit\Event\Code\Test $test): void
        {
            if (!$test instanceof TestMethod || !isset($this->tests[$test->id()])) {
                $this->supported = false;
                return;
            }
            $this->finishedTests[$test->id()] = true;
        }

        public function executionFinished(): void
        {
            $this->executionFinished = true;
        }

        public function invalidate(): void
        {
            $this->supported = false;
        }

        public function write(): void
        {
            $this->captureShutdownIncludes();
            $finishedTests = array_intersect_key($this->tests, $this->finishedTests);
            $finishedClasses = array_intersect_key($this->classes, $this->finishedClasses);
            $complete = $this->supported && $this->loaded && $this->executionFinished
                && $finishedTests !== [] && $finishedClasses !== []
                && count($finishedTests) === count($this->finishedTests)
                && count($finishedClasses) === count($this->finishedClasses);
            foreach ($finishedTests as $class) {
                $complete = $complete && isset($finishedClasses[$class]);
            }
            if ($this->full) {
                $complete = $complete && count($finishedTests) === count($this->tests)
                    && count($finishedClasses) === count($this->classes);
            }
            ksort($this->tests);
            ksort($this->classes);
            ksort($this->dependencies);
            foreach ($this->dependencies as &$dependencies) {
                $dependencies = array_keys($dependencies);
                sort($dependencies);
            }
            unset($dependencies);
            $inventory = [];
            foreach ($this->tests as $id => $class) {
                $inventory[] = ['id' => $id, 'class' => $class, 'file' => $this->classes[$class]];
            }
            $tests = [];
            foreach ($finishedTests as $id => $class) {
                $tests[] = ['id' => $id, 'class' => $class, 'file' => $finishedClasses[$class]];
            }
            $dependencies = array_intersect_key($this->dependencies, $finishedClasses);
            $dependencyCount = array_sum(array_map('count', $dependencies));
            $payload = json_encode([
                'schema' => 2,
                'full' => $this->full,
                'supported' => $this->supported,
                'complete' => $complete,
                'test_count' => count($tests),
                'class_count' => count($finishedClasses),
                'dependency_owner_count' => count($dependencies),
                'dependency_count' => $dependencyCount,
                'inventory_test_count' => count($inventory),
                'inventory_class_count' => count($this->classes),
                'tests' => $tests,
                'dependencies' => $dependencies,
                'inventory' => $inventory,
            ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
            if (strlen($payload) > self::MAX_OUTPUT_BYTES) {
                return;
            }
            $directory = dirname($this->output);
            $temporary = tempnam($directory, 'phpunit-');
            if (!is_string($temporary)) {
                return;
            }
            try {
                if (file_put_contents($temporary, $payload, LOCK_EX) === strlen($payload)) {
                    rename($temporary, $this->output);
                }
            } finally {
                if (is_file($temporary)) {
                    unlink($temporary);
                }
            }
        }

        private function capture(string $class): void
        {
            foreach (get_included_files() as $file) {
                $real = realpath($file);
                if (!is_string($real)) {
                    $this->supported = false;
                    continue;
                }
                if (isset($this->artifacts[$real])) {
                    $this->addDependency($class, $this->artifacts[$real]);
                    $this->capturedIncludes[$real] = true;
                    continue;
                }
                $this->capturedIncludes[$real] = true;
                if (self::within($real, $this->root) && !self::ignoredLocal($real, $this->root, $this->classes)) {
                    $this->supported = false;
                }
            }
        }

        private function captureShutdownIncludes(): void
        {
            foreach (get_included_files() as $file) {
                $real = realpath($file);
                if (!is_string($real) || isset($this->capturedIncludes[$real])) {
                    continue;
                }
                if (isset($this->artifacts[$real])) {
                    foreach (array_keys($this->classes) as $class) {
                        $this->addDependency($class, $this->artifacts[$real]);
                    }
                    continue;
                }
                if (self::within($real, $this->root) && !self::ignoredLocal($real, $this->root, $this->classes)) {
                    $this->supported = false;
                }
            }
        }

        private function addDependency(string $class, string $artifact): void
        {
            if (isset($this->dependencies[$class][$artifact])) {
                return;
            }
            if ($this->dependencyCount >= self::MAX_DEPENDENCIES) {
                $this->supported = false;
                return;
            }
            $this->dependencies[$class][$artifact] = true;
            ++$this->dependencyCount;
        }

        private static function ignoredLocal(string $path, string $root, array $classes): bool
        {
            if (self::within($path, $root . DIRECTORY_SEPARATOR . 'vendor')) {
                return true;
            }
            return in_array(self::relative($path, $root), array_values($classes), true);
        }

        private static function outputPath(mixed $value): string
        {
            if (!is_string($value) || $value === '' || is_link($value) || is_dir($value)) {
                throw new RuntimeException('Affected PHPUnit output is invalid');
            }
            $directory = realpath(dirname($value));
            if (!is_string($directory) || !is_dir($directory) || !is_writable($directory)) {
                throw new RuntimeException('Affected PHPUnit output is invalid');
            }
            return $directory . DIRECTORY_SEPARATOR . basename($value);
        }

        private static function realDirectory(mixed $value): string
        {
            $real = is_string($value) ? realpath($value) : false;
            if (!is_string($real) || !is_dir($real) || is_link($value)) {
                throw new RuntimeException('Affected PHPUnit root is invalid');
            }
            return rtrim($real, DIRECTORY_SEPARATOR);
        }

        private static function within(string $path, string $root): bool
        {
            return $path === $root || str_starts_with($path, rtrim($root, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR);
        }

        private static function relative(string $path, string $root): string
        {
            if (!self::within($path, $root)) {
                throw new RuntimeException('Affected PHPUnit path is outside the project');
            }
            return str_replace(DIRECTORY_SEPARATOR, '/', substr($path, strlen($root) + 1));
        }

        private static function relativePath(string $path): bool
        {
            if ($path === '' || str_contains($path, '\\') || str_starts_with($path, '/')) {
                return false;
            }
            foreach (explode('/', $path) as $segment) {
                if ($segment === '' || $segment === '.' || $segment === '..') {
                    return false;
                }
            }
            return true;
        }
    }
    }
}
