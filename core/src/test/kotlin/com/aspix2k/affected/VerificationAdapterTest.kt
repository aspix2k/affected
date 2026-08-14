package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationAdapterTest {

    @Test
    fun `a missing adapter fails the prepared group`() {
        assertFalse(preparedGroupPasses(adapterFound = false))
    }

    @Test
    fun `a present adapter is allowed to run`() {
        assertTrue(preparedGroupPasses(adapterFound = true))
    }

    @Test
    fun `a named task or toolbar check fails when the adapter is gone`() {
        assertFalse(runWithRequiredAdapter(null as String?) { true })
    }

    @Test
    fun `a named task still reports the adapter result`() {
        assertTrue(runWithRequiredAdapter("gradle") { true })
        assertFalse(runWithRequiredAdapter("gradle") { false })
    }
}
