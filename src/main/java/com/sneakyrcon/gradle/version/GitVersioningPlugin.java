package com.sneakyrcon.gradle.version;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.nio.file.Path;
import java.util.Map;

/** Sets the root project's version from its Git tags and current branch. */
public final class GitVersioningPlugin implements Plugin<Project> {
    /** Creates the plugin instance used by Gradle. */
    public GitVersioningPlugin() {
    }

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("com.sneakyrcon.gradle.version must be applied to the root project.");
        }

        String version = GitVersionResolver.resolve(project.getRootDir().toPath(), System.getenv());
        project.setVersion(version);
    }
}
