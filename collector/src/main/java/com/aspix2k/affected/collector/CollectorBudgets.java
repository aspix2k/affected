package com.aspix2k.affected.collector;

final class CollectorBudgets {
    static final int MAX_DEPENDENCIES_PER_TEST = 20_000;
    static final int MAX_RECORDED_PER_WORKER = 100_000;
    static final int MAX_CATALOG_ENTRIES = 250_000;
    static final int MAX_MAP_BYTES = 8 * 1024 * 1024;

    private CollectorBudgets() {
    }
}
