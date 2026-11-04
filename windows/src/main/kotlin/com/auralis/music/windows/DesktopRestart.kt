package com.auralis.music.windows

import java.util.concurrent.TimeUnit

internal const val RESTART_ARGUMENT = "--restart-after="

/** Wait before touching profile files or acquiring the single-instance lock. */
internal fun awaitRestartParent(args: Array<String>) {
    val value = args.singleOrNull { it.startsWith(RESTART_ARGUMENT) } ?: return
    val pid = requireNotNull(value.removePrefix(RESTART_ARGUMENT).toLongOrNull()) { "Invalid restart process" }
    require(pid > 0 && pid != ProcessHandle.current().pid()) { "Invalid restart process" }
    ProcessHandle.of(pid).orElse(null)?.onExit()?.get(15, TimeUnit.SECONDS)
}

internal fun desktopRestartCommand(
    appPath: String?, executable: String?, processArguments: List<String>, appArguments: List<String>, pid: Long
): List<String> {
    val arguments = appArguments.filterNot { it.startsWith(RESTART_ARGUMENT) } + "$RESTART_ARGUMENT$pid"
    return if (!appPath.isNullOrBlank()) listOf(appPath) + arguments else {
        require(!executable.isNullOrBlank()) { "Application launcher is unavailable" }
        // A development launch needs its JVM options/classpath as well as its main class.
        listOf(executable) + processArguments.filterNot { it.startsWith(RESTART_ARGUMENT) } + "$RESTART_ARGUMENT$pid"
    }
}

internal fun launchDesktopRestart(args: Array<String>) {
    val process = ProcessHandle.current()
    val info = process.info()
    val command = desktopRestartCommand(
        System.getProperty("jpackage.app-path"), info.command().orElse(null),
        info.arguments().orElse(emptyArray()).toList(), args.toList(), process.pid()
    )
    // The child waits for this process to finish the existing orderly shutdown path.
    ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
}
