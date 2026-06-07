package com.aggitech.aggo.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InstallMojoTest {

    @Test
    void installsExecutableAggoLauncher() throws Exception {
        Path dir = Files.createTempDirectory("aggo-maven-plugin-bin");
        Path project = Files.createTempDirectory("aggo-maven-plugin-project");
        InstallMojo mojo = mojo(dir, project, "aggo", "com.example.db.MigrationsKt");

        mojo.execute();

        Path launcher = dir.resolve("aggo");
        String script = Files.readString(launcher);
        assertTrue(Files.exists(launcher));
        assertTrue(launcher.toFile().canExecute());
        assertTrue(script.startsWith("#!/usr/bin/env sh\n"));
        assertTrue(script.contains("cd '" + project + "'"));
        assertTrue(script.contains("-Dexec.mainClass='com.example.db.MigrationsKt'"));
        assertTrue(script.contains("-Dexec.args=\"$*\""));
    }

    @Test
    void rejectsInvalidCommandName() throws Exception {
        InstallMojo mojo = mojo(
            Files.createTempDirectory("aggo-maven-plugin-bin"),
            Files.createTempDirectory("aggo-maven-plugin-project"),
            "bad name",
            "com.example.db.MigrationsKt"
        );

        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    private static InstallMojo mojo(Path installDir, Path projectDir, String command, String mainClass) throws Exception {
        InstallMojo mojo = new InstallMojo();
        set(mojo, "installDir", installDir);
        set(mojo, "projectDir", projectDir);
        set(mojo, "command", command);
        set(mojo, "mainClass", mainClass);
        return mojo;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
