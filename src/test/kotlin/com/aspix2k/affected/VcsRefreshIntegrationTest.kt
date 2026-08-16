package com.aspix2k.affected

import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import git4idea.repo.GitRepository
import java.lang.reflect.Proxy

class VcsRefreshIntegrationTest : BasePlatformTestCase() {

    fun testCompletedChangeListRefreshInvalidatesTheProjectState() {
        val state = project.service<AffectedState>()
        state.watchVcsChanges()
        val revision = state.snapshot().revision

        project.messageBus.syncPublisher(ChangeListListener.TOPIC).changeListUpdateDone()

        assertEquals(revision + 1, state.snapshot().revision)
    }

    fun testGitRepositoryRefreshInvalidatesThroughTheProjectMessageBus() {
        val state = project.service<AffectedState>()
        project.messageBus.connect(testRootDisposable).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryRefreshListener(),
        )
        val revision = state.snapshot().revision

        project.messageBus.syncPublisher(GitRepository.GIT_REPO_CHANGE).repositoryChanged(repository())

        assertEquals(revision + 1, state.snapshot().revision)
    }

    private fun repository(): GitRepository = Proxy.newProxyInstance(
        GitRepository::class.java.classLoader,
        arrayOf(GitRepository::class.java),
    ) { _, method, _ ->
        if (method.name == "getProject") project else error("Unexpected repository call: ${method.name}")
    } as GitRepository
}
