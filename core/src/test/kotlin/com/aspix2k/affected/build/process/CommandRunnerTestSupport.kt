package com.aspix2k.affected.build.process

import com.intellij.util.concurrency.AppExecutorUtil

internal fun CommandRunner.capture(process: Process, timeoutSeconds: Long, maxBytes: Int): String? =
    captureProcessOutput(
        process,
        ProcessTreeTermination(process.toHandle(), executor = AppExecutorUtil.getAppScheduledExecutorService()),
        timeoutSeconds,
        maxBytes,
    )
