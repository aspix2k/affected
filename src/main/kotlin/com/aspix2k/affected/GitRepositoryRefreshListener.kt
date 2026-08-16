package com.aspix2k.affected

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

class GitRepositoryRefreshListener : GitRepositoryChangeListener {

    override fun repositoryChanged(repository: GitRepository) {
        invalidateAfterGitRepositoryRefresh(repository.project) { it.service<AffectedState>().invalidate() }
    }
}

internal fun invalidateAfterGitRepositoryRefresh(
    project: Project,
    frontend: Boolean = remoteFrontendProven(),
    invalidate: (Project) -> Unit,
) {
    if (!frontend && !project.isDisposed) invalidate(project)
}
