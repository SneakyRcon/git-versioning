package com.sneakyrcon.gradle.version;

import org.gradle.api.GradleException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitVersionResolver {
    private static final Pattern SEMVER_TAG = Pattern.compile("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final Pattern R_TAG = Pattern.compile("^R([0-9]{2})_([0-9]{4})(?:\\.([0-9]+))?$");
    private static final Comparator<SemanticVersion> SEMVER_ORDER = Comparator
            .comparing(SemanticVersion::major)
            .thenComparing(SemanticVersion::minor)
            .thenComparing(SemanticVersion::patch);
    private static final Comparator<ReleaseTag> R_TAG_ORDER = Comparator
            .comparing(ReleaseTag::year)
            .thenComparing(ReleaseTag::date)
            .thenComparing(ReleaseTag::sequence);

    private GitVersionResolver() {
    }

    static String resolve(Path repository, Map<String, String> environment) {
        String explicitTag = explicitTag(environment);
        if (explicitTag != null && R_TAG.matcher(explicitTag).matches()) {
            return explicitTag;
        }

        String head = git(repository, "rev-parse", "HEAD").trim();
        Map<String, List<String>> tagsByCommit = tagsByCommit(repository);
        List<String> tagsAtHead = tagsByCommit.getOrDefault(head, List.of());

        Optional<String> exactRTag = tagsAtHead.stream()
                .filter(tag -> R_TAG.matcher(tag).matches())
                .max(Comparator.comparing(tag -> parseRTag(tag)))
                .map(tag -> tag);
        if (exactRTag.isPresent()) {
            return exactRTag.get();
        }

        if (explicitTag != null) {
            Optional<String> exactVersion = releaseVersion(explicitTag);
            if (exactVersion.isPresent()) {
                return exactVersion.get();
            }
        }

        Optional<SemanticVersion> exactSemver = tagsAtHead.stream()
                .map(GitVersionResolver::parseSemver)
                .flatMap(Optional::stream)
                .max(SEMVER_ORDER);
        if (exactSemver.isPresent()) {
            return exactSemver.get().toString();
        }

        Optional<SemanticVersion> nearestTag = nearestSemanticVersion(repository, head, tagsByCommit);
        SemanticVersion nextVersion = nearestTag
                .map(baseVersion -> new SemanticVersion(
                        baseVersion.major(), baseVersion.minor(), baseVersion.patch().add(BigInteger.ONE)))
                .orElse(new SemanticVersion(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO));
        String branch = branchName(repository, environment, head);
        return nextVersion + "-" + sanitizeBranch(branch) + "-SNAPSHOT";
    }

    private static Optional<SemanticVersion> nearestSemanticVersion(
            Path repository,
            String head,
            Map<String, List<String>> tagsByCommit) {
        TaggedSemanticVersion nearest = null;
        for (Map.Entry<String, List<String>> entry : tagsByCommit.entrySet()) {
            Optional<SemanticVersion> taggedVersion = entry.getValue().stream()
                    .map(GitVersionResolver::parseSemver)
                    .flatMap(Optional::stream)
                    .max(SEMVER_ORDER);
            if (taggedVersion.isEmpty() || !isAncestor(repository, entry.getKey(), head)) {
                continue;
            }

            int distance = Integer.parseInt(git(repository, "rev-list", "--count",
                    entry.getKey() + ".." + head).trim());
            TaggedSemanticVersion candidate = new TaggedSemanticVersion(taggedVersion.get(), distance);
            if (nearest == null
                    || candidate.distance() < nearest.distance()
                    || (candidate.distance() == nearest.distance()
                        && SEMVER_ORDER.compare(candidate.version(), nearest.version()) > 0)) {
                nearest = candidate;
            }
        }
        return Optional.ofNullable(nearest).map(TaggedSemanticVersion::version);
    }

    private static boolean isAncestor(Path repository, String ancestor, String descendant) {
        return executeGit(repository, "merge-base", "--is-ancestor", ancestor, descendant).exitCode() == 0;
    }

    private static Map<String, List<String>> tagsByCommit(Path repository) {
        Map<String, List<String>> result = new HashMap<>();
        String refs = git(repository,
                "for-each-ref",
                "--format=%(refname:strip=2)%09%(objectname)%09%(*objectname)",
                "refs/tags");

        for (String line : refs.lines().toList()) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3 || fields[0].isBlank()) {
                continue;
            }
            String targetCommit = fields[2].isBlank() ? fields[1] : fields[2];
            result.computeIfAbsent(targetCommit, ignored -> new ArrayList<>()).add(fields[0]);
        }
        return result;
    }

    private static Optional<String> releaseVersion(String tag) {
        Matcher rMatcher = R_TAG.matcher(tag);
        if (rMatcher.matches()) {
            return Optional.of(tag);
        }
        return parseSemver(tag).map(SemanticVersion::toString);
    }

    private static Optional<SemanticVersion> parseSemver(String tag) {
        Matcher matcher = SEMVER_TAG.matcher(tag);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new SemanticVersion(
                new BigInteger(matcher.group(1)),
                new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3))));
    }

    private static ReleaseTag parseRTag(String tag) {
        Matcher matcher = R_TAG.matcher(tag);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not an R release tag: " + tag);
        }
        return new ReleaseTag(
                new BigInteger(matcher.group(1)),
                new BigInteger(matcher.group(2)),
                matcher.group(3) == null ? BigInteger.ZERO : new BigInteger(matcher.group(3)));
    }

    private static String explicitTag(Map<String, String> environment) {
        for (String key : List.of("GITHUB_REF", "GITEA_REF")) {
            String ref = environment.get(key);
            if (ref != null && ref.startsWith("refs/tags/")) {
                return ref.substring("refs/tags/".length());
            }
        }
        return null;
    }

    private static String branchName(Path repository, Map<String, String> environment, String head) {
        for (String key : List.of("GITHUB_HEAD_REF", "GITEA_HEAD_REF")) {
            String name = environment.get(key);
            if (name != null && !name.isBlank() && !name.startsWith("refs/tags/")) {
                return stripBranchPrefix(name.trim());
            }
        }

        for (String key : List.of("GITHUB_REF", "GITEA_REF")) {
            String ref = environment.get(key);
            if (ref != null && ref.startsWith("refs/heads/")) {
                return ref.substring("refs/heads/".length());
            }
        }

        boolean tagRef = List.of("GITHUB_REF", "GITEA_REF").stream()
                .map(environment::get)
                .anyMatch(ref -> ref != null && ref.startsWith("refs/tags/"));
        if (!tagRef) {
            for (String key : List.of("GITHUB_REF_NAME", "GITEA_REF_NAME")) {
                String name = environment.get(key);
                if (name != null && !name.isBlank() && !name.startsWith("refs/tags/")) {
                    return stripBranchPrefix(name.trim());
                }
            }
        }

        String currentBranch = gitOptional(repository, "symbolic-ref", "--quiet", "--short", "HEAD");
        if (!currentBranch.isBlank()) {
            return currentBranch.trim();
        }
        return "detached-" + head.substring(0, Math.min(8, head.length()));
    }

    private static String stripBranchPrefix(String branch) {
        return branch.startsWith("refs/heads/") ? branch.substring("refs/heads/".length()) : branch;
    }

    private static String sanitizeBranch(String branch) {
        String sanitized = branch.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        if (sanitized.isBlank()) {
            throw new GradleException("Cannot derive a version from the current Git branch name: " + branch);
        }
        return sanitized;
    }

    private static String git(Path repository, String... arguments) {
        CommandResult result = executeGit(repository, arguments);
        if (result.exitCode() != 0) {
            throw new GradleException("Git versioning command failed: git " + String.join(" ", arguments)
                    + System.lineSeparator() + result.output());
        }
        return result.output();
    }

    private static String gitOptional(Path repository, String... arguments) {
        CommandResult result = executeGit(repository, arguments);
        return result.exitCode() == 0 ? result.output() : "";
    }

    private static CommandResult executeGit(Path repository, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repository.toFile())
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, new String(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GradleException("Unable to run Git while resolving the project version.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while resolving the project version from Git.", e);
        }
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record TaggedSemanticVersion(SemanticVersion version, int distance) {
    }

    private record SemanticVersion(BigInteger major, BigInteger minor, BigInteger patch) {
        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    private record ReleaseTag(BigInteger year, BigInteger date, BigInteger sequence)
            implements Comparable<ReleaseTag> {
        @Override
        public int compareTo(ReleaseTag other) {
            return R_TAG_ORDER.compare(this, other);
        }
    }
}
