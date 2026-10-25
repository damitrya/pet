package com.damitrya.mimio.diagnostics

import java.io.File
import java.io.Reader
import java.io.StringReader

/**
 * Where a recording lives: the journal (appended event blocks) and the summary. Called on the diagnostics
 * I/O thread only ([DrivingDiagnostics.io]), never on the main thread.
 */
interface DiagnosticsStorage {
    fun appendJournal(text: String)
    fun journalReader(): Reader
    fun journalBytes(): Long
    fun writeSummary(text: String)
    fun readSummary(): String?
    fun clear()
}

/** App-private files: `files/driving_diagnostics/journal.txt` and `summary.properties`. */
class FileDiagnosticsStorage(private val dir: File) : DiagnosticsStorage {

    private val journal = File(dir, "journal.txt")
    private val summary = File(dir, "summary.properties")

    override fun appendJournal(text: String) {
        dir.mkdirs()
        journal.appendText(text, Charsets.UTF_8)
    }

    override fun journalReader(): Reader =
        if (journal.exists()) journal.bufferedReader(Charsets.UTF_8) else StringReader("")

    override fun journalBytes(): Long = if (journal.exists()) journal.length() else 0L

    override fun writeSummary(text: String) {
        dir.mkdirs()
        // Write aside, then rename: a crash mid-write never leaves a broken summary.
        val tmp = File(dir, "summary.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(summary)) {
            summary.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    override fun readSummary(): String? = if (summary.exists()) summary.readText(Charsets.UTF_8) else null

    override fun clear() {
        journal.delete()
        summary.delete()
    }
}
