# Changelog

What changed in each wowfam release. Versions are dates (CalVer).

## 2026.11.23.1

### Features

- **cli**: Leave addons linked into AddOns alone ([bc63c99](https://github.com/tinnet/wowfam/commit/bc63c997e97d8ff3947250fcbbfc8de17498e1d3))

### Fixes

- **cli**: Print wowfam external -n's entries, and name a lone folder after itself ([f24c99c](https://github.com/tinnet/wowfam/commit/f24c99c92781effbe90757cc81a27a1819f8caaa))

## 2026.11.23

### Features

- **cli**: Record addons another manager keeps with wowfam external ([0359da0](https://github.com/tinnet/wowfam/commit/0359da0ea5133b4c056e8464e6084164b501cadc))

### Fixes

- Keep wowfam.lock and wowfam.toml readable when a value holds a byte order mark ([e45b7f3](https://github.com/tinnet/wowfam/commit/e45b7f358400b2839d642952a73e96e67a6b8966))
- **cli**: Never delete a folder an external entry stands for ([ede3b39](https://github.com/tinnet/wowfam/commit/ede3b39ffe143e8e205443099b9e30c44f7ad9aa))

### Documentation

- Add instawow and how it handles CurseForge and Wago keys ([f0b8abf](https://github.com/tinnet/wowfam/commit/f0b8abf99fc0ff17494694566589dc2da363d7f9))

## 2026.11.18

### Features

- **cli**: Show install progress in place, with each download, in a terminal ([ef511fa](https://github.com/tinnet/wowfam/commit/ef511faf7e1606ec3bed9461b433fa7071fdbef9))

### Performance

- **install**: Stop reading all of QuestieDB's database after every run ([60b8cac](https://github.com/tinnet/wowfam/commit/60b8cac677c1298e48cd020a1b222538890d0590))

### Documentation

- Explain where WoW addons come from, and answer why wowfam is GitHub only ([d4eeb94](https://github.com/tinnet/wowfam/commit/d4eeb945df198c359def12e8d6b196338349686d))
- Say who pays for WoWInterface, GitHub and WowUp downloads ([05d139e](https://github.com/tinnet/wowfam/commit/05d139ee9c9046da1ff6d5a2f490da6044b92a36))

## 2026.11.17

### Features

- **cli**: Add well-known addons by name, e.g. wowfam add questie ([4b0d3f0](https://github.com/tinnet/wowfam/commit/4b0d3f08783e9fef7a339e0a6f7f27e4ef27886d))
- **cli**: Add wowfam registry to list the well-known addons ([848922d](https://github.com/tinnet/wowfam/commit/848922d9d283805c01483933ca1e9a3c341985af))
- **cli**: Add whatstraining and foreverplusplus to the well-known addons ([dfd032c](https://github.com/tinnet/wowfam/commit/dfd032cca69f25b8aab73e4968043f83b0d13918))

### Fixes

- **cli**: Stop --help printing picocli format warnings ([62b1a62](https://github.com/tinnet/wowfam/commit/62b1a62225b4fa63d3430b1f6f5ce933a5d9115e))
- **cli**: Let your own --tag-prefix or --asset replace a well-known addon's ([cf608ee](https://github.com/tinnet/wowfam/commit/cf608eecd191f9fad164be350acffe799ae28d9e))
- **github**: Don't fall back to releases from before an addon shipped release.json ([4c8426a](https://github.com/tinnet/wowfam/commit/4c8426a38e6555703944b83c37a770b87ad112d2))

## 2026.11.14

### Features

- **cli**: Add wowfam remove, and uninstall addons taken out of wowfam.toml ([89c212b](https://github.com/tinnet/wowfam/commit/89c212b565f0b68df527745b2244a4476bdcac37))
- **cli**: **breaking:** One global config with all your games, and wowfam init to find them ([0d54e96](https://github.com/tinnet/wowfam/commit/0d54e96881b6be15cd1451256767a90734824a10))

### Fixes

- **install**: Report a version no release has instead of keeping the installed one ([333a6d0](https://github.com/tinnet/wowfam/commit/333a6d030e847fdc20b5643a069ed38efbd11bf1))
- **install**: Reinstall when the zip for the locked release changes ([d92d3dc](https://github.com/tinnet/wowfam/commit/d92d3dca79001aa6905e07b0e1c24d29c2b5501e))
- **config**: Reject addon names that aren't plain folder names ([2eb58d1](https://github.com/tinnet/wowfam/commit/2eb58d16b9a93a0542d6eaa4eeae5be1c3c8bf7f))
- **install**: Read .toc files that aren't UTF-8 or start with a BOM ([f712d36](https://github.com/tinnet/wowfam/commit/f712d367a1830108d6831b9ead6773cc86aba01a))
- **cli**: Print an error, not a stack trace, for a bad lock or a non-GitHub answer ([26e6b30](https://github.com/tinnet/wowfam/commit/26e6b30dfaaf90d898c30e0b4a01080f5a539367))
- **github**: Download assets through the API so private repositories work ([07afa86](https://github.com/tinnet/wowfam/commit/07afa86612081bfaa9d75e36fde6e5a31629ef86))
- **install**: Remove the old folders when an addon moves to another source ([d7afa8b](https://github.com/tinnet/wowfam/commit/d7afa8b5e4a94a727f4601107e65aeaa72c0c397))

### Documentation

- Say game detection follows addons_dir, not where wowfam.toml is ([daca488](https://github.com/tinnet/wowfam/commit/daca488aef9ff6a0bc938e569765ae64eba04ba9))
- License wowfam under MIT ([13e6cd4](https://github.com/tinnet/wowfam/commit/13e6cd4afece9dbc40b57c95364afad38a98e6c5))

## 2026.11.13

### Features

- **cli**: Add an add command that works out the entry from releases ([3604e2f](https://github.com/tinnet/wowfam/commit/3604e2f895aa33675a9f6fb2764b886126deb158))
- **github**: Pick release.json zips for the game the AddOns folder is in ([093138a](https://github.com/tinnet/wowfam/commit/093138a4ccce7013f13c72e1d645fa258ccf3a05))

### Fixes

- **github**: Keep addons installable after a game patch ([70b490b](https://github.com/tinnet/wowfam/commit/70b490b2d66d370292b38bd34369df26e3021cff))
- **cli**: Make add's duplicate, name and rollback checks reliable ([cc92ae4](https://github.com/tinnet/wowfam/commit/cc92ae41dd017a64e58c7b5fcacfe04387447be3))
- **config**: Keep a symlinked wowfam.toml a symlink when add writes it ([9a7e85e](https://github.com/tinnet/wowfam/commit/9a7e85e741f193ee7f3c74ba55e62026780b5449))
- **cli**: See an addon added in a game folder as a duplicate ([c3da4ae](https://github.com/tinnet/wowfam/commit/c3da4aeb25eaa77fa6df1f954d47e94913092dfc))

### Performance

- **github**: Download each release.json once ([69df54c](https://github.com/tinnet/wowfam/commit/69df54c07db711cfdd40980b2565f250fd127994))

### Documentation

- Make the README user-facing and move internals to docs/ ([e0148b2](https://github.com/tinnet/wowfam/commit/e0148b23775348ba1e17afa535b3648e0291f24e))
- Say entries added in a game folder rely on game detection ([7c50719](https://github.com/tinnet/wowfam/commit/7c5071910bf1e256d51fbd7352129b923e30e6ab))

## 2026.11.12

### Features

- **install**: Run pre_install and post_install hooks around each addon ([cd5dd12](https://github.com/tinnet/wowfam/commit/cd5dd12b90a67a60b08573b0295d5c0c2e71eda2))
- **lock**: Record the checksum's algorithm in wowfam.lock ([90b0c9d](https://github.com/tinnet/wowfam/commit/90b0c9dc1e801aaf1b6087443db7fae9dd1b7e7a))
- **github**: Pick the zip for a game flavor from release.json ([9f785cb](https://github.com/tinnet/wowfam/commit/9f785cb93d6810d833529db49ba1a18483efd9c1))

### Fixes

- **cli**: Size list and outdated columns to their contents ([6612e62](https://github.com/tinnet/wowfam/commit/6612e6274926c700d108ce2c708289d76b54155d))
- **github**: Only fail on an ambiguous asset pattern for the release picked ([afbfe52](https://github.com/tinnet/wowfam/commit/afbfe529bacad315cfd8f611b7c02aa8fb23b8c4))

## 2026.11.10

### Features

- Scaffold wowfam, a CLI that installs and updates WoW addons from wowfam.toml ([f1fd567](https://github.com/tinnet/wowfam/commit/f1fd56750bec6b82a559e89a3316486b3043dfac))

### Documentation

- Reword the README's note on where Gradle comes from ([6feada4](https://github.com/tinnet/wowfam/commit/6feada4bb380d8a7ca8c5a105c2c4eda14d01bc2))
- Add AGENTS.md and enforce conventional commits ([762fef4](https://github.com/tinnet/wowfam/commit/762fef4357ce53322ec41fce70049f33669a6d06))


