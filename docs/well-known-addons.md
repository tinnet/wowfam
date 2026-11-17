# Well-known addons

`wowfam add` takes the name of a well-known addon in place of its repository:

```sh
wowfam add questie
wowfam add spoken
wowfam add spoken-bosses-audio -g classic_era
```

The name stands for a source and the options that pick the right releases from it. What goes into
`wowfam.toml` is that source and those options, exactly as if you had typed them, so the name
plays no part after `add`:

```console
$ wowfam add spoken -n
[games.forever.addons.spoken]
source = "github:rusty-key/spoken-wow"
version = "latest"
tag_prefix = "spoken/"
asset = "Spoken-*.zip"
```

The entry is named after the addon (`spoken`, not `spoken-wow`); `--name` changes that, and any
other option you give (`--asset`, `--tag-prefix`, `--version`, …) wins over the list's. Names
ignore case. A name that isn't in the list is an error that suggests close ones; for any other
addon give `owner/repo` or its GitHub URL.

| Name                     | Source                                 | Options                                                                   |
|--------------------------|----------------------------------------|---------------------------------------------------------------------------|
| `better-vendor-price`    | `github:mooreatv/BetterVendorPrice`    |                                                                           |
| `bigwigs`                | `github:BigWigsMods/BigWigs`           |                                                                           |
| `dbm`                    | `github:DeadlyBossMods/DeadlyBossMods` |                                                                           |
| `polestar`               | `github:fitzforge/polestar`            | `asset = "Polestar-*.zip"`                                                |
| `polestar-recorder`      | `github:fitzforge/polestar`            | `asset = "Polestar_Recorder-*.zip"`                                       |
| `questie`                | `github:Questie/Questie`               | `tag_prefix = "bundle/"`                                                  |
| `spoken`                 | `github:rusty-key/spoken-wow`          | `tag_prefix = "spoken/"`, `asset = "Spoken-*.zip"`                        |
| `spoken-bosses-audio`    | `github:rusty-key/spoken-wow`          | `tag_prefix = "bosses-audio/"`, `asset = "SpokenBossesAudio-*.zip"`       |
| `spoken-readables-audio` | `github:rusty-key/spoken-wow`          | `tag_prefix = "readables-audio/"`, `asset = "SpokenReadablesAudio-*.zip"` |
| `weakauras`              | `github:WeakAuras/WeakAuras2`          |                                                                           |

## Adding one to the list

The list is `Registry.ENTRIES` in
[`config/Registry.java`](../src/main/java/io/github/tinnet/wowfam/config/Registry.java), kept in
alphabetical order. An addon belongs there if it publishes its zips as GitHub releases.

- The name is lower case, letters, digits and `-`: what players call the addon, not the
  repository.
- Give only the options `add` can't work out or might work out wrong: a `tag_prefix` when the
  repository releases several things, an `asset` when a release has several zips. Addons whose
  releases carry a `release.json` need neither.
- Two entries from one repository must differ in their options.
- Add a row to the table above, and run `just network-test`: it checks that every entry still
  finds a release with one zip on GitHub.

Because `add` writes out the source, changing an entry later only affects new `add`s; existing
`wowfam.toml` files keep what they were given.
