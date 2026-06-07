package com.aggitech.aggo.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Installs the {@code aggo} Unix launcher.
 */
@Mojo(name = "install", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public final class InstallMojo extends AbstractMojo {

    private static final Pattern COMMAND_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*$");

    /**
     * Kotlin/Java main class that delegates to {@code AggoMigrateTask.runFromArgs(args)}.
     */
    @Parameter(property = "aggo.mainClass", required = true)
    private String mainClass;

    /**
     * Installed command name.
     */
    @Parameter(property = "aggo.command", defaultValue = "aggo")
    private String command;

    /**
     * Directory where the launcher is installed.
     */
    @Parameter(property = "aggo.installDir", defaultValue = "${user.home}/.local/bin")
    private Path installDir;

    /**
     * Project directory where Maven commands should run.
     */
    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private Path projectDir;

    @Override
    public void execute() throws MojoExecutionException {
        if (!COMMAND_NAME.matcher(command).matches()) {
            throw new MojoExecutionException(
                "aggo.command must match [A-Za-z][A-Za-z0-9_-]*, got: " + command
            );
        }
        if (mainClass == null || mainClass.isBlank()) {
            throw new MojoExecutionException("Configure <mainClass> or pass -Daggo.mainClass=...");
        }

        try {
            Files.createDirectories(installDir);
            Path launcher = installDir.resolve(command);
            Files.writeString(launcher, launcherScript(), StandardCharsets.UTF_8);
            if (!launcher.toFile().setExecutable(true, false)) {
                throw new MojoExecutionException("Could not mark launcher executable: " + launcher);
            }
            getLog().info("Installed Aggo CLI: " + launcher);
            getLog().info("Run: " + command + " migrate generate --name add_orders");
            getLog().info("If the command is not found, add " + installDir + " to PATH.");
        } catch (IOException ex) {
            throw new MojoExecutionException("Could not install Aggo CLI", ex);
        }
    }

    private String launcherScript() {
        return "#!/usr/bin/env sh\n" +
            "set -eu\n" +
            "cd " + shellQuote(projectDir.toAbsolutePath().normalize().toString()) + "\n" +
            "exec mvn -q compile exec:java -Dexec.mainClass=" + shellQuote(mainClass) +
            " -Dexec.args=\"$*\"\n";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
