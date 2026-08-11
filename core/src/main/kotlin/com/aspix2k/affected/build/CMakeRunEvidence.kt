package com.aspix2k.affected.build

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal fun promoteCMakeBaseline(
    store: CMakeTestBaselineStore,
    before: CMakeTestSnapshot?,
    after: CMakeTestSnapshot?,
    report: Path?,
    full: Boolean,
    passed: Boolean,
): Boolean = runCatching {
    if (!full || !passed) return false
    if (before == null || after == null || report == null) return false
    if (before != after) return false
    if (!completeCTestReport(report, after.allTests)) return false
    store.write(after)
    true
}.getOrDefault(false)

internal fun completeCTestReport(path: Path, expectedTests: Set<String>): Boolean = runCatching {
    require(expectedTests.isNotEmpty())
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    require(Files.size(path) in 1..MAX_CTEST_REPORT_SIZE)
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
    val suite = document.documentElement
    require(suite.tagName == "testsuite")
    require(suite.intAttribute("failures") == 0)
    require(suite.intAttribute("errors", 0) == 0)
    require(suite.intAttribute("disabled", 0) == 0)
    require(suite.intAttribute("skipped", 0) == 0)
    val cases = suite.getElementsByTagName("testcase")
    require(suite.intAttribute("tests") == cases.length)
    val executed = LinkedHashSet<String>()
    repeat(cases.length) { index ->
        val case = cases.item(index) as Element
        require(case.getElementsByTagName("failure").length == 0)
        require(case.getElementsByTagName("error").length == 0)
        require(case.getElementsByTagName("skipped").length == 0)
        val name = case.getAttribute("name")
        require(name.isNotBlank() && executed.add(name))
    }
    executed == expectedTests
}.getOrDefault(false)

private fun Element.intAttribute(name: String, default: Int? = null): Int = when {
    hasAttribute(name) -> getAttribute(name).toInt().also { require(it >= 0) }
    default != null -> default
    else -> error(name)
}

private const val MAX_CTEST_REPORT_SIZE = 16L * 1024 * 1024
