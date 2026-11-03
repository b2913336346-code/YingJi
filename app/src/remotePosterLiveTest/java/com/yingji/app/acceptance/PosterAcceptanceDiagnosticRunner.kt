package com.yingji.app.acceptance

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in diagnostics for the isolated phone package. Never records locals, exceptions or credentials. */
class PosterAcceptanceDiagnosticRunner : AndroidJUnitRunner() {
    private val monitor = Executors.newSingleThreadScheduledExecutor()
    override fun onStart() {
        check(targetContext.packageName == "com.yingji.app.posterlive.acceptance")
        monitor.scheduleAtFixedRate({
            runCatching {
                val traces = Thread.getAllStackTraces().entries.filter { (thread, _) ->
                    thread.name == "main" || thread.name.startsWith("Instr:") || thread.name.startsWith("Instrumentation")
                }
                File(targetContext.filesDir, "poster-acceptance-threads.txt").writeText(
                    traces.joinToString("\n") { (thread, frames) ->
                        "THREAD " + thread.name + " " + thread.state.name + "\n" +
                            frames.joinToString("\n") { "  " + it.className + "." + it.methodName + ":" + it.lineNumber }
                    }
                )
            }
        }, 5, 5, TimeUnit.SECONDS)
        super.onStart()
    }
    override fun finish(resultCode: Int, results: Bundle?) {
        monitor.shutdownNow()
        super.finish(resultCode, results)
    }
}
