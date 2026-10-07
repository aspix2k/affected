package com.aspix2k.affected.build

import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy

internal fun multiRootProject(root: File): Project = Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java),
) { _, method, _ ->
    when (method.name) {
        "getBasePath" -> root.path
        "getLocationHash" -> "multi-root"
        else -> error("Unexpected Project call: ${method.name}")
    }
} as Project
