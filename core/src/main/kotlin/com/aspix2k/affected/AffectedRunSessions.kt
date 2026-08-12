package com.aspix2k.affected

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface AffectedOwnedSession {
    fun isActive(): Boolean
    fun stopIfActive(): Boolean
}

@Service(Service.Level.PROJECT)
class AffectedRunSessions {

    private val sessions = ConcurrentHashMap.newKeySet<AffectedOwnedSession>()
    private val expectedExternal = AtomicInteger()

    fun register(session: AffectedOwnedSession) {
        sessions.add(session)
    }

    fun register(handler: ProcessHandler) {
        register(ProcessHandlerSession(handler))
    }

    fun expectExternal() {
        expectedExternal.incrementAndGet()
    }

    fun claimExternal(): Boolean {
        while (true) {
            val current = expectedExternal.get()
            if (current <= 0) return false
            if (expectedExternal.compareAndSet(current, current - 1)) return true
        }
    }

    fun stopOwned(): Int {
        val owned = sessions.toList()
        sessions.removeAll(owned.toSet())
        return owned.count { it.stopIfActive() }
    }

    fun activeCount(): Int {
        sessions.removeIf { !it.isActive() }
        return sessions.size
    }

    companion object {
        fun getInstance(project: Project): AffectedRunSessions =
            project.getService(AffectedRunSessions::class.java)
    }
}

private class ProcessHandlerSession(
    private val handler: ProcessHandler,
) : AffectedOwnedSession {
    override fun isActive(): Boolean =
        !handler.isProcessTerminated && !handler.isProcessTerminating

    override fun stopIfActive(): Boolean {
        if (!isActive()) return false
        handler.destroyProcess()
        return true
    }
}
