# Contributing to OpenZeekr

Thanks for your interest. OpenZeekr is an experimental reverse-engineering
project, so contributions are welcome but the bar for anything touching secrets,
crypto, or the digital key is high. Please read [SECURITY.md](SECURITY.md) before
you start.

## Prerequisites

- A full **JDK 17** (the build targets Java/Kotlin 17). It must include `jlink`,
  which some distro "headless"/JRE packages leave out; Temurin 17 works.
- **Android SDK** with:
  - `compileSdk` / `targetSdk` 34,
  - **NDK `27.0.12077973`** and **CMake `3.22.1`** — required to build `:core`'s
    native secrets lib (`libozsecrets.so`).
- The **Gradle wrapper** (`./gradlew`) — do not install Gradle separately.
- Optionally **[Trunk](https://docs.trunk.io)** for linting (the same checks CI
  runs). Just install the CLI; the config is already committed in `.trunk/`.

Step-by-step install instructions for Linux, macOS and Windows, plus fixes for
common setup errors, are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

You do **not** need any real secrets to build. A clean clone builds and runs with
everything blank; you configure the app in its Settings screen. If you have your
own extracted secrets, copy the templates:

```bash
cp secrets.properties.example secrets.properties
cp keystore.properties.example keystore.properties
cp core/secrets_firebase.xml.example core/src/main/res/values/secrets_firebase.xml
```

None of those files are tracked — see [SECURITY.md](SECURITY.md) for the full list.

## Project layout

- `:core` — BLE / digital-key / crypto / cloud logic and the native secrets lib
  (the bulk of the code).
- `:app` — the Compose phone UI.
- `:wear` — the Wear OS companion (reuses `:core`).

## Build, test, lint

Everything goes through Gradle, so the same commands work on Linux, macOS and
Windows (use `gradlew.bat` instead of `./gradlew` on Windows). CI runs exactly
these tasks.

```bash
./gradlew verify             # everything below, in one go
./gradlew verifyBuild        # assembleDebug for all modules (a clean clone builds blank)
./gradlew verifyTest         # offline JVM unit tests for all modules
./gradlew verifyLint         # Android Lint, gated by each module's lint-baseline.xml
./gradlew verifyRelease      # release build (R8) — catches missing ProGuard keep rules
./gradlew --continue verify  # report every failure, not just the first

./gradlew :core:testDebugUnitTest  # a single module
```

`verifyRelease` builds unsigned when `keystore.properties` is absent, so it needs no
secrets. It matters because debug builds skip R8: a missing keep rule for serialization,
Retrofit, reflection or JNI breaks only the release build.

Failing tests print their name and assertion, and lint prints each issue as
`file:line` with its rule id. Full HTML reports are under `<module>/build/reports/`.

**Lint baseline.** The existing code has known lint issues (a few of them errors).
They are recorded in `app/`, `core/` and `wear/lint-baseline.xml`, so only _new_
errors fail the build. If you fix a baselined issue, or have to accept a new one
deliberately, regenerate the baselines with `./gradlew updateLintBaseline` and
commit them with an explanation in the PR.

Trunk (formatting, ktlint, secret scanning, and the rest):

```bash
trunk check          # changed files
trunk check --all    # whole tree
trunk fmt            # auto-format
```

Please make sure `verify` and `trunk check` pass locally before opening a PR.

## Coding conventions

- Kotlin official style; formatting is enforced by `ktlint` via Trunk.
- Keep the dense, explanatory KDoc style the codebase already uses — the comments
  documenting _why_ a signing/handshake step exists are valuable, keep them
  accurate when you change behaviour.
- Don't introduce new dependencies or auth schemes without explaining why.

## Pull requests

- Branch from `master`; keep each PR focused on one change so it is easy to
  review (and, for upstream, easy to cherry-pick).
- Fill in the PR template, including the **security checklist** — say explicitly
  whether the change touches logging, tokens, keys, or VINs.
- Do not commit any secret material (see [SECURITY.md](SECURITY.md)). `trufflehog`
  will flag it, but don't rely on that.
- Update `CHANGELOG.md` for user-visible changes.

## AI-assisted contributions

AI coding agents are welcome. Much of this project was written with one. Agent
instructions live in [AGENTS.md](AGENTS.md), which Claude Code, Kiro, Codex,
Cursor and Copilot read automatically.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the parts fit together
and lists the invariants that break silently. It's worth reading before your
first change, agent or not.

The human opening the PR is responsible for it:

- Review the diff. Pay special attention to the security-sensitive paths listed
  in AGENTS.md.
- Run `verify` yourself, and test at the car when the change affects vehicle
  behaviour.
- Keep the agent's `Co-Authored-By:` trailer so reviewers know.

When an agent gets something wrong twice, fix AGENTS.md in the same PR. Keep it
short and concrete.

## Reporting security issues

Do **not** open a public issue. Follow the private process in
[SECURITY.md](SECURITY.md).
