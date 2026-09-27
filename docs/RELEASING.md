# Releasing

The steps to cut an OpenZeekr release. Tool-agnostic, so a human or any AI agent can
follow it. Only the maintainer tags and publishes.

## 1. Preconditions

- The working tree is clean and `master` is up to date.
- `CHANGELOG.md` has an `## [Unreleased]` section describing the changes.
- The version is new and semver-shaped `X.Y.Z` (check `git tag --list 'v*'`).

## 2. Version bump (phone **and** watch)

The phone and watch APKs share one `applicationId` and ship in a single Play release,
so each artifact needs a distinct, increasing `versionCode`. The convention since 0.1.4
is **phone = previous watch + 1**, then **watch = phone + 1**:

| Release | app `versionCode` | wear `versionCode` |
| ------- | ----------------- | ------------------ |
| 0.1.6   | 8                 | 9                  |
| 0.1.7   | 9                 | 10                 |
| 0.1.8   | 11                | 12                 |

- `app/build.gradle.kts`: set `versionName = "X.Y.Z"`, bump `versionCode`.
- `wear/build.gradle.kts`: set `versionName = "X.Y.Z"`, set `versionCode` one higher.

Both must keep reading the same `keystore.properties`: the two artifacts have to be
signed with the same key or the Wear Data Layer stops delivering between them.

## 3. Changelogs

- `CHANGELOG.md`: rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD`, keeping the
  Added / Changed / Fixed / Digital key subsections. Add a fresh empty `## [Unreleased]`
  above it.
- `README.md`: update the `**Version X.Y.Z**` header, and add a `### X.Y.Z` section at
  the top of `## Changelog` summarising the release for users. Lead with any critical
  warning, the way 0.1.7 did.

## 4. Verify

```bash
./gradlew --continue verify   # Windows: gradlew.bat --continue verify
trunk check
```

`verify` includes the release (R8) build, so a missing ProGuard keep rule fails here
rather than in a published APK.

## 5. Commit, tag, publish

Commit as `X.Y.Z: <comma-separated highlights>`, matching earlier release commits
(e.g. `0.1.7: multi-car switcher, accept shared cars in-app, …`).

The signed build needs the maintainer's untracked `keystore.properties`; without it
`assembleRelease` produces unsigned APKs. Publishing is maintainer-only:

```bash
./gradlew assembleRelease           # signed when keystore.properties is present
git tag vX.Y.Z && git push origin vX.Y.Z
# then attach openzeekr-phone-X.Y.Z.apk to the GitHub release
```

The in-app update check reads the GitHub releases feed, so the tag and release must
exist for users to be offered the update.
