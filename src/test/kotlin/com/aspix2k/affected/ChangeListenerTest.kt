package com.aspix2k.affected

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeListenerTest {

    private val extensions = setOf("kt", "rs", "go", "py", "ts", "cs", "php", "rb", "cpp")

    @Test
    fun `source changes from every supported language are relevant`() {
        listOf("Main.kt", "lib.rs", "main.go", "app.py", "index.ts", "App.cs", "index.php", "app.rb", "main.cpp")
            .forEach { assertTrue(isRelevantPath("/project/src/$it", extensions), it) }
    }

    @Test
    fun `generated and VCS files are ignored`() {
        assertFalse(isRelevantPath("/project/build/generated/Main.kt", extensions))
        assertFalse(isRelevantPath("/project/.gradle/cache/lib.rs", extensions))
        assertFalse(isRelevantPath("/project/.git/worktrees/main.go", extensions))
        assertFalse(isRelevantPath("/project/node_modules/package/index.ts", extensions))
        assertFalse(isRelevantPath("/project/target/debug/generated.rs", extensions))
    }
}
