# wowfam

Installs and updates World of Warcraft addons from a `wowfam.toml`, the way
[mise](https://mise.jdx.dev) does for tools. Each addon names a **backend** and a reference;
the first backend is **GitHub releases**. "Installing" means downloading the release zip and
unpacking its addon folders into your `Interface/AddOns` directory.

Built with Java 25 and compiled to a single native binary with GraalVM Native Image.

## Install

Releases ship native binaries for Linux x86_64, macOS arm64 and Windows x86_64. With
[mise](https://mise.jdx.dev)'s GitHub backend:

```sh
mise use -g github:tinnet/wowfam
```

While the repository is private, mise needs a token that can read it (`GITHUB_TOKEN`).

## Usage

```sh
wowfam install            # install everything in ./wowfam.toml (keeps versions pinned in wowfam.lock)
wowfam install spoken     # one addon, plus whatever it `depends` on
wowfam update             # move everything to the newest matching release
wowfam outdated           # what update would change
wowfam list               # what is configured and what is installed
wowfam -C path/to/wowfam.toml --addons-dir /tmp/AddOns install
```

Set `GITHUB_TOKEN` (or `GH_TOKEN`) to raise GitHub's API rate limit or reach private repositories.
`HTTPS_PROXY` / `NO_PROXY` are honoured. The native binary trusts the CA certificates of the JDK it
was built with; behind a TLS-intercepting proxy, point it at another trust store with
`wowfam -Djavax.net.ssl.trustStore=/path/to/cacerts ...`.

## wowfam.toml

```toml
[settings]
addons_dir = "~/Games/World of Warcraft/_classic_era_/Interface/AddOns"  # default: ./AddOns
# cache_dir = "~/.cache/wowfam"                                          # downloaded zips

[addons.spoken]
source = "github:rusty-key/spoken-wow"   # <backend>:<reference>
version = "latest"                       # "latest", exact "3.8.5", or a prefix like "3" / "3.8"
tag_prefix = "spoken/"                   # github: only tags starting with this (monorepos)
asset = "Spoken-*.zip"                   # github: glob for the release asset (default "*.zip")
# prerelease = true                      # github: consider prereleases

[addons.spoken-bosses-audio]
source = "github:rusty-key/spoken-wow"
tag_prefix = "bosses-audio/"
depends = ["spoken"]                     # installed first, and pulled in by `install spoken-bosses-audio`
```

The table name (`spoken`) is only a handle; a release zip may contain several addon folders and
all of them are installed. [`examples/spoken/wowfam.toml`](examples/spoken/wowfam.toml) is the
proof-of-concept target: Spoken publishes its player addon (six folders in one zip) and its sound
packs from one repository under prefixed tags.

### Hooks

Shell commands to run around each addon's installation (`sh -c`, or `cmd /c` on Windows), from the
directory holding `wowfam.toml`. Each is a command or a list of commands run in order. The
`[hooks]` table applies to every addon; an addon's own `hooks` run after it.

```toml
[hooks]
pre_install = "./review-addon.sh"        # before the folders are copied into the AddOns folder
post_install = ["echo done", "notify-send wowfam \"$WOWFAM_ADDON $WOWFAM_VERSION\""]

[addons.spoken]
source = "github:rusty-key/spoken-wow"
hooks = { post_install = "./reload-ui-reminder.sh" }
```

- **`pre_install`** runs once the release is unpacked into a staging folder and before anything in
  the AddOns folder changes. If it exits non-zero, the addon is not installed and the run fails.
  It is the place to inspect an addon before it reaches the game.
- **`post_install`** runs once the folders are in place and recorded in `wowfam.lock`. If it
  fails, the addon stays installed and the run fails.

Hooks run only when an addon is actually installed or changed, not when it is up to date.
`wowfam install --no-hooks` (or `update --no-hooks`) skips them. They see these variables:

| Variable                  | Value                                                                  |
|---------------------------|------------------------------------------------------------------------|
| `WOWFAM_HOOK`             | `pre_install` or `post_install`                                        |
| `WOWFAM_ADDON`            | the table name, e.g. `spoken`                                          |
| `WOWFAM_SOURCE`           | e.g. `github:rusty-key/spoken-wow`                                     |
| `WOWFAM_VERSION`, `WOWFAM_TAG` | the release being installed                                       |
| `WOWFAM_PREVIOUS_VERSION` | the version it replaces, or empty                                      |
| `WOWFAM_DIR`              | the folder holding the addon folders: staging for `pre_install`, the AddOns folder for `post_install` |
| `WOWFAM_FOLDERS`          | the addon folders, one per line                                        |
| `WOWFAM_ADDONS_DIR`       | the AddOns folder                                                      |
| `WOWFAM_ARCHIVE`          | the downloaded zip                                                     |

Hooks run with your permissions; only put commands you trust in `wowfam.toml`.

#### Reviewing addons with Claude

A `pre_install` hook can have [Claude Code](https://claude.com/claude-code) read every addon before
it is installed and refuse the ones that look malicious:

```toml
[hooks]
pre_install = "./review-addon.sh"
```

```sh
#!/bin/sh
# review-addon.sh: let Claude read the unpacked addon; install it only on an explicit OK.
cd "$WOWFAM_DIR" || exit 1
verdict=$(claude -p --allowedTools "Read,Grep,Glob" "You are reviewing the World of Warcraft \
addon $WOWFAM_ADDON $WOWFAM_VERSION ($WOWFAM_SOURCE), unpacked in this directory, before it is \
installed. Read its .toc, Lua and XML files. Look for unwanted behaviour: sending chat, whispers \
or addon messages the user did not ask for, hidden or obfuscated code (loadstring, encoded \
payloads), touching other addons' saved variables, protected-action abuse, or anything that does \
not fit what the addon says it does. Summarise what you found, then end with a line that is \
exactly VERDICT: OK or VERDICT: REJECT.")
printf '%s\n' "$verdict"
printf '%s\n' "$verdict" | tail -n 1 | grep -qx 'VERDICT: OK'
```

The review's output is printed as part of `wowfam install`; a rejected addon leaves the AddOns
folder untouched. Re-run with `--no-hooks` to install it anyway.

### wowfam.lock

Written beside `wowfam.toml`. It records, per addon, the release tag, asset URL, SHA-256 (when
GitHub publishes a digest) and the folders that were unpacked. `install` reuses the locked release
if it still satisfies `version`; `update` re-resolves. On update, folders the old release had and
the new one does not are removed, and an addon may not overwrite a folder another addon owns.

After installing, each folder's `.toc` is read and required dependencies (`## Dependencies`,
`## RequiredDeps`) that are not present in the AddOns folder are reported as warnings.

## Building

Tools are pinned in [`mise.toml`](mise.toml) (GraalVM 25, Gradle, just, lefthook, betterleaks) and
tasks live in the [`justfile`](justfile):

```sh
just setup          # mise install + lefthook install (git hooks)
just test           # unit + property tests (JUnit 6, AssertJ, Hegel)
just network-test   # tests against the real GitHub API
just run --help     # run on the JVM
just native         # -> build/native/nativeCompile/wowfam
just poc            # install the Spoken example into examples/spoken/AddOns
just fmt            # google-java-format 2.0 via Spotless, plus `just --fmt` (`just fmt-check` to verify)
just ep-fix         # apply Error Prone's suggested fixes in place
just secrets        # betterleaks over the tree and the git history
just release        # tag today's CalVer release (vYYYY.MM.DD) and push it
```

Versions are CalVer, `YYYY.MM.DD` (`YYYY.MM.DD.N` for another release that day); local builds
report `dev`. Pushing a `v20*` tag runs [`release.yml`](.github/workflows/release.yml): it builds
the native binary on each OS, checks that `--version` matches the tag, and publishes the archives
with a `SHA256SUMS` file and generated notes. Once the repository is public it also signs build
provenance, which `gh attestation verify` and mise check.

Every compile runs [Error Prone](https://errorprone.info) with Picnic's
[error-prone-support](https://error-prone.picnic.tech) checks and Refaster rules, and
[NullAway](https://github.com/uber/NullAway) in JSpecify mode: each package is `@NullMarked`
(`package-info.java`), so anything that may be null must say `@Nullable`. Findings fail the build.
This happens while `javac` compiles the JVM bytecode; the native image is built from that afterwards.
Error Prone is pinned to the version Picnic's release is built against (2.50.0) — newer ones break
its checks — so bump the two together. Refaster rules that would introduce Guava are switched off.

We rely on gradle being available; either mise installs the Gradle pinned in `mise.toml` or
without mise, any Gradle 9.9 on the `PATH` works, and its toolchain support (with the foojay
resolver) downloads a JDK 25 / GraalVM 25 if none is installed.

Git hooks ([`lefthook.yml`](lefthook.yml)): pre-commit scans staged changes for secrets and
formats staged Java files, commit-msg requires a [Conventional Commit](AGENTS.md#commits), and
pre-push checks that every pushed commit is signed. [`AGENTS.md`](AGENTS.md) has the working
conventions.

Why Java 25: it is the current LTS and the newest JDK that GraalVM Native Image supports — there is
no GraalVM release for JDK 26 or 27.

## Layout

| Package     | What                                                                                  |
|-------------|---------------------------------------------------------------------------------------|
| `cli`       | picocli commands (`install`, `update`, `list`, `outdated`)                            |
| `config`    | `wowfam.toml` model and loader                                                        |
| `backend`   | the `Backend` interface, release selection, HTTP; `backend.github` is GitHub releases |
| `install`   | download → verify → unpack → swap folders; zip-slip-safe extraction; `.toc` parsing   |
| `lock`      | `wowfam.lock`                                                                         |
| `util`      | versions (mise-style matching), globs, dependency ordering                            |
| `nativeimage` | GraalVM substitutions; drops Jackson's unused XML/SQL handlers (−7 MB)              |

Adding a backend means implementing `Backend` (list releases with their asset, download an asset)
and registering it in `WowfamCommand.defaultBackends`.

## Tests

Property-based tests use [Hegel](https://hegel.dev) (`@HegelTest`): version ordering and matching,
dependency ordering over random DAGs, glob matching, lock-file round-trips of arbitrary strings,
and that no zip entry name can escape the AddOns folder. `CliTest` runs the whole CLI against
`FakeGitHub`, an in-process stand-in for the releases API laid out like the Spoken repository.
