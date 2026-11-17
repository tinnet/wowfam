# wowfam

Installs and updates World of Warcraft addons from a `wowfam.toml`, the way
[mise](https://mise.jdx.dev) does for tools. You list the addons you want for each of your games;
wowfam downloads their releases from GitHub, unpacks them into `Interface/AddOns`, and keeps them up
to date. Folders it didn't install are left alone, so it can sit next to addons you manage by hand.

```console
$ wowfam add https://github.com/fitzforge/polestar
added to /home/me/.config/wowfam/wowfam.toml:
[games.forever.addons.polestar]
source = "github:fitzforge/polestar"
version = "latest"
asset = "Polestar-*.zip"
downloading Polestar-0.3.7.2.zip (53.0 MB)
installing polestar 0.3.7.2 from Polestar-0.3.7.2.zip
✓ polestar 0.3.7.2  (Polestar, Polestar_Codex, …)

$ wowfam outdated
ADDON    INSTALLED       LATEST
spoken   3.8.6           3.8.7
questie  12.6.5+v1.0.13  12.8.0+v1.0.16

$ wowfam update
↑ spoken 3.8.6 -> 3.8.7  (Spoken, SpokenContributions, …)
↑ questie 12.6.5+v1.0.13 -> 12.8.0+v1.0.16  (Questie, QuestieDB)
```

## Install

Releases ship a single native binary for Linux x86_64, macOS arm64 and Windows x86_64. With
[mise](https://mise.jdx.dev):

```sh
mise use -g github:tinnet/wowfam
```

Or download the archive for your system from the
[releases page](https://github.com/tinnet/wowfam/releases) and put `wowfam` on your `PATH`.

## Getting started

```console
$ wowfam init
found:
  1  classic_era  ~/Games/battlenet/drive_c/Program Files (x86)/World of Warcraft/_classic_era_
  2  forever      ~/Games/battlenet/drive_c/Program Files (x86)/World of Warcraft/_forever_
add which? [all], numbers such as 1 3, none, or the path of another folder:
default game for add and remove (classic_era, forever) [classic_era]: forever
wrote /home/me/.config/wowfam/wowfam.toml
```

`init` looks for World of Warcraft where the Battle.net launcher puts it: `Program Files` on
Windows, `/Applications` on macOS, and on Linux the Wine prefixes of Lutris, Steam (Proton),
Bottles, Heroic and Faugus. If it finds nothing, it asks for the folder; `wowfam init --dir <folder>`
skips the search. Run it again after installing another game version to add that one.

Then add addons by pasting their GitHub page, from any folder:

```sh
wowfam add https://github.com/Questie/Questie
wowfam add mooreatv/BetterVendorPrice -g classic_era   # to a game other than the default
```

`add` reads the repository's releases, works out which tags and which zip are the addon, writes
the entry to `wowfam.toml`, and installs it. Restart the game (not just `/reload`) to load newly
installed addons.

The config lives in `~/.config/wowfam/wowfam.toml` (`$XDG_CONFIG_HOME/wowfam`; `%APPDATA%\wowfam`
on Windows), with `wowfam.lock` beside it. `$WOWFAM_CONFIG` or `-C FILE` points wowfam at another
one.

**Upgrading from a `wowfam.toml` in the game folder:** `wowfam init` notices it in a game folder
it adds and moves its addons and lock over (`--import FILE` for one elsewhere). Its `[settings]`
and `[hooks]` are not copied. Delete the old files afterwards.

## Commands

```sh
wowfam init               # find your games and write wowfam.toml
wowfam add <source>       # add an addon to wowfam.toml and install it
wowfam install            # install everything in wowfam.toml (keeps the versions in wowfam.lock)
wowfam install questie    # one addon, plus whatever it `depends` on
wowfam outdated           # what update would change
wowfam update             # move everything to the newest matching release
wowfam remove questie     # uninstall it and take it out of wowfam.toml
wowfam list               # what is configured and what is installed
wowfam registry           # the well-known addons add takes by name
```

`install`, `update`, `outdated` and `list` work on every game; `-g GAME` limits them to one. `add`
and `remove` work on `settings.default_game` (or the only game) unless `-g` names another.
`wowfam <command> --help` lists each command's options. Every command also takes `-C FILE` (which
`wowfam.toml` to use) and `--addons-dir DIR` (install that game's addons somewhere else).

In a terminal, `install`, `update` and `add` show their progress in place, with each addon's
download, and leave one line per addon that changed. Piped or redirected output, `TERM=dumb` and
`WOWFAM_PROGRESS=0` get plain lines instead, one per step, for logs and scripts.

### `wowfam add`

The source can be a GitHub URL, `owner/repo`, `github:owner/repo`, or the name of a
[well-known addon](docs/well-known-addons.md) such as `questie`. Most addons need nothing
else. When a repository publishes more than one thing, `add` picks the one named like the
repository: Spoken's addon over its desktop manager, Polestar's addon zip over its optional
recorder. When it can't tell, it says which option to give.

| Option             | Sets                                                                 |
|--------------------|----------------------------------------------------------------------|
| `--name NAME`      | the entry's name (default: the repository name, `better-vendor-price`) |
| `--version V`      | `latest` (default), an exact version, or a prefix such as `3.8`      |
| `--tag-prefix P`   | only releases tagged `P…`, e.g. `spoken/`                            |
| `--asset GLOB`     | which zip of a release to install, e.g. `'Polestar_Recorder-*.zip'`  |
| `--flavor F`       | the game to install for, when AddOns isn't in a WoW install (see below) |
| `--prerelease`     | consider prereleases                                                 |
| `-n`, `--dry-run`  | print the entry without changing anything                            |
| `--no-install`     | only write the entry                                                 |

If an addon you add is already in AddOns (installed by hand), wowfam replaces it with the release
and manages it from then on. If installing fails, the entry is taken out of `wowfam.toml` again.

### `wowfam remove`

`wowfam remove NAME…` (or `rm`) deletes the folders the addon installed and takes its table out
of `wowfam.toml`, keeping the rest of the file as it is. `-n` shows what it would do. It refuses
while another entry `depends` on the addon.

Editing `wowfam.toml` by hand works too: `wowfam install` and `wowfam update` without addon names
uninstall whatever is in a game's part of `wowfam.lock` but no longer in its part of `wowfam.toml`. A renamed entry takes its
folders over, and an entry moved to another source (a fork) drops folders only the old one had.

## wowfam.toml

`init` and `add` write these for you; this is what they mean if you want to edit them. Each game
has a `[games.<name>]` table, and its addons are `[games.<name>.addons.<addon>]` tables. An addon
you want in two games is listed in both.

```toml
[settings]
default_game = "forever"          # the game add and remove use without -g
# cache_dir = "~/.cache/wowfam"   # where downloaded zips are kept

[games.forever]                   # a name of your choosing: letters, digits, - and _
dir = "~/Games/World of Warcraft/_forever_"   # the game folder; ~ is your home
# addons_dir = "/some/AddOns"     # instead of dir: an AddOns folder anywhere

[games.forever.addons.spoken]     # also a name of your choosing
source = "github:rusty-key/spoken-wow"
version = "latest"                # "latest", exact "3.8.5", or a prefix like "3" / "3.8"
tag_prefix = "spoken/"            # only tags starting with this (repositories with several projects)
asset = "Spoken-*.zip"            # which zip of a release (default "*.zip")
# prerelease = true               # consider prereleases
# flavor = "classic_era"          # the game to install for (see Game versions)

[games.forever.addons.spoken-bosses-audio]
source = "github:rusty-key/spoken-wow"
tag_prefix = "bosses-audio/"
depends = ["spoken"]              # installed first, and pulled in by `install spoken-bosses-audio`

[games.retail]
dir = "~/Games/World of Warcraft/_retail_"

[games.retail.addons.spoken]
source = "github:rusty-key/spoken-wow"
tag_prefix = "spoken/"
asset = "Spoken-*.zip"
```

Relative paths are relative to the folder holding `wowfam.toml`. A release zip may hold several
addon folders; all of them are installed and belong to that entry. When an update drops a folder,
wowfam removes it. Two entries of a game can't install the same folder.

After installing, wowfam checks each addon's required dependencies (`## Dependencies` in its
`.toc`) and warns about any that aren't in AddOns.

### Game versions

Many addons ship one zip that works on every game version. Some ship one per game; their releases
carry a `release.json` (written by the [BigWigs packager](https://github.com/BigWigsMods/packager))
that says which zip is for which game.

wowfam knows which client a game folder holds from the files the Battle.net launcher keeps there,
and installs the right zip without any setting. `wowfam list` shows what it found:

```
forever: wowf 1.60.1.70938 (interface 16001), ~/Games/…/_forever_/Interface/AddOns
```

`add` therefore writes no `asset` for such addons. For a game whose AddOns folder is outside a WoW
install (an `addons_dir`, or `--addons-dir`), give them a `flavor`.

After a game patch, an addon that hasn't put out a release for it yet gets the zip for the
closest earlier version of the same expansion. An addon whose newest release no longer supports
your game stays on its last release that did, and one that is installed but has no release for
your game at all (after a new expansion) is left as it is.

For an AddOns folder outside a WoW install, or to override what was detected, name the client per
addon:

```toml
flavor = "classic_era"   # mainline/retail, classic/classic_era, bcc/tbc, wrath, cata, mists, forever, …
# interface = 11508      # or the interface number from the game's addon list
# nolib = true           # take the -nolib zip, without embedded libraries
```

### Hooks

Commands to run around each addon's installation, from the folder holding `wowfam.toml`
(`sh -c`, or `cmd /c` on Windows). Each is a command or a list of commands. The `[hooks]` table
applies to every addon; an addon's own `hooks` run after it.

```toml
[hooks]
pre_install = "./review-addon.sh"
post_install = ["echo done", "notify-send wowfam \"$WOWFAM_ADDON $WOWFAM_VERSION\""]

[games.forever.addons.spoken]
source = "github:rusty-key/spoken-wow"
hooks = { post_install = "./reload-ui-reminder.sh" }
```

- **`pre_install`** runs once the release is unpacked, before anything in AddOns changes. If it
  fails (exits non-zero), the addon is not installed.
- **`post_install`** runs once the addon is in place. If it fails, the addon stays installed but
  wowfam reports the failure.

Hooks run only when an addon is installed or changed, not when it is up to date. `--no-hooks`
skips them. They see these variables:

| Variable                  | Value                                                                  |
|---------------------------|------------------------------------------------------------------------|
| `WOWFAM_HOOK`             | `pre_install` or `post_install`                                        |
| `WOWFAM_GAME`             | the game's name in `wowfam.toml`, e.g. `forever`                       |
| `WOWFAM_ADDON`            | the entry's name, e.g. `spoken`                                        |
| `WOWFAM_SOURCE`           | e.g. `github:rusty-key/spoken-wow`                                     |
| `WOWFAM_VERSION`, `WOWFAM_TAG` | the release being installed                                       |
| `WOWFAM_PREVIOUS_VERSION` | the version it replaces, or empty                                      |
| `WOWFAM_DIR`              | the folder holding the addon's folders: a staging folder for `pre_install`, AddOns for `post_install` |
| `WOWFAM_FOLDERS`          | the addon's folders, one per line                                      |
| `WOWFAM_ADDONS_DIR`       | the AddOns folder                                                      |
| `WOWFAM_ARCHIVE`          | the downloaded zip                                                     |

Hooks run with your permissions; only put commands you trust in `wowfam.toml`.

#### Reviewing addons with Claude

A `pre_install` hook can have [Claude Code](https://claude.com/claude-code) read every addon
before it's installed and refuse the ones that look malicious:

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

The review is printed as part of `wowfam install`; a rejected addon leaves AddOns untouched.
Re-run with `--no-hooks` to install it anyway.

## wowfam.lock

wowfam writes `wowfam.lock` next to `wowfam.toml`. It records, per game, which release of each
addon is installed, the zip's checksum, and the folders it unpacked. `install` keeps those
releases, so a second machine with the same two files gets the same addons; `update` moves to the
newest ones. Keep both in your dotfiles if you like: `init` writes paths under your home as `~/…`.
Downloads are checked against the checksum GitHub publishes.

## GitHub access and proxies

wowfam works without a token, but GitHub allows only 60 unauthenticated API requests an hour. Set
`GITHUB_TOKEN` (or `GH_TOKEN`) to raise that limit, or to install from private repositories:

```sh
export GITHUB_TOKEN=$(gh auth token)
```

`HTTPS_PROXY` and `NO_PROXY` are honoured. Behind a proxy that intercepts TLS, point wowfam at a
trust store that has its certificate: `wowfam -Djavax.net.ssl.trustStore=/path/to/cacerts …`.

## FAQ

Questions we expect, about where wowfam gets addons from. The background, with sources, is in
[Where WoW addons come from](docs/addon-sources.md).

**Where does wowfam download addons from?**
From GitHub releases, and nothing else for now. Most addons are built with the BigWigs packager,
which publishes every release to GitHub alongside CurseForge, Wago and WoWInterface.

**Why not CurseForge?**
CurseForge's API takes a key that Overwolf, which owns CurseForge, approves per app and doesn't let
you share. An open-source tool can't keep one secret, and the apps that have one show CurseForge's
ads in exchange: WowUp's CurseForge edition runs on Overwolf's own Electron to do it.

**Why not Wago?**
Wago pays addon authors from ad revenue, so apps that install from it show Wago's ads. WowUp does,
unless you have a Wago access key. A command-line tool has nowhere to show them.

**What about WoWInterface?**
Not yet, and not without asking. It has no public API: WowUp reads the undocumented one behind
WoWInterface's own Minion app, which costs a small site bandwidth and brings it nothing back. A
wowfam backend would start with WoWInterface's permission, and with authentication if they want it.

**My addon isn't on GitHub. What now?**
Ask its author: if they use the BigWigs packager, a GitHub release is one setting away. Until then,
keep it in AddOns by hand or with another manager; wowfam leaves folders it didn't install alone.

**Can I use wowfam next to WowUp or the CurseForge app?**
Yes, as long as each addon has one manager. wowfam only touches the folders it installed, and
taking over an addon that's already there is `wowfam add`.

**Does wowfam show ads or pay authors?**
No to both. Authors aren't paid for GitHub downloads; if an addon you use has a Patreon or similar,
that's the way to support it.

## Contributing

[`docs/development.md`](docs/development.md) covers building, testing and releasing;
[`docs/design.md`](docs/design.md) explains how wowfam works inside. [`AGENTS.md`](AGENTS.md) has
the working conventions.

## License

[MIT](LICENSE)
