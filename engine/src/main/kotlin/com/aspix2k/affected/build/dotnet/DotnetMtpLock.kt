package com.aspix2k.affected.build.dotnet

import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal fun nativeMtpSdkSupported(version: String): Boolean = NATIVE_MTP_SDK.matches(version)

internal fun nativeMtpSdkVersion(element: JsonElement?): String? =
    element?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(::nativeMtpSdkSupported)

internal fun nativeMtpRequestedXunitVersion(requested: String): String? =
    NATIVE_MTP_XUNIT_REQUESTED.matchEntire(requested)?.groupValues?.get(1)

internal fun nativeMtpLockStructure(lock: JsonObject, xunitVersion: String): Boolean = runCatching {
    require(lock.get("version").asInt == 1)
    val dependencies = lock.getAsJsonObject("dependencies")
    require(dependencies.keySet() == setOf("net10.0"))
    val packages = dependencies.getAsJsonObject("net10.0")
    require(packages.size() in 1..MAX_NATIVE_MTP_LOCKED_PACKAGES)
    val resolved = packages.entrySet().associate { (name, value) ->
        val item = value.asJsonObject
        require(item.get("contentHash").asString.isNotBlank())
        name to Triple(item.get("type").asString, item.get("resolved").asString, item.get("requested")?.asString)
    }
    val direct = resolved.filterValues { it.first == "Direct" }
    require(direct.keys == setOf("xunit.v3"))
    require(resolved.all { (_, value) -> value.first == "Direct" || value.first == "Transitive" })
    val xunit = resolved.getValue("xunit.v3")
    require(xunit.second == xunitVersion && xunit.third == "[$xunitVersion, $xunitVersion]")
    listOf("xunit.v3.mtp-v2", "xunit.v3.core.mtp-v2", "xunit.v3.common", "xunit.v3.assert").forEach { name ->
        require(resolved[name]?.second == xunitVersion)
    }
    require(resolved.keys.none { it.endsWith("mtp-v1", ignoreCase = true) })
    require(NATIVE_MTP_PLATFORM_VERSION.matches(resolved.getValue("Microsoft.Testing.Platform").second))
    true
}.getOrDefault(false)

private val NATIVE_MTP_XUNIT_REQUESTED = Regex("\\[(4\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*))]")
private val NATIVE_MTP_PLATFORM_VERSION = Regex("2\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")
private const val MAX_NATIVE_MTP_LOCKED_PACKAGES = 128
private val NATIVE_MTP_SDK = Regex("10\\.0\\.(?:[4-9][0-9]{2}|[1-9][0-9]{3,})")
