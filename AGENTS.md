# AGENTS.md

Guidance for coding agents (and humans) working in this repository. The [README](README.md)
is for users: what wowfam does and how to configure it. [`docs/design.md`](docs/design.md)
explains the internals and package layout, [`docs/development.md`](docs/development.md) the
build, tests and releases. This file covers how to work on it. Keep the README user-facing:
internals and contributor notes go in `docs/`.

## Setup and commands

Tools are pinned in `mise.toml`; tasks live in the `justfile`. There is no Gradle wrapper — mise
provides `gradle`.

```sh
just setup      # mise install + lefthook install
just test       # unit and property tests
just ci         # what CI runs: secret scan, format check, tests
just fmt        # google-java-format via Spotless, plus `just --fmt`
just native     # native binary in build/native/nativeCompile/wowfam
just run --help # run on the JVM
```

Run `just ci` before declaring work done. `just network-test` hits the real GitHub API; run it
only when touching the GitHub backend.

## Code

- Java 25. Every compile runs Error Prone, Picnic's error-prone-support and NullAway, and any
  finding fails the build. Fix the cause; don't suppress a check without a comment saying why.
  `just ep-fix` applies the suggested fixes.
- Every package is `@NullMarked` (`package-info.java`): anything that may be null says
  `@Nullable`.
- Formatting is google-java-format; never hand-format, run `just fmt` (the pre-commit hook does).
- New code that the native image reaches by reflection or resources needs native-image
  configuration; check with `just native` and a run of the binary, not only the JVM tests.
- Tests use JUnit 6 and AssertJ; properties use Hegel (`@HegelTest`). Tests go in the same commit
  as the change they cover.

## Commits

[Conventional Commits](https://www.conventionalcommits.org): `<type>[(scope)][!]: <description>`.
The commit-msg hook enforces it.

| Type       | For                                                            |
|------------|----------------------------------------------------------------|
| `feat`     | new user-visible behaviour                                     |
| `fix`      | a bug fix                                                      |
| `perf`     | faster or smaller, behaviour unchanged                         |
| `refactor` | code change with no behaviour change                           |
| `test`     | tests only                                                     |
| `docs`     | documentation only                                             |
| `build`    | Gradle, dependencies, native-image, mise tool versions         |
| `ci`       | GitHub Actions workflows                                       |
| `chore`    | other repo tooling: git hooks, justfile, editor and lint setup |

Scopes are optional; use a package or area (`cli`, `install`, `github`, `native`, `hooks`).
Description in lower case, imperative, no trailing period. The body says why, wrapped at 72.
`!` (or a `BREAKING CHANGE:` footer) marks a change to the CLI or `wowfam.toml` that breaks
existing setups.

Every commit must be signed; the pre-push hook rejects unsigned ones. Keep commits to one
logical change. Don't commit build output, local `wowfam.lock` files or anything with
credentials (`GITHUB_TOKEN`); the secret scan runs on every commit.

## Releases

CalVer, `vYYYY.MM.DD` (`.N` for another release that day). `just release` tags and pushes from a
clean `main`; `.github/workflows/release.yml` builds and publishes. Don't create release tags any
other way, and never move or delete a published tag.
