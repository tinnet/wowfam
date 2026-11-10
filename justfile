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

clean:
    gradle clean
