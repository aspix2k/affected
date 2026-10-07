package com.aspix2k.affected.build

interface BuildSystemTraits {

    val id: String

    val sourceExtensions: Set<String>

    fun isTestSource(path: String): Boolean = false

    val consumersNeedSignatureChange: Boolean get() = false

    val singleOwnerPerRoot: Boolean get() = false

    val capabilitySource: Any get() = this
}

internal inline fun <reified T : Any> BuildSystemTraits.capability(): T? = this as? T ?: capabilitySource as? T
