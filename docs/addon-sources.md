# Where WoW addons come from

A one-page map of the addon sites, the apps that install from them, and the money and API access
that hold it together, as of November 2026. It explains why wowfam installs from GitHub releases
and nothing else, and what the other options would cost. Sources are linked inline and listed at
the end.

## The four sources

Most addon authors write their code once and publish the same zip to several places. The
[BigWigs packager](https://github.com/BigWigsMods/packager), run from GitHub Actions, builds the
zips and "can also upload them to CurseForge, WoWInterface, Wago, and GitHub (as a release)". It
also attaches a `release.json` saying which zip is for which game, which is what wowfam reads.

```mermaid
flowchart LR
  author[Addon author<br/>git repository] --> packager[BigWigs packager<br/>GitHub Actions]
  packager --> cf[CurseForge<br/>owned by Overwolf]
  packager --> wago[Wago Addons<br/>run by Method]
  packager --> wowi[WoWInterface<br/>MMOUI]
  packager --> gh[GitHub releases<br/>zips + release.json]
```

| Source | Who runs it | How a third-party app gets addons from it |
|---|---|---|
| [CurseForge](https://www.curseforge.com/wow) | Overwolf | The CurseForge API, only with an API key Overwolf approves; each author can also block third-party apps for a project |
| [Wago Addons](https://addons.wago.io) | Method | The Wago API, through an agreement: WowUp has one, and it requires ads |
| [WoWInterface](https://www.wowinterface.com) | MMOUI | No public API; WowUp reads the undocumented one behind Minion (`api.mmoui.com`) |
| GitHub releases | the author | GitHub's public REST API; anyone can read it |

## The apps

Each site has an app of its own. [WowUp](https://wowup.io) is the open-source app that tries to
install from all of them, and [instawow](https://github.com/layday/instawow) is a command-line
manager that can too, if you bring your own keys.

- **CurseForge app** (Overwolf): installs from CurseForge. It first ran inside Overwolf; a
  standalone build followed in 2022, with a
  [Linux alpha](https://gamingonlinux.com/2022/05/curseforge-modding-client-comes-to-linux-in-alpha-only-supports-wow-for-now)
  that May.
- **Wago app**: Wago's own client for its addons and WeakAuras
  ([listing](https://sourceforge.net/app/wagoapp/)).
- **[Minion](https://minion.mmoui.com)** (MMOUI): WoWInterface's updater, around
  [since 2009](https://www.engadget.com/tag/mmoui-minion).
- **WowUp**: open source (GPL-3.0), free, funded by
  [Patreon](https://www.patreon.com/jliddev). It ships as **two editions built from one
  codebase**, because CurseForge and Wago each set conditions the other's edition can't meet.
- **instawow**: open source (GPL-3.0-or-later), a command-line manager in Python. It ships no
  keys: CurseForge and Wago work only with a key the user supplies (see
  [below](#instawow-brings-no-keys)).

```mermaid
flowchart LR
  cfapp[CurseForge app]
  subgraph wowup[WowUp]
    wucf[with CurseForge<br/>on Overwolf's ow-electron]
    wuw[with Wago<br/>on Electron]
  end
  wagoapp[Wago app]
  minion[Minion]
  instawow[instawow]
  wowfam[wowfam]
  cfapi[(CurseForge API<br/>key required)]
  wagoapi[(Wago API)]
  mmoui[(api.mmoui.com)]
  ghapi[(GitHub API)]
  cfapp --> cfapi
  wucf -- "Overwolf's key,<br/>CurseForge ads" --> cfapi
  wuw -- "Wago ads, or<br/>a Wago access key" --> wagoapi
  wagoapp --> wagoapi
  minion --> mmoui
  wowup --> mmoui
  wowup --> ghapi
  instawow -- "the user's<br/>own key" --> cfapi
  instawow -- "the user's<br/>Wago access key" --> wagoapi
  instawow --> mmoui
  instawow --> ghapi
  wowfam --> ghapi
```

### WowUp's two editions

WowUp's [README](https://github.com/WowUp/WowUp#winget) offers "WowUp With Wago"
(`winget install wowup.wowup`) and "Wowup with CurseForge" (`winget install wowup.cf`), and
[wowup.io](https://wowup.io) asks you to "choose your edition". The code shows the split:

- The Wago edition's build
  ([`environment.prod.ts`](https://github.com/WowUp/WowUp/blob/master/wowup-electron/src/environments/environment.prod.ts))
  turns Wago on and CurseForge off.
- The CurseForge edition's build
  ([`environment.prod.ow.ts`](https://github.com/WowUp/WowUp/blob/master/wowup-electron/src/environments/environment.prod.ow.ts))
  turns CurseForge on with an API key injected at build time, turns Wago off, and runs on
  Overwolf's Electron fork, `@overwolf/ow-electron`.
- Both read WoWInterface, GitHub and TukUI.

Both editions show ads, and both say why in the app
([`en.json`](https://github.com/WowUp/WowUp/blob/master/wowup-electron/src/assets/i18n/en.json)):
"In order to use wago.io as an addon provider and help support authors for their hard work on your
favorite addons we are required to show this advertisement", and the same sentence for CurseForge.
The Wago edition hides its ads for anyone with a Wago access key, which comes with
[Wago's Patreon](https://addons.wago.io/patreon).

### instawow brings no keys

instawow's
[README](https://github.com/layday/instawow/blob/4d0d5dfc9de47046f4c6600043d431fde900a76b/README.rst#curseforge)
calls the keyed CurseForge API "unworkable for add-on managers except through a proxy service,
which the author of this particular add-on manager cannot afford". So instead of shipping a key, it
leaves the key to the user. The code (at commit `4d0d5df`, November 2026) shows how:

- The CurseForge source
  ([`cfcore.py`](https://github.com/layday/instawow/blob/4d0d5dfc9de47046f4c6600043d431fde900a76b/src/instawow/_sources/cfcore.py))
  sends the user's own key from `access_tokens.cfcore` as `x-api-key`, and fails without one. The
  only other way is to set `INSTAWOW_CF_API_URL` to a proxy, which then needs no key; instawow
  doesn't provide a proxy.
- The Wago source
  ([`wago_addons.py`](https://github.com/layday/instawow/blob/4d0d5dfc9de47046f4c6600043d431fde900a76b/src/instawow/_sources/wago_addons.py))
  requires a token in `access_tokens.wago_addons`, in practice a Wago access key. It has no
  ad-supported path.
- The WoWInterface source
  ([`wowi.py`](https://github.com/layday/instawow/blob/4d0d5dfc9de47046f4c6600043d431fde900a76b/src/instawow/_sources/wowi.py))
  reads the same undocumented `api.mmoui.com` endpoints as WowUp, without a key.
- GitHub and TukUI use their public APIs; a GitHub token is optional and only raises the rate
  limit.

## How it got this way

```mermaid
timeline
  2020 : Twitch hands CurseForge addon management to Overwolf
  2021 : Overwolf announces an API roadmap restricting third-party managers
       : Ajour and CurseBreaker stop
  2022 : Overwolf denies WowUp a CurseForge API key; WowUp partners with Wago
       : The official CurseForge API goes live, keys by approval
       : WowUp launches a second app with CurseForge downloads
```

- **December 2020**: addon management moved from the Twitch app to Overwolf's CurseForge app
  ([Blizzard forums](https://eu.forums.blizzard.com/en/wow/t/%F0%9F%90%BA-overwolf-addon-management-changeover-now-2nd-december/189111)).
- **November 2021**: Overwolf announced a roadmap to restrict its API to approved third parties
  ([Wowhead](https://www.wowhead.com/news/the-end-of-ajour-overwolf-announces-new-api-roadmap-to-restrict-third-party-324892)).
  The Ajour and CurseBreaker managers stopped
  ([MMO-Champion](https://www.mmo-champion.com/content/10197-Ajour-and-CurseBreaker-Addon-Managers-No-Longer-Maintained)).
- **February 2022**: Overwolf turned down WowUp's application, citing an exclusivity clause, after
  WowUp announced a partnership with Wago
  ([Wowhead](https://www.wowhead.com/news/ads-revenue-and-api-wowup-and-overwolf-split-over-addon-development-325912),
  [Blizzard forums](https://us.forums.blizzard.com/en/wow/t/overwolf-denies-wowup-its-api/1181983)).
  Wago's announcement to authors
  ([Method](https://mailcoach.method.gg/webview/7e52f06b-1a71-4307-aef1-712b3a16a378)) says
  downloads through WowUp are credited to Wago "so that we can compensate developers".
- **2022**: the official CurseForge API went live, with keys by application
  ([Overwolf](https://medium.com/overwolf/the-curseforge-official-api-is-now-live-d314606355c2),
  [MMO-Champion](https://www.mmo-champion.com/content/10587-CurseForge-Official-API-Standalone-Client-Now-Live)).
  Each project got a toggle for third-party distribution, and downloads through third-party apps
  don't count toward the author's rewards
  ([CurseForge support](https://support.curseforge.com/support/solutions/articles/9000207877)).
- **November 2022**: WowUp launched a separate app with CurseForge downloads
  ([Wowhead](https://www.wowhead.com/news=329736/wowup-launches-new-addon-manager-supporting-curseforge-downloads)),
  today's "WowUp with CurseForge".

## Where the money goes

The sites pay authors from ad revenue, so they want downloads to come through something that shows
their ads. That is the condition an addon manager is accepted on.

```mermaid
flowchart LR
  player[Player] -- sees ads in --> app[CurseForge app,<br/>Wago app, or WowUp]
  app -- ad revenue --> host[CurseForge or Wago]
  host -- monthly rewards,<br/>by downloads --> author[Addon author]
```

### Who pays for the rest

That loop leaves out two sources and one app.

- **WoWInterface** is a small site: its admins bought the MMOUI network (WoWInterface, its sister
  sites and Minion) back into their own company, Good Game Mods LLC, in 2016
  ([announcement](https://www.wowinterface.com/forums/showpost.php?p=312607&postcount=1)). It has
  lived on website ads and a premium membership that removed them
  ([2007](https://wowinterface.com/forums/showthread.php?p=47089)); after 2016 a new premium plan
  was "yet to be decided", and nothing newer on its income is public. Third-party apps cost it
  bandwidth and bring nothing back. In 2020 its admin wrote that they were looking into an API for
  them "that is affordable and doesn't bankrupt us with crippling resource expenses", most likely
  by "requiring authentication for downloads… to enforce the server limitations of
  non-subscribers… even through third-party managers"
  ([quoted here](https://wowinterface.com/forums/showpost.php?p=337354&postcount=8)). No such API
  has been announced. In 2022 he noted, matter-of-factly, that WowUp is "using our undocumented
  minion api" ([thread](https://wowinterface.com/forums/showthread.php?p=340620)): tolerated, as
  far as anything public says, not agreed.
- **GitHub** hosts release files for anyone to download as a service it offers on purpose, and
  authors choose to publish there. A download from a release costs nobody an ad view; the only
  limit on tools is the API's rate limit.
- **WowUp** says it is "free because players back it on Patreon"
  ([wowup.io](https://wowup.io); about $262 a month from some 500 patrons in one
  [snapshot](https://www.patreon.com/jliddev/about)). The ads in its editions are served by Wago
  and by Overwolf. Overwolf apps generally get a share of their ad revenue, and the CurseForge
  edition is one, but whether WowUp earns anything from either edition's ads is not public.

So a WowUp user installing from WoWInterface costs WoWInterface bandwidth and pays it nothing,
while the same app's ads pay CurseForge or Wago and their authors.

## What this means for wowfam

wowfam is a command-line tool: it has nowhere to show an ad, and as open source it can't keep an
API key secret. That rules out CurseForge, and Wago's terms as WowUp has them. GitHub releases need
neither, the packager already publishes them for most addons, and its `release.json` says which zip
is for which game, and downloading them uses GitHub the way it means to be used.

WoWInterface is the one other source an ad-free tool could read, but only through the undocumented
API behind Minion, run by a small team worried about exactly that cost. wowfam won't read it
without asking WoWInterface first, and would take authentication if they want it.

## Sources

- WowUp: [wowup.io](https://wowup.io), [GitHub](https://github.com/WowUp/WowUp),
  [ad texts](https://github.com/WowUp/WowUp/blob/master/wowup-electron/src/assets/i18n/en.json),
  [WoWInterface provider](https://github.com/WowUp/WowUp/blob/master/wowup-lib/src/addon-providers/wow-interface-addon-provider.ts)
- instawow (at `4d0d5df`):
  [README on CurseForge](https://github.com/layday/instawow/blob/4d0d5dfc9de47046f4c6600043d431fde900a76b/README.rst#curseforge),
  [CurseForge source](https://github.com/layday/instawow/blob/4d0d5dfc9de47046f4c6600043d431fde900a76b/src/instawow/_sources/cfcore.py),
  [Wago source](https://github.com/layday/instawow/blob/4d0d5dfc9de47046f4c6600043d431fde900a76b/src/instawow/_sources/wago_addons.py),
  [WoWInterface source](https://github.com/layday/instawow/blob/4d0d5dfc9de47046f4c6600043d431fde900a76b/src/instawow/_sources/wowi.py)
- Wowhead:
  [The End of Ajour](https://www.wowhead.com/news/the-end-of-ajour-overwolf-announces-new-api-roadmap-to-restrict-third-party-324892)
  (2021-11-14),
  [WowUp and Overwolf Split](https://www.wowhead.com/news/ads-revenue-and-api-wowup-and-overwolf-split-over-addon-development-325912)
  (2022-02-09),
  [WowUp Launches New Addon Manager Supporting CurseForge Downloads](https://www.wowhead.com/news=329736/wowup-launches-new-addon-manager-supporting-curseforge-downloads)
  (2022-11-05)
- Overwolf:
  [The CurseForge official API is now live](https://medium.com/overwolf/the-curseforge-official-api-is-now-live-d314606355c2),
  [CurseForge support on the third-party toggle](https://support.curseforge.com/support/solutions/articles/9000207877)
- WoWInterface:
  [The future of MMOUI](https://www.wowinterface.com/forums/showpost.php?p=312607&postcount=1)
  (2016), [on third-party managers](https://wowinterface.com/forums/showpost.php?p=337354&postcount=8)
  (2020), [on WowUp's use of the Minion API](https://wowinterface.com/forums/showthread.php?p=340620)
  (2022)
- Wago: [Method's announcement of the WowUp partnership](https://mailcoach.method.gg/webview/7e52f06b-1a71-4307-aef1-712b3a16a378),
  [Wago Patreon](https://addons.wago.io/patreon)
- Packaging: [BigWigs packager](https://github.com/BigWigsMods/packager),
  [Using the BigWigs Packager with GitHub Actions](https://warcraft.wiki.gg/wiki/Using_the_BigWigs_Packager_with_GitHub_Actions)
