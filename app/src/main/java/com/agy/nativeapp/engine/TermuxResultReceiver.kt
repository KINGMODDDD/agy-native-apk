package com.agy.nativeapp.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val internalErrorCode: Int,
    val internalErrorMessage: String?
)

class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (executionId < 0) return

        val bundle: Bundle? = intent.getBundleExtra(TERMUX_RESULT_BUNDLE)
        val result = if (bundle == null) {
            TermuxCommandResult(
                stdout = "",
                stderr = "",
                exitCode = -1,
                internalErrorCode = -1,
                internalErrorMessage = "Termux returned no result bundle."
            )
        } else {
            TermuxCommandResult(
                stdout = bundle.getString(KEY_STDOUT).orEmpty(),
                stderr = bundle.getString(KEY_STDERR).orEmpty(),
                exitCode = bundle.getInt(KEY_EXIT_CODE, -1),
                internalErrorCode = bundle.getInt(KEY_ERR, -1),
                internalErrorMessage = bundle.getString(KEY_ERRMSG)
            )
        }

        pendingResults.remove(executionId)?.complete(result)
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "com.agy.nativeapp.EXECUTION_ID"

        private const val TERMUX_RESULT_BUNDLE = "com.termux.app.service.plugin.result.bundle"
        private const val KEY_STDOUT = "com.termux.app.service.plugin.result.bundle.stdout"
        private const val KEY_STDERR = "com.termux.app.service.plugin.result.bundle.stderr"
        private const val KEY_EXIT_CODE = "com.termux.app.service.plugin.result.bundle.exit_code"
        private const val KEY_ERR = "com.termux.app.service.plugin.result.bundle.err"
        private const val KEY_ERRMSG = "com.termux.app.service.plugin.result.bundle.errmsg"

        private val nextId = AtomicInteger(1000)
        private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

        fun registerExecution(): Pair<Int, CompletableDeferred<TermuxCommandResult>> {
            val id = nextId.incrementAndGet()
            val deferred = CompletableDeferred<TermuxCommandResult>()
            pendingResults[id] = deferred
            return id to deferred
        }

        fun unregisterExecution(id: Int) {
            pendingResults.remove(id)?.cancel()
        }
    }
}
