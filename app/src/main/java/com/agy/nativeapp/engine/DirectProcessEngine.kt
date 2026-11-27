package com.agy.nativeapp.engine

import com.agy.nativeapp.model.AgySettings
import com.agy.nativeapp.model.AgyStreamEvent
import com.agy.nativeapp.parser.AgyStreamParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DirectProcessEngine : AgyExecutionEngine {

    private val activeProcesses = ConcurrentHashMap<String, Process>()

    override fun execute(settings: AgySettings, request: AgyRunRequest): Flow<AgyStreamEvent> =
        channelFlow {
            val command = if (settings.useRootForDirectProcess) {
                val cmdString = buildString {
                    append("cd ")
                    append(AgyCommandBuilder.shellQuote(request.workdir))
                    append(" && ")
                    append(AgyCommandBuilder.buildDisplayCommand(settings, request))
                }
                listOf("su", "-c", cmdString)
            } else {
                listOf(settings.agyBinaryPath) + AgyCommandBuilder.buildArgs(settings, request)
            }

            val pb = ProcessBuilder(command).apply {
                val workdirFile = File(request.workdir)
                if (workdirFile.exists() && workdirFile.isDirectory) {
                    directory(workdirFile)
                }
                val env = environment()
                val currentPath = env["PATH"].orEmpty()
                val termuxBin = "/data/data/com.termux/files/usr/bin"
                if (!currentPath.contains(termuxBin)) {
                    env["PATH"] = if (currentPath.isBlank()) termuxBin else "$termuxBin:$currentPath"
                }
                env.putAll(AgyCommandBuilder.parseExtraEnv(settings.extraEnvLines))
            }

            val process = try {
                pb.start()
            } catch (e: Exception) {
                send(
                    AgyStreamEvent.ProcessFinished(
                        exitCode = -1,
                        errorMessage = "Failed to start process '${command.firstOrNull()}': ${e.message}"
                    )
                )
                close()
                return@channelFlow
            }

            activeProcesses[request.runId] = process
            // Close stdin immediately so headless `agy -p` never waits on interactive EOF
            runCatching { process.outputStream.close() }

            try {
                coroutineScope {
                    val stdoutJob = async(Dispatchers.IO) {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                val parsed = AgyStreamParser.parseLine(line)
                                if (parsed != null) {
                                    trySend(parsed)
                                }
                            }
                        }
                    }

                    val stderrJob = async(Dispatchers.IO) {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (line.isNotBlank()) {
                                    trySend(AgyStreamEvent.Stderr(line))
                                }
                            }
                        }
                    }

                    awaitAll(stdoutJob, stderrJob)
                }

                val exitCode = process.waitFor()
                send(AgyStreamEvent.ProcessFinished(exitCode = exitCode))
            } catch (e: Exception) {
                send(
                    AgyStreamEvent.ProcessFinished(
                        exitCode = -1,
                        errorMessage = e.message ?: "Process execution interrupted"
                    )
                )
            } finally {
                activeProcesses.remove(request.runId)
                runCatching { process.destroy() }
            }

            awaitClose {
                activeProcesses.remove(request.runId)?.let { proc ->
                    runCatching { proc.destroyForcibly() }
                }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun cancel(settings: AgySettings, runId: String) {
        activeProcesses.remove(runId)?.let { process ->
            runCatching { process.destroyForcibly() }
        }
    }
}
