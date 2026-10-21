package com.damitrya.mimio.state

/**
 * Lets the settings' debug tools read the running service's behaviour statistics ([ActivityStats]) — both live in
 * the same process. The service sets [source] while it runs and clears it when it stops. Main thread only.
 */
object ActivityDiagnostics {
    var source: (() -> ActivityStats)? = null

    /** The current session's statistics, or null when Mimio is not running. */
    fun current(): ActivityStats? = source?.invoke()
}
