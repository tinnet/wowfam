# wowfam dev tasks. `just` lists them.

set windows-shell := ["pwsh", "-NoLogo", "-Command"]

binary := "build/native/nativeCompile/wowfam" + if os_family() == "windows" { ".exe" } else { "" }

[private]
default:
    @{{ just_executable() }} --list --unsorted

# Install the tools from mise.toml and the git hooks
setup:
    mise install
    lefthook install

# Compile and run the unit and property tests
test *args:
    gradle test {{ args }}

# Run the tests that talk to the real GitHub API (set GITHUB_TOKEN to avoid rate limits)
network-test:
    gradle networkTest

# Build the native binary into build/native/nativeCompile
native:
    gradle nativeCompile

# Run wowfam on the JVM, e.g. `just run --help`
run *args:
    gradle -q run --args="{{ args }}"

# Install the Spoken proof of concept into examples/spoken/AddOns with the native binary
poc *args="install": native
    {{ binary }} --config examples/spoken/wowfam.toml {{ args }}

# Format the Java code (google-java-format) and this justfile
fmt:
    gradle spotlessApply
    {{ just_executable() }} --fmt

# Fail if anything is not formatted
fmt-check:
    gradle spotlessCheck
    {{ just_executable() }} --fmt --check

# Apply Error Prone / Picnic / NullAway suggested fixes in place, then reformat
ep-fix:
    gradle compileJava compileTestJava -Perrorprone.patch
    gradle spotlessApply

# Scan the working tree and the git history for secrets
secrets:
    betterleaks dir . --no-banner --redact -v
    betterleaks git . --no-banner --redact -v

# What CI runs
ci: secrets fmt-check test

# Show what the next release's notes would say
changelog:
    git cliff --unreleased --strip header

# Release from main (today's date, or e.g. `just release 2026.11.10.1`): commit CHANGELOG.md, tag, push
[unix]
release version=`date +%Y.%m.%d`:
    git diff --quiet HEAD && git fetch -q origin main && test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" || { echo "release from a clean main that matches origin/main" >&2; exit 1; }
    git cliff --tag "v{{ version }}" --output CHANGELOG.md
    git add CHANGELOG.md
    git diff --cached --quiet || git commit -q -m "chore(release): {{ version }}"
    git push origin HEAD:main
    git tag -s "v{{ version }}" -m "wowfam {{ version }}"
    git push origin "v{{ version }}"

clean:
    gradle clean
