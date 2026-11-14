# Development

How to build, test and release wowfam. [`AGENTS.md`](../AGENTS.md) has the working conventions
(commit messages, signing, code rules); [`design.md`](design.md) explains how it works inside.

## Tools

Tools are pinned in [`mise.toml`](../mise.toml): GraalVM 25, Gradle, just, lefthook and
betterleaks. Tasks live in the [`justfile`](../justfile):

```sh
just setup          # mise install + lefthook install (git hooks)
just test           # unit + property tests (JUnit 6, AssertJ, Hegel)
just ci             # what CI runs: secret scan, format check, tests
just network-test   # tests against the real GitHub API
just run --help     # run on the JVM
just native         # -> build/native/nativeCompile/wowfam
just poc            # install the Spoken example into examples/spoken/AddOns
just fmt            # google-java-format via Spotless, plus `just --fmt` (`just fmt-check` to verify)
just ep-fix         # apply Error Prone's suggested fixes in place
just secrets        # betterleaks over the tree and the git history
just changelog      # what the next release's notes would say
just release        # write CHANGELOG.md, tag today's CalVer release (vYYYY.MM.DD), push both
```

There is no Gradle wrapper. mise installs the Gradle pinned in `mise.toml`; without mise, any
Gradle 9.9 on the `PATH` works, and its toolchain support (with the foojay resolver) downloads a
JDK 25 / GraalVM 25 if none is installed.

Why Java 25: it is the current LTS and the newest JDK that GraalVM Native Image supports. There is
no GraalVM release for JDK 26 or 27.

## Static checks

Every compile runs [Error Prone](https://errorprone.info) with Picnic's
[error-prone-support](https://error-prone.picnic.tech) checks and Refaster rules, and
[NullAway](https://github.com/uber/NullAway) in JSpecify mode: each package is `@NullMarked`
(`package-info.java`), so anything that may be null must say `@Nullable`. Findings fail the build.
This happens while `javac` compiles the JVM bytecode; the native image is built from that
afterwards.

Error Prone is pinned to the version Picnic's release is built against (2.50.0); newer ones break
its checks, so bump the two together. Refaster rules that would introduce Guava are switched off.

## Native image

picocli's annotation processor generates the reflection configuration for the commands at compile
time. Anything else the native image reaches by reflection or as a resource would need
configuration in `src/main/resources/META-INF/native-image` (nothing does today); check new code
with `just native` and a run of the binary, not only the JVM tests. `nativeimage/JacksonSubstitutions` drops Jackson's unused XML and SQL
handlers, which saves about 7 MB.

## Tests

- Unit tests use JUnit 6 and AssertJ.
- Property-based tests use [Hegel](https://hegel.dev) (`@HegelTest`): version ordering and
  matching, dependency ordering over random DAGs, glob matching, lock-file and `wowfam.toml`
  round-trips of arbitrary strings, asset-pattern discovery, and that no zip entry name can escape
  the AddOns folder.
- `CliTest` runs the whole CLI against `FakeGitHub`, an in-process stand-in for the releases API
  laid out like the Spoken repository. `WowInstall` lays out a WoW install the way the launcher
  does, for game detection.
- `just network-test` runs `SpokenNetworkTest` against the real GitHub API; run it when touching
  the GitHub backend.

[`examples/spoken/wowfam.toml`](../examples/spoken/wowfam.toml) was the proof-of-concept target:
Spoken publishes its player addon (six folders in one zip) and its sound packs from one repository
under prefixed tags. `just poc` installs it into `examples/spoken/AddOns`.

## Git hooks

[`lefthook.yml`](../lefthook.yml) wires up:

- **pre-commit**: scans staged changes for secrets and formats staged Java files.
- **commit-msg**: requires a [Conventional Commit](../AGENTS.md#commits).
- **pre-push**: checks that every pushed commit is signed.

## GitHub Actions

Every action the workflows use is pinned to a commit in
[`.github/workflows/actions.lock`](../.github/workflows/actions.lock), a GitHub Actions
[workflow lockfile](https://gh.io/actions-lockfile): runs use the recorded commit even if the tag
moves. After adding or changing a `uses:`, run
[`gh actions-lock`](https://github.com/github/gh-actions-lock) (`gh extension install
github/gh-actions-lock`) and commit the lockfile with the workflow; CI's `actions-lock` job fails
otherwise. Dependabot bumps actions weekly, a week after their release, and updates the lockfile
in the same pull request. `setup-gradle` uses `cache-provider: basic`, its MIT-licensed cache;
the default one is proprietary.

## Releases

Versions are CalVer, `YYYY.MM.DD` (`YYYY.MM.DD.N` for another release that day); local builds
report `dev`. `just release`, on a clean `main` that matches `origin/main`, writes
[`CHANGELOG.md`](../CHANGELOG.md) with [git-cliff](https://git-cliff.org), commits it as
`chore(release): <version>`, tags that commit and pushes both.

The changelog and the release notes are built from the commit subjects ([`cliff.toml`](../cliff.toml)):
`feat`, `fix`, `perf` and `docs` commits are listed under their own heading; `refactor`, `test`,
`build`, `ci` and `chore` are left out as internal. So the subject of a user-visible commit is
what users read.

Pushing a `v20*` tag runs [`release.yml`](../.github/workflows/release.yml). It builds the native
binary on Linux, macOS and Windows, checks that `--version` matches the tag, and publishes the
archives (named the way mise's GitHub backend expects) with a `SHA256SUMS` file, and that
release's section of the changelog as its notes. Once the repository is public it also signs build provenance, which
`gh attestation verify` and mise check.

While the repository is private, `mise use github:tinnet/wowfam` needs a `GITHUB_TOKEN` that can
read it.
