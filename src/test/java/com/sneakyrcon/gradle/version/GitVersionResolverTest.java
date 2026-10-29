package com.sneakyrcon.gradle.version;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitVersionResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsNormalizedVersionForSemverTags() throws IOException {
        Path repository = initializeRepository("main");
        commit(repository, "release");
        annotatedTag(repository, "v1.2.3");

        assertEquals("1.2.3", resolve(repository));

        tag(repository, "1.2.3");
        assertEquals("1.2.3", resolve(repository));
    }

    @Test
    void exactRTagWinsOverSemverAndKeepsItsOriginalText() throws IOException {
        Path repository = initializeRepository("main");
        commit(repository, "release");
        tag(repository, "v1.2.3");
        tag(repository, "R26_0612");
        tag(repository, "R26_0612.2");

        assertEquals("R26_0612.2", resolve(repository));
        assertEquals("R26_0612", GitVersionResolver.resolve(repository,
                Map.of("GITHUB_REF", "refs/tags/R26_0612")));
        assertEquals("R26_0612.2", GitVersionResolver.resolve(repository,
                Map.of("GITHUB_REF", "refs/tags/v1.2.3")));
    }

    @Test
    void untaggedCommitUsesNextPatchAndEveryBranchName() throws IOException {
        for (String branch : new String[]{"main", "master", "test", "feature/claim-travel"}) {
            Path repository = initializeRepository(branch);
            commit(repository, "release");
            tag(repository, "1.4.9");
            commit(repository, "next");

            assertEquals("1.4.10-" + branch.replace('/', '-') + "-SNAPSHOT", resolve(repository));
        }
    }

    @Test
    void nearestReachableSemverTagCanBeOnMergedHistory() throws IOException {
        Path repository = initializeRepository("main");
        commit(repository, "base release");
        tag(repository, "v1.0.0");

        git(repository, "checkout", "-b", "release-line");
        commit(repository, "release line change");
        tag(repository, "v2.0.0");

        git(repository, "checkout", "main");
        commit(repository, "mainline change");
        git(repository, "merge", "--no-ff", "release-line", "-m", "merge release line");

        assertEquals("2.0.1-main-SNAPSHOT", resolve(repository));
    }

    @Test
    void ancestorRTagDoesNotBecomeTheVersionBase() throws IOException {
        Path repository = initializeRepository("test");
        commit(repository, "semver release");
        tag(repository, "v1.0.20");
        commit(repository, "R release");
        tag(repository, "R26_0612.2");

        assertEquals("R26_0612.2", resolve(repository));
        commit(repository, "development after R release");

        assertEquals("1.0.21-test-SNAPSHOT", resolve(repository));
    }

    @Test
    void noSemverTagsStartsAtZeroOneZero() throws IOException {
        Path repository = initializeRepository("test");
        commit(repository, "first commit");

        assertEquals("0.1.0-test-SNAPSHOT", resolve(repository));
    }

    @Test
    void detachedCheckoutUsesGiteaHeadBranchWhenProvided() throws IOException {
        Path repository = initializeRepository("main");
        commit(repository, "release");
        tag(repository, "v2.4.1");
        commit(repository, "development");
        git(repository, "checkout", "--detach", "HEAD");

        assertEquals("2.4.2-feature-from-pr-SNAPSHOT", GitVersionResolver.resolve(repository,
                Map.of("GITHUB_HEAD_REF", "feature/from-pr")));
    }

    @Test
    void actionBranchEnvironmentWinsOverRunnerCheckoutBranch() throws IOException {
        Path repository = initializeRepository("runner-ref");
        commit(repository, "release");
        tag(repository, "v2.4.1");
        commit(repository, "development");

        assertEquals("2.4.2-feature-from-action-SNAPSHOT", GitVersionResolver.resolve(repository,
                Map.of("GITEA_HEAD_REF", "feature/from-action")));
    }

    private Path initializeRepository(String branch) throws IOException {
        Path repository = temporaryDirectory.resolve("repo-" + System.nanoTime());
        Files.createDirectories(repository);
        git(repository, "init", "--quiet", "--initial-branch", branch);
        git(repository, "config", "user.name", "Version Test");
        git(repository, "config", "user.email", "version-test@example.invalid");
        return repository;
    }

    private void commit(Path repository, String message) throws IOException {
        String file = "commit-" + System.nanoTime() + ".txt";
        Files.writeString(repository.resolve(file), message);
        git(repository, "add", file);
        git(repository, "commit", "--quiet", "-m", message);
    }

    private void tag(Path repository, String name) throws IOException {
        git(repository, "tag", name);
    }

    private void annotatedTag(Path repository, String name) throws IOException {
        git(repository, "tag", "-a", name, "-m", name);
    }

    private static String resolve(Path repository) {
        return GitVersionResolver.resolve(repository, Map.of());
    }

    private static void git(Path repository, String... arguments) {
        try {
            Process process = new ProcessBuilder(concat(new String[]{"git"}, arguments))
                    .directory(repository.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new AssertionError("git " + String.join(" ", arguments) + " failed: " + output);
            }
        } catch (IOException e) {
            throw new AssertionError("Unable to run Git test command.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while running Git test command.", e);
        }
    }

    private static String[] concat(String[] first, String[] rest) {
        String[] result = new String[first.length + rest.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(rest, 0, result, first.length, rest.length);
        return result;
    }
}
