package com.aggitech.aggo.migration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeText

internal enum class UnixCliRunner {
    AUTO,
    GRADLE,
    MAVEN,
}

internal data class UnixCliInstallRequest(
    val commandName: String = "aggo",
    val installDir: Path = defaultUnixInstallDir(),
    val projectDir: Path = defaultUnixProjectDir(Paths.get("").toAbsolutePath()),
    val runner: UnixCliRunner = UnixCliRunner.AUTO,
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

internal fun defaultUnixProjectDir(start: Path): Path {
    var current = start.toAbsolutePath().normalize()
    while (true) {
        if (hasBuildFile(current)) return current
        current = current.parent ?: return start.toAbsolutePath().normalize()
    }
}

internal fun defaultUnixCliRunner(projectDir: Path): UnixCliRunner =
    if (hasGradleBuild(projectDir)) UnixCliRunner.GRADLE else UnixCliRunner.MAVEN

private fun hasBuildFile(dir: Path): Boolean =
    hasGradleBuild(dir) || Files.exists(dir.resolve("pom.xml"))

private fun hasGradleBuild(dir: Path): Boolean =
    Files.exists(dir.resolve("gradlew")) ||
        Files.exists(dir.resolve("build.gradle")) ||
        Files.exists(dir.resolve("build.gradle.kts")) ||
        Files.exists(dir.resolve("settings.gradle")) ||
        Files.exists(dir.resolve("settings.gradle.kts"))

private fun unixLauncherScript(request: UnixCliInstallRequest): String {
    val execLine = when (request.runner) {
        UnixCliRunner.AUTO ->
            """
            |if has_gradle_build "${'$'}project_dir"; then
            |  run_gradle
            |elif [ -f "${'$'}project_dir/pom.xml" ]; then
            |  run_maven
            |else
            |  echo "aggo: no Gradle or Maven build file found from ${'$'}start_dir" >&2
            |  exit 1
            |fi
            """.trimMargin()
        UnixCliRunner.GRADLE ->
            """
            |run_gradle
            """.trimMargin()
        UnixCliRunner.MAVEN ->
            "run_maven"
    }

    return """
        |#!/usr/bin/env sh
        |set -eu
        |
        |has_gradle_build() {
        |  [ -x "${'$'}1/gradlew" ] ||
        |    [ -f "${'$'}1/build.gradle" ] ||
        |    [ -f "${'$'}1/build.gradle.kts" ] ||
        |    [ -f "${'$'}1/settings.gradle" ] ||
        |    [ -f "${'$'}1/settings.gradle.kts" ]
        |}
        |
        |has_build_file() {
        |  has_gradle_build "${'$'}1" || [ -f "${'$'}1/pom.xml" ]
        |}
        |
        |find_project_root() {
        |  current="${'$'}(cd "${'$'}1" && pwd)"
        |  while :; do
        |    if has_build_file "${'$'}current"; then
        |      printf '%s\n' "${'$'}current"
        |      return
        |    fi
        |    parent="${'$'}(dirname "${'$'}current")"
        |    if [ "${'$'}parent" = "${'$'}current" ]; then
        |      printf '%s\n' "${'$'}(cd "${'$'}1" && pwd)"
        |      return
        |    fi
        |    current="${'$'}parent"
        |  done
        |}
        |
        |find_gradle_wrapper_dir() {
        |  current="${'$'}project_dir"
        |  while :; do
        |    if [ -x "${'$'}current/gradlew" ]; then
        |      printf '%s\n' "${'$'}current"
        |      return
        |    fi
        |    parent="${'$'}(dirname "${'$'}current")"
        |    if [ "${'$'}parent" = "${'$'}current" ]; then
        |      return 1
        |    fi
        |    current="${'$'}parent"
        |  done
        |}
        |
        |run_gradle() {
        |  if wrapper_dir="${'$'}(find_gradle_wrapper_dir)"; then
        |    cd "${'$'}wrapper_dir"
        |    exec ./gradlew -q -p "${'$'}project_dir" ${shellQuote(request.gradleTask)} --args="${'$'}*"
        |  fi
        |  cd "${'$'}project_dir"
        |  exec gradle -q ${shellQuote(request.gradleTask)} --args="${'$'}*"
        |}
        |
        |run_maven() {
        |  cd "${'$'}project_dir"
        |  exec mvn -q compile exec:java -Dexec.args="${'$'}*"
        |}
        |
        |start_dir="${'$'}{AGGO_PROJECT_DIR:-${'$'}(pwd)}"
        |project_dir="${'$'}(find_project_root "${'$'}start_dir")"
        |$execLine
        |
    """.trimMargin()
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
