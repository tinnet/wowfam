# Design

How wowfam works inside. The [README](../README.md) describes what it does for users;
[`development.md`](development.md) covers building and testing.

## Packages

| Package       | What                                                                                  |
|---------------|---------------------------------------------------------------------------------------|
| `cli`         | picocli commands (`init`, `add`, `remove`, `install`, `update`, `list`, `outdated`, `registry`), output tables, live progress |
| `config`      | `wowfam.toml` model, loader and text editor; finding WoW installs; game detection; well-known addons |
| `backend`     | the `Backend` interface, release selection, HTTP; `backend.github` is GitHub releases |
| `install`     | download → verify → unpack → swap folders; zip-slip-safe extraction; `.toc` parsing; hooks |
| `lock`        | `wowfam.lock`                                                                         |
| `util`        | versions (mise-style matching), globs, checksums, dependency ordering                 |
| `nativeimage` | GraalVM substitutions                                                                 |

## Backends

A source is `<backend>:<reference>`, as in mise. A backend implements `Backend`:

- **`releases(spec)`** lists every release it can offer for the addon, each with the asset to
  install, newest first. Choosing among them is not its job.
- **`resolve(spec, release)`** (optional) settles a release once it is the one picked, for
  backends that need another request to know its asset. GitHub uses it to read `release.json`
  for one release instead of for every release it lists.
- **`discover(spec)`** (optional) fills in the options a new addon needs, for `wowfam add`.
- **`download(asset, target)`** fetches an asset.

Register a new backend in `WowfamCommand.defaultBackends`.

`ReleaseSelector` picks a release: the locked one if it still satisfies `version` (for
`install`), otherwise the newest that does and that the backend can resolve. A release that
resolves to nothing (no zip for this game) is passed over for the next newest.

For a game, the GitHub backend leaves out releases without a `release.json` that are older than the
repository's newest release with one: from before the addon used the packager, they can't say which
zip is for which game. Otherwise an addon that never supported the game would fall back to a
release from years ago (WeakAuras 3.3.0 for Forever) and install, or fail on, whatever zips it
had. Releases without one that are newer (an addon that stopped using the packager) are still
picked by `asset`.

The GitHub backend keeps the release list of each repository for the run, so several addons from
one repository cost one listing. A `release.json` is kept in the cache directory under the digest
GitHub publishes for it, so each one is downloaded once, not on every run. An `asset` glob that matches several zips only fails for the
release that gets picked, so an old release that shipped two zips doesn't block newer ones.

## Installing

For each addon, in dependency order (`depends`):

1. Pick a release (above). If the locked release is picked and its folders are all present, the
   addon is up to date and nothing else happens.
2. Download the asset into the cache, unless a cached copy matches the published checksum, and
   verify it.
3. Extract it into a staging folder inside AddOns (same file system, so moves are cheap). The
   extractor refuses entry names that would escape the folder.
4. Refuse folders another addon's lock entry owns. An entry whose addon is no longer in
   `wowfam.toml` doesn't count, so a renamed entry takes its folders over.
5. Run `pre_install` hooks on the staging folder; a failure stops here.
6. Delete folders the previous release had and this one doesn't (also when it came from another
   source); move the new folders into place.
7. Record the release, asset, checksum and folders in `wowfam.lock`, then run `post_install`.

A run without addon names then uninstalls lock entries whose addon is no longer in `wowfam.toml`:
their folders are deleted, except ones another entry has taken over, and the entry is dropped.
`wowfam remove` does the same for the addons it is given, and takes their tables out of
`wowfam.toml` as text (the table, its sub-tables and the comment paragraph right above). It parses
the result and only writes it if every other addon, the settings and the hooks come out unchanged.

The lock is written after every addon, even when one fails, so it always says what is on disk.
Checksums are stored as `<algorithm>:<hex>`; a lock from before that (`sha256 = "<hex>"`) is
rewritten on first read.

Afterwards every installed folder's `.toc` is read, and required dependencies
(`## Dependencies`, `## RequiredDeps`) missing from AddOns are reported as warnings. The `.toc`
files are scanned line by line for those keys alone: some addons keep data in them (QuestieDB's
run to tens of megabytes each).

### Progress

The installer reports to an `install.Progress`: lines to keep (`log`), what an addon is doing now
(`status`, `step`), download bytes (`bytes`, fed by `Backend.download`'s callback) and each
addon's outcome (`finished`). Its default prints every `log` and `status` as a line, which is what
pipes, tests and `TERM=dumb` get, and the report's outcomes are printed at the end.

On an interactive terminal `cli.LiveProgress` takes its place. It draws a status block with JLine's
`Display` (a spinner, addons done, the current addon and its download), redrawn ten times a second
by a daemon thread, and prints each finished addon's line above the block as it happens, so the
scrollback keeps those lines and nothing else. It hides the cursor while it runs and puts it back
on close or on Ctrl-C (a shutdown hook). JLine reaches the terminal through its FFM provider
(`java.lang.foreign`), so the native image needs no JNI library; it is built with
`--enable-native-access=ALL-UNNAMED`, and JLine's jars bring their own native-image configuration.

## `wowfam add`

`add` builds an entry from what the user gave, asks the backend to `discover` the rest, checks
the entry finds and resolves a release, and only then writes it.

A bare word (no `/`, `:` or `.`) was never a source, so it names a well-known addon
(`Registry`, listed in [`well-known-addons.md`](well-known-addons.md)): its source and preset
options are where `add` starts, the user's options go over them, and `discover` fills in the rest.
The resolved entry is written, not the name, so the registry is only consulted by `add`: nothing
else knows it exists, and re-pointing a name to another repository never moves an existing setup.
It is a Java table rather than a bundled TOML file so the native image needs no resource
configuration and a malformed entry fails the build.

The GitHub backend works out options the way one would by reading the release page:

- **`tag_prefix`**: tags are grouped by what comes before their version: everything up to the
  first digit, less a trailing `v` (`spoken/v3.8.7` → `spoken/`, `client-v0.1.7` → `client-`).
  Groups whose releases have no zip are dropped; that leaves out desktop apps released from the
  same repository (Polestar's `client-` releases). If several groups remain, those named like the
  repository are kept: the prefix is part of the repository name (`spoken/` in `spoken-wow`), or a
  zip of the group's latest release is (`Polestar-*.zip` in `polestar`, not
  `polestar-forge-windows-x86_64.zip`). Of those, the most recently released wins (Questie's
  `bundle/` over its older plain tags). Otherwise the user must say.
- **`asset`**: the newest release's zip with its version replaced by `*`, trying the tag's
  version with and without its `v` (`Questie-v12.8.0+v1.0.16.zip` → `Questie-*.zip`). With several
  zips, the one whose name before the version is the repository name wins. The pattern must match
  exactly one asset of that release.
- No `asset` is needed when the release has a `release.json` and the game is known.

The entry is added to `wowfam.toml` as text (`ConfigEditor`), after the game's last table, so
comments and layout survive;
the result is parsed before it is written, and written by atomic rename. If the install fails and
the addon didn't make it into the lock (a failed download, a `pre_install` hook saying no), the
file is restored. If it is in the lock (only `post_install` failed), the entry stays, so the
config and the lock agree.

## Config and games

There is one `wowfam.toml` per user, in the user's config folder (`WowfamCommand.defaultConfigFile`:
`$WOWFAM_CONFIG`, else `$XDG_CONFIG_HOME/wowfam`, `~/.config/wowfam`, or `%APPDATA%\wowfam` on
Windows); the current directory plays no part, since a player has no reason to be in their game
folder. `ConfigLoader` parses it into a `Workspace`: the default game and one `Config` per
`[games.<name>]`, which is what the installer works on (its AddOns folder, cache and hooks, and its
addons). Commands over addons run once per selected game; `add` and `remove` pick one
(`Workspace.one`: `--game`, else `default_game`, else the only game).

Each game's addons are their own tables rather than one list of addons with a `games` key per
entry: a game's section then reads as its AddOns folder, per-game differences (a version pinned for
one game) need no overrides, and `add`, `remove` and the lock all work on one game's tables. The
price is listing an addon twice for two games.

`wowfam.lock` keeps the same split (`[games.<name>.addons.<addon>]`). A `LockFile` is one game's
part; writing it rewrites the file with the other games' entries as they were read.

`init` (`WowInstalls`) looks where the launcher installs: the registry's `InstallPath` and the
usual folders on each drive on Windows, `/Applications` on macOS, and on Linux the `drive_c` of
Wine prefixes: `~/Games/*` and the `prefix:` of Lutris' game YAML, `~/.wine`, Steam's
`compatdata/*/pfx` (native and Flatpak), Bottles (native and Flatpak), Heroic and Faugus. A game
folder is a child of the install named `_<something>_` with a `.flavor.info` or an `Interface`
folder; it is named after the folder (`_classic_era_` → `classic_era`). Paths under the home
folder are written as `~/…`, so the file can live in dotfiles.

A `wowfam.toml` from before (one AddOns folder, top-level `[addons]`) is refused with a pointer to
`init --import`, which moves its addon tables under a game as text (comments kept), checks by
parsing that every addon arrived, and moves its lock entries when the old file installed into the
same AddOns folder.

## Game detection

The Battle.net launcher leaves this in a WoW install:

```
World of Warcraft/.build.info               one row per installed product, with its version
World of Warcraft/_forever_/.flavor.info    the product of this folder, e.g. wowf
World of Warcraft/_forever_/Interface/AddOns
```

Both files are the launcher's BPSV format: a header of `Name!TYPE:size` columns, then rows, all
separated by `|`. `Game.detect` walks up from the game's AddOns folder to the first `.flavor.info`, reads the
product, and takes that product's active row from `.build.info` next to the game folder. The
version's first three numbers give the interface number addons declare in `## Interface`:
`major * 10000 + minor * 100 + patch` (1.60.1 → `16001`, 12.1.5 → `120105`).

The loader then gives every addon without a `flavor` or `interface` of its own an `interface`
option. The option isn't written to `wowfam.toml`; it is derived on every load, so the file stays
correct when the game updates. `release.json` entries match it on major and minor version
(`interface / 100`), so 11509 still takes a zip built for 11508. When no entry does (the game was
patched to 11.2 and the addon still ships 11.1), the entry for the closest earlier minor version
of the same major wins; a zip for a later version or another major is never taken. If even that
finds nothing and the addon is installed, `install` and `update` keep it rather than fail.

Product codes (`wow`, `wow_classic_era`, `wowf`, …) are deliberately not mapped to the packager's
flavor names: the mapping changes as Blizzard reuses products for new expansions, and the interface
number already carries the information.
