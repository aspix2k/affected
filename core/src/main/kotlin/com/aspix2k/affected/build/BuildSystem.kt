package com.aspix2k.affected.build

import com.intellij.openapi.project.Project

data class BuildModule(
    val id: String,
    val root: String,
    val contentRoots: List<String>,
    val testTask: String,
    val compileTask: String,
    val hasTests: Boolean,
    val dependencies: Set<String> = emptySet(),
    val extraTasks: Set<String> = emptySet(),
) {
    val key: String get() = "$root|$id"
}

interface BuildSystem {

    val id: String

    /** File types whose change can affect a module of this system. */
    val sourceExtensions: Set<String>

    fun isPresent(project: Project): Boolean

    fun modules(project: Project): List<BuildModule>

    fun run(project: Project, root: String, tasks: List<String>)

    /**
     * Runs and waits, reporting whether it succeeded. Used where the answer
     * decides something — a commit that must not happen, a push to abort.
     */
    fun runAndWait(project: Project, root: String, tasks: List<String>): Boolean
}
