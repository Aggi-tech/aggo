package com.aggitech.aggo.migration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeText

internal enum class UnixCliRunner {
    GRADLE,
    MAVEN,
}

internal data class UnixCliInstallRequest(
    val commandName: String = "aggo",
    val installDir: Path = defaultUnixInstallDir(),
    val projectDir: Path = Paths.get("").toAbsolutePath(),
    val runner: UnixCliRunner = defaultUnixCliRunner(Paths.get("").toAbsolutePath()),
    val gradleTask: String = ":aggoCliRun",
)

internal fun writeUnixCliLauncher(request: UnixCliInstallRequest): Path {
    require(request.commandName.matches(Regex("^[A-Za-z][A-Za-z0-9_-]*$"))) {
        "command name must match [A-Za-z][A-Za-z0-9_-]*, got: '${request.commandName}'"
    }

    Files.createDirectories(request.installDir)
    val target = request.installDir.resolve(request.commandName)
    target.writeText(unixLauncherScript(request), Charsets.UTF_8)
    target.toFile().setExecutable(true, false)
    return target
}

internal fun defaultUnixInstallDir(): Path =
    Paths.get(System.getProperty("user.home")).resolve(".local").resolve("bin")

internal fun defaultUnixCliRunner(projectDir: Path): UnixCliRunner =
    if (Files.exists(projectDir.resolve("gradlew"))) UnixCliRunner.GRADLE else UnixCliRunner.MAVEN

private fun unixLauncherScript(request: UnixCliInstallRequest): String {
    val execLine = when (request.runner) {
        UnixCliRunner.GRADLE ->
            "exec ./gradlew -q ${shellQuote(request.gradleTask)} --args=\"\$*\""
        UnixCliRunner.MAVEN ->
            "exec mvn -q compile exec:java -Dexec.args=\"\$*\""
    }

    return """
        |#!/usr/bin/env sh
        |set -eu
        |cd ${shellQuote(request.projectDir.toAbsolutePath().normalize().toString())}
        |$execLine
        |
    """.trimMargin()
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
