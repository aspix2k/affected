package com.aspix2k.affected.build.process

internal fun CommandRunner.capture(process: Process, timeoutSeconds: Long, maxBytes: Int): String? =
    capture(process, ProcessTreeTermination(process.toHandle()), timeoutSeconds, maxBytes)
