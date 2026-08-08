package com.aspix2k.affected

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class StartupRefresh : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<AffectedState>().invalidate()
    }
}
