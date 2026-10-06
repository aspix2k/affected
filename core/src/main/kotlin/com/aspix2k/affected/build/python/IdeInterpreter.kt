package com.aspix2k.affected.build.python

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

internal fun ideInterpreter(project: Project, root: File): String? =
    ApplicationManager.getApplication().runReadAction(
        Computable {
            val module = LocalFileSystem.getInstance().findFileByIoFile(root)
                ?.let { ModuleUtilCore.findModuleForFile(it, project) }
            val sdk = module?.let { ModuleRootManager.getInstance(it).sdk }
                ?: ProjectRootManager.getInstance(project).projectSdk
            configuredPythonInterpreter(sdk?.sdkType?.name, sdk?.homePath)
        },
    )
