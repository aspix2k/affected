package com.aspix2k.affected

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicPluginDescriptorsTest {

    @Test
    fun `require-restart blocks a dynamic update`() {
        val problems = DynamicPluginDescriptors.problems(
            mapOf("plugin.xml" to """<idea-plugin require-restart="true"><id>x</id></idea-plugin>"""),
        )
        assertTrue(problems.any { it.contains("require-restart") }, problems.toString())
    }

    @Test
    fun `legacy components block a dynamic update`() {
        val problems = DynamicPluginDescriptors.problems(
            mapOf(
                "plugin.xml" to """
                    <idea-plugin>
                      <application-components><component/></application-components>
                    </idea-plugin>
                """.trimIndent(),
            ),
        )
        assertTrue(problems.any { it.contains("application-components") }, problems.toString())
    }

    @Test
    fun `an action group without an id blocks a dynamic update`() {
        val problems = DynamicPluginDescriptors.problems(
            mapOf(
                "plugin.xml" to """
                    <idea-plugin>
                      <actions><group class="x.Group"/></actions>
                    </idea-plugin>
                """.trimIndent(),
            ),
        )
        assertTrue(problems.any { it.contains("group") && it.contains("id") }, problems.toString())
    }

    @Test
    fun `a non-dynamic extension point blocks a dynamic update`() {
        val problems = DynamicPluginDescriptors.problems(
            mapOf(
                "plugin.xml" to """
                    <idea-plugin>
                      <extensionPoints>
                        <extensionPoint name="buildSystem" interface="x.BuildSystem"/>
                      </extensionPoints>
                    </idea-plugin>
                """.trimIndent(),
            ),
        )
        assertTrue(problems.any { it.contains("dynamic") }, problems.toString())
    }

    @Test
    fun `a missing optional config file is not a complete descriptor set`() {
        val problems = DynamicPluginDescriptors.problems(
            mapOf(
                "META-INF/plugin.xml" to """
                    <idea-plugin>
                      <depends optional="true" config-file="affected-gradle.xml">com.intellij.gradle</depends>
                    </idea-plugin>
                """.trimIndent(),
            ),
        )
        assertTrue(problems.any { it.contains("affected-gradle.xml") }, problems.toString())
    }

    @Test
    fun `a missing content-module descriptor is not a complete descriptor set`() {
        val problems = DynamicPluginDescriptors.problems(
            mapOf(
                "META-INF/plugin.xml" to """
                    <idea-plugin>
                      <content><module name="affected.mcp" loading="optional"/></content>
                    </idea-plugin>
                """.trimIndent(),
            ),
        )
        assertTrue(problems.any { it.contains("affected.mcp") }, problems.toString())
    }

    @Test
    fun `a valid optional set has no problems`() {
        val problems = DynamicPluginDescriptors.problems(
            mapOf(
                "META-INF/plugin.xml" to """
                    <idea-plugin>
                      <depends optional="true" config-file="affected-gradle.xml">com.intellij.gradle</depends>
                      <content><module name="affected.mcp" loading="optional"/></content>
                      <extensionPoints>
                        <extensionPoint name="buildSystem" interface="x.BuildSystem" dynamic="true"/>
                      </extensionPoints>
                      <actions>
                        <group id="com.aspix2k.affected.Group" class="x.Group"/>
                      </actions>
                    </idea-plugin>
                """.trimIndent(),
                "META-INF/affected-gradle.xml" to "<idea-plugin/>",
                "affected.mcp.xml" to "<idea-plugin package=\"x\"/>",
            ),
        )
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the repository descriptors satisfy the DevKit dynamic rules`() {
        val problems = DynamicPluginDescriptors.problems(DynamicPluginDescriptors.repository())
        assertEquals(emptyList(), problems)
    }
}

object DynamicPluginDescriptors {

    private val FORBIDDEN_TAGS = listOf(
        "application-components",
        "project-components",
        "module-components",
    )

    fun repository(root: File = File(".")): Map<String, String> {
        val files = listOf(
            File(root, "src/main/resources/META-INF/plugin.xml"),
            File(root, "src/main/resources/META-INF/affected-gradle.xml"),
            File(root, "src/main/resources/META-INF/affected-maven.xml"),
            File(root, "mcp/src/main/resources/affected.mcp.xml"),
        )
        return files.associate { file ->
            val key = when {
                file.path.endsWith("affected.mcp.xml") -> "affected.mcp.xml"
                else -> "META-INF/${file.name}"
            }
            key to file.readText()
        }
    }

    fun problems(descriptors: Map<String, String>): List<String> =
        descriptors.flatMap { (name, xml) -> problemsIn(name, parse(name, xml), descriptors.keys) }

    private fun problemsIn(name: String, root: Element, descriptors: Set<String>): List<String> =
        restartProblems(name, root) +
            componentProblems(name, root) +
            groupProblems(name, root) +
            extensionPointProblems(name, root) +
            optionalConfigProblems(name, root, descriptors) +
            contentModuleProblems(name, root, descriptors)

    private fun restartProblems(name: String, root: Element): List<String> =
        if (root.getAttribute("require-restart") == "true") {
            listOf("$name: require-restart blocks a restartless update")
        } else {
            emptyList()
        }

    private fun componentProblems(name: String, root: Element): List<String> =
        FORBIDDEN_TAGS.filter { root.getElementsByTagName(it).length > 0 }
            .map { "$name: <$it> is a legacy component and is not dynamic" }

    private fun groupProblems(name: String, root: Element): List<String> =
        elements(root, "group")
            .filter { it.getAttribute("id").isBlank() }
            .map { "$name: every action group must have an id" }

    private fun extensionPointProblems(name: String, root: Element): List<String> =
        elements(root, "extensionPoint")
            .filter { it.getAttribute("dynamic") != "true" }
            .map { "$name: extension point ${it.getAttribute("name")} must be dynamic" }

    private fun optionalConfigProblems(name: String, root: Element, descriptors: Set<String>): List<String> =
        elements(root, "depends")
            .map { it.getAttribute("config-file") }
            .filter { it.isNotBlank() && "META-INF/$it" !in descriptors }
            .map { "$name: optional config-file $it is missing" }

    private fun contentModuleProblems(name: String, root: Element, descriptors: Set<String>): List<String> =
        elements(root, "module")
            .filter { it.parentNode.nodeName == "content" }
            .map { it.getAttribute("name") }
            .filter { "$it.xml" !in descriptors }
            .map { "$name: content module $it has no descriptor" }

    private fun elements(root: Element, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun parse(name: String, xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder()
            .parse(InputSource(xml.reader()))
            .documentElement
            ?: error("$name has no root element")
    }
}
