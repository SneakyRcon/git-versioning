# SneakyRcon Git Versioning for Gradle

This repository provides the `com.sneakyrcon.gradle.version` Gradle plugin. It
sets the root project version from Git tags and the current branch, so local
Gradle builds and GitHub Actions use the same version rules.

## Requirements

The Gradle runtime must use Java 17 or newer, and Git must be available on
`PATH`.

## Version rules

- A current `RYY_MMDD[.N]` tag returns its exact tag text. If several R tags
  point at the commit, a tag-triggered build uses that exact tag; otherwise the
  highest date and suffix are selected.
- A current `vX.Y.Z` or `X.Y.Z` tag returns `X.Y.Z`.
- Otherwise, the plugin finds the nearest reachable SemVer tag, increments
  its patch number, and appends the sanitized branch name and `-SNAPSHOT`.
- R tags on earlier commits do not affect the SemVer base. If no SemVer tag is
  reachable, untagged builds start at `0.1.0`.
- The plugin reads Git only. It does not parse commit messages, create tags, or
  add a Gradle `release` task.

## Applying the plugin

Apply the plugin at the root project. Gradle resolves it from the Plugin Portal
by default:

```groovy
plugins {
  id 'com.sneakyrcon.gradle.version' version '1.0.0'
}
```

## Publishing

The `publishPlugins` task publishes the plugin and its marker to the Gradle
Plugin Portal. Validate the publication metadata without uploading it with:

```sh
./gradlew -PreleaseVersion=1.0.0 publishPlugins --validate-only
```

For a local upload, configure `gradle.publish.key` and
`gradle.publish.secret` in `~/.gradle/gradle.properties`, or set the
`GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` environment variables, then run
`./gradlew -PreleaseVersion=1.0.0 publishPlugins`.

The GitHub Actions workflow publishes to the Gradle Plugin Portal when a
`vX.Y.Z` tag is pushed. Configure the repository secrets
`GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` before creating the release
tag. The Gradle Plugin Portal reviews a plugin's initial publication before it
becomes publicly available.

Checkout should fetch all tags and history. The plugin uses the checked-out
branch locally and GitHub ref environment variables when a runner checks out a
detached commit.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
