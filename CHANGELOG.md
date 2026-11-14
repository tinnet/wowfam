# Changelog

What changed in each wowfam release. Versions are dates (CalVer).

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


