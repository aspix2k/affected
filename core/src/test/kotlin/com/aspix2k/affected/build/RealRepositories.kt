package com.aspix2k.affected.build

import com.google.gson.Gson

data class RealRepository(
    val id: String,
    val url: String,
    val sha: String,
    val license: String,
    val ecosystem: String,
    val budgetSeconds: Long,
    val tools: List<String>,
    val setup: List<List<String>>,
    val environment: Map<String, String>? = null,
    val roots: List<String>,
    val modules: List<String>,
    val scenarios: List<RealScenario>,
) {
    override fun toString(): String = id
}

data class RealScenario(
    val name: String,
    val files: List<String>,
    val tasks: List<String>,
    val command: List<String>,
    val output: String,
    val pass: Boolean,
    val testDependents: Boolean = false,
)

internal object RealRepositories {

    val all: List<RealRepository> by lazy {
        val manifest = CliConformanceRepository.configured.repositoryFile("conformance/real-repositories.json")
        Gson().fromJson(manifest.readText(), Manifest::class.java).repositories.onEach(::validate)
    }

    private data class Manifest(val repositories: List<RealRepository>)

    private fun validate(repository: RealRepository) {
        require(SHA.matches(repository.sha)) { "${repository.id}: pin a full commit SHA, got ${repository.sha}" }
        require(repository.url.startsWith("https://")) { "${repository.id}: clone URL must be https" }
        require(repository.scenarios.isNotEmpty()) { "${repository.id}: no scenarios" }
        repository.scenarios.forEach { Regex(it.output) }
    }

    private val SHA = Regex("[0-9a-f]{40}")
}
