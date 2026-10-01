package com.aspix2k.affected.build.python

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import java.io.File
import kotlin.io.path.createTempDirectory

class PythonIdeSdkTest : BasePlatformTestCase() {

    fun testTheProjectPythonSdkIsTheInterpreterAndOtherSdkTypesAreIgnored() {
        val root = createTempDirectory("python-ide-sdk").toFile()
        val home = File(root, "sdk/bin/python").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

        useProjectSdk(sdk("Python SDK", home.path))
        assertEquals(home.path, ideInterpreter(project, root))

        useProjectSdk(sdk("JavaSDK", home.path))
        assertNull(ideInterpreter(project, root))
    }

    private fun sdk(type: String, home: String): Sdk {
        val sdk = ProjectJdkTable.getInstance().createSdk("affected-$type", sdkType(type))
        val modificator = sdk.sdkModificator
        modificator.homePath = home
        runWriteAction {
            modificator.commitChanges()
            ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable)
        }
        return sdk
    }

    private fun useProjectSdk(sdk: Sdk) = runWriteAction {
        ProjectRootManager.getInstance(project).projectSdk = sdk
    }

    private fun sdkType(name: String): SdkTypeId = object : SdkTypeId {
        override fun getName(): String = name
        override fun getVersionString(sdk: Sdk): String? = null
        override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) = Unit
        override fun loadAdditionalData(currentSdk: Sdk, additional: Element): SdkAdditionalData? = null
    }
}
