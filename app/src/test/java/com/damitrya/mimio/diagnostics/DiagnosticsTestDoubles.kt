package com.damitrya.mimio.diagnostics

import java.io.Reader
import java.io.StringReader
import java.util.concurrent.Executor

/** In-memory journal and summary. */
class MemoryDiagnosticsStorage : DiagnosticsStorage {
    val journal = StringBuilder()
    var summary: String? = null
    var clears = 0

    override fun appendJournal(text: String) {
        journal.append(text)
    }

    override fun journalReader(): Reader = StringReader(journal.toString())
    override fun journalBytes(): Long = journal.toString().toByteArray(Charsets.UTF_8).size.toLong()
    override fun writeSummary(text: String) {
        summary = text
    }

    override fun readSummary(): String? = summary
    override fun clear() {
        clears++
        journal.setLength(0)
        summary = null
    }

    fun savedSummary(): DrivingDiagnosticsSummary = DrivingDiagnosticsSummary.fromText(summary!!)
}

/** Runs I/O tasks at once, on the calling thread. */
object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/** Manually advanced monotonic time. */
class TestClock(var now: Long = 100_000L) : () -> Long {
    override fun invoke(): Long = now
    fun advance(ms: Long) { now += ms }
}
