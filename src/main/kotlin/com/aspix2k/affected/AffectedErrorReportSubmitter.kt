package com.aspix2k.affected

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.util.Consumer
import java.awt.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class AffectedErrorReportSubmitter : ErrorReportSubmitter() {

    private var descriptor: PluginDescriptor? = null

    override fun setPluginDescriptor(plugin: PluginDescriptor) {
        descriptor = plugin
        super.setPluginDescriptor(plugin)
    }

    override fun getReportActionText(): String = AffectedBundle.message("error.report.action")

    override fun getPrivacyNoticeText(): String = AffectedBundle.message("error.report.privacy")

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val event = events.firstOrNull()
        val title = event?.throwableText?.lineSequence()?.firstOrNull()?.take(TITLE_LIMIT).orEmpty()

        BrowserUtil.browse(issueUrl(title, body(event, additionalInfo)))

        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }

    private fun body(event: IdeaLoggingEvent?, additionalInfo: String?): String {
        val info = ApplicationInfo.getInstance()

        return buildString {
            appendLine("## What happened")
            appendLine()
            appendLine(additionalInfo?.takeIf { it.isNotBlank() } ?: "_(add what you were doing)_")
            appendLine()
            appendLine("## Environment")
            appendLine()
            appendLine("- Plugin: ${descriptor?.version ?: "unknown"}")
            val product = ApplicationNamesInfo.getInstance().fullProductName
            appendLine("- IDE: $product ${info.fullVersion} (${info.build})")
            appendLine("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("- Java: ${System.getProperty("java.version")}")
            appendLine()
            appendLine("## Stack trace")
            appendLine()
            appendLine("```")
            appendLine(event?.throwableText?.take(TRACE_LIMIT) ?: "(none)")
            appendLine("```")
        }
    }

    private fun issueUrl(title: String, body: String): String {
        val parameters = listOf(
            "labels" to "bug",
            "title" to title.ifBlank { "Exception in Affected" },
            "body" to body,
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }

        return "$ISSUES_URL?$parameters"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val ISSUES_URL = "https://github.com/aspix2k/affected/issues/new"
        const val TITLE_LIMIT = 120

        const val TRACE_LIMIT = 4000
    }
}
