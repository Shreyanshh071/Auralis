package com.auralis.music.windows.dev

import com.auralis.music.windows.settings.InstallerDefaults
import kotlinx.coroutines.runBlocking
import java.io.File

/** Explicit settings-only export; never scans portable test profiles or account/session stores. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 2) { "Provide the installed profile and the output JSON path" }
    val source = File(args[0]).canonicalFile
    require(File(source, "settings/auralis_appearance_settings.preferences_pb").isFile)
    val snapshot = InstallerDefaults.snapshot(source)
    val output = File(args[1])
    output.parentFile.mkdirs()
    output.writeText(snapshot.toString(2) + "\n")
    println("Exported ${snapshot.getJSONObject("stores").length()} preference stores and ${snapshot.getJSONObject("properties").length()} Windows settings files")
}
