package com.aspix2k.affected.impact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

internal class DependencyMapPromotionValidationTest : DependencyMapFixture() {

    @Test
    fun `promotion keeps the previous map for every invalid worker shape`() = assertKept(
        mapOf(
            "blank worker id" to candidate(
                expectedWorkers = setOf(" "),
                workers = listOf(worker(" ", record(betaTest, beta))),
                artifacts = listOf(beta),
            ),
            "second worker blank" to candidate(
                expectedWorkers = setOf("worker-1", " "),
                workers = listOf(worker("worker-1", record(alphaTest, alpha)), worker(" ", record(betaTest, beta))),
                artifacts = listOf(alpha, beta),
            ),
            "duplicate worker id" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(
                    worker("worker-1", record(alphaTest, alpha)),
                    worker("worker-1", record(betaTest, beta)),
                ),
                artifacts = listOf(alpha, beta),
            ),
            "unexpected worker" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(
                    worker("worker-1", record(alphaTest, alpha)),
                    worker("worker-2", record(betaTest, beta)),
                ),
                artifacts = listOf(alpha, beta),
            ),
            "worker without records" to candidate(
                expectedWorkers = setOf("worker-1", "worker-2"),
                workers = listOf(worker("worker-1", record(alphaTest, alpha)), worker("worker-2")),
                artifacts = listOf(alpha),
            ),
            "first worker without records" to candidate(
                expectedWorkers = setOf("worker-1", "worker-2"),
                workers = listOf(worker("worker-1"), worker("worker-2", record(alphaTest, alpha))),
                artifacts = listOf(alpha),
            ),
        ),
    )

    @Test
    fun `promotion keeps the previous map for every invalid content shape`() = assertKept(
        mapOf(
            "duplicate artifact" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(worker("worker-1", record(alphaTest, alpha))),
                artifacts = listOf(alpha, alpha),
            ),
            "duplicate artifact after a unique one" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(worker("worker-1", record(alphaTest, alpha))),
                artifacts = listOf(beta, alpha, alpha),
            ),
            "extra produced test class" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(worker("worker-1", record(alphaTest, alpha), record(betaTest, beta))),
                artifacts = listOf(alpha, beta),
                expectedTestClasses = setOf(alphaTest),
            ),
            "record outside the catalog" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(worker("worker-1", record(alphaTest, beta))),
                artifacts = listOf(alpha),
            ),
            "record with a stale dependency" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(worker("worker-1", record(alphaTest, dependency("Alpha", "alpha-stale")))),
                artifacts = listOf(alpha),
            ),
        ),
    )

    @Test
    fun `promotion keeps the previous map for every invalid metadata shape`() = assertKept(
        mapOf(
            "no expected workers" to candidate(
                expectedWorkers = emptySet(),
                workers = emptyList(),
                artifacts = listOf(alpha),
                expectedTestClasses = setOf(alphaTest),
            ),
            "no expected test classes" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(worker("worker-1", record(alphaTest, alpha))),
                artifacts = listOf(alpha),
                expectedTestClasses = emptySet(),
            ),
            "no workers or records" to candidate(
                expectedWorkers = emptySet(),
                workers = emptyList(),
                artifacts = listOf(alpha),
                expectedTestClasses = emptySet(),
            ),
            "blank run id" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(worker("worker-1", record(alphaTest, alpha))),
                artifacts = listOf(alpha),
                completedRunId = " ",
            ),
            "schema mismatch" to candidate(
                expectedWorkers = setOf("worker-1"),
                workers = listOf(worker("worker-1", record(alphaTest, alpha))),
                artifacts = listOf(alpha),
                mapIdentity = identity.copy(schemaVersion = DEPENDENCY_MAP_SCHEMA_VERSION + 1),
            ),
        ),
    )

    @Test
    fun `a promoted map carries the candidate identity catalog and records`() {
        val candidate = candidate(
            expectedWorkers = setOf("worker-1", "worker-2"),
            workers = listOf(
                worker("worker-1", record(alphaTest, alpha)),
                worker("worker-2", record(betaTest, beta)),
            ),
            artifacts = listOf(alpha, beta),
        )

        assertEquals(
            complete(
                artifacts = listOf(alpha, beta),
                records = listOf(record(alphaTest, alpha), record(betaTest, beta)),
                completedRunId = "candidate-run",
            ),
            DependencyMapPromotion.promote(null, candidate),
        )
    }

    private fun assertKept(candidates: Map<String, DependencyMapCandidate>) {
        val previous = complete(listOf(alpha), listOf(record(alphaTest, alpha)))
        candidates.forEach { (name, candidate) ->
            assertSame(previous, DependencyMapPromotion.promote(previous, candidate), name)
            assertNull(DependencyMapPromotion.promote(null, candidate), name)
        }
    }
}
