# AGENTS.md

Instructions for AI coding agents (Claude Code, Kiro, Codex, Cursor, Copilot, …)
working in this repository. Humans: see [CONTRIBUTING.md](CONTRIBUTING.md).

OpenZeekr is a reverse-engineered Android companion app for Zeekr (EU) vehicles.
It provides cloud remote control plus an offline BLE digital key, with a Wear OS
companion. It is used only with vehicles and accounts the user owns or is
authorized to use. Never add features whose purpose is accessing other people's
cars or accounts.

## Setup and commands

- **Setup:** follow [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). You need a full
  JDK 17 (with `jlink`), the Android SDK with NDK `27.0.12077973` and CMake
  `3.22.1`, and `local.properties` or `ANDROID_HOME`. If the environment is
  wrong, Gradle fails at startup with the exact fix. Read that message instead of
  guessing.
- **Windows:** use `gradlew.bat` instead of `./gradlew`.
- **How it fits together:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) covers
  the flows (startup, cloud signing, digital key, watch handover) and has a
  **"Change checklist"** of invariants that break silently. Read the relevant
  section before changing that area.

| Command                                                                           | What it does                                                                   |
| --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `./gradlew verify`                                                                | Build, unit tests, Android Lint and release/R8, exactly as CI runs them.       |
| `./gradlew --continue verify`                                                     | Same, but reports every failure instead of stopping at the first.              |
| `./gradlew verifyRelease`                                                         | Release build only (R8). Debug builds cannot catch a missing keep rule.        |
| `./gradlew :core:testDebugUnitTest --tests 'com.openzeekr.app.net.VinCryptoTest'` | Run one test class.                                                            |
| `trunk check` / `trunk fmt`                                                       | Linters, formatters and secret scanning (ktlint, trufflehog, markdownlint, …). |

## Definition of done

Before you say a change is finished:

1. `./gradlew verify` passes. Paste the relevant output, not just "it builds".
2. `trunk check` is clean for the files you touched.
3. New or changed pure logic has a unit test (see Testing). A bug fix has a test
   that fails without the fix whenever the code is testable on the JVM.
4. Every user-visible change is in `CHANGELOG.md` under `## [Unreleased]`
   (create that section at the top if it is missing).
5. The change goes on its own branch with one concern, not on `master`.
6. The diff was reviewed against this file and the ARCHITECTURE change
   checklist, with a separate security review for security-sensitive paths.

Say plainly what you could not verify, e.g. "not tested at the car" or "BLE path
untested". Many features only prove themselves on a real vehicle.

## Layout

- `:core`: all logic, in `core/src/main/java/com/openzeekr/app/`.
  - `ble/`: digital key: handshake, crypto, proximity. `ble/rpa/` is remote parking.
  - `net/`: TSP cloud client (Retrofit, signing, login).
  - `remote/`: repositories and commands.
  - `config/`: `SecretsConfig`, `ConfigStore`.
  - `util/`: `Logx`, `LogCrypto`, `NativeSecrets`.
  - `push/`, `wear/`: FCM push, and code shared with the watch.
  - `src/main/cpp/`: the native secrets lib.
- `:app`: Compose phone UI (`ui/`), plus `App.kt`, which holds `Deps`.
- `:wear`: Wear OS app. Its `applicationId` must stay `com.openzeekr.app`, the
  same as the phone app.
- The README's "Project structure" section is out of date. Trust the tree.
- `CMAC_FINDINGS.md` and `SENTRY_ENDPOINT_FINDINGS.md` are referenced in code
  but are local-only. Don't recreate or look for them.

## Architecture conventions

- **Wiring:** use manual DI through `Deps` (`core/.../Deps.kt`). Register new
  services there. Don't add Hilt, Koin or other DI frameworks.
- **Results:** repositories return `CallResult` (`Ok` / `Err`). Don't throw
  across the repository boundary.
- **State:** expose it as `StateFlow`. Screens take `deps` and use
  `collectAsState()`. The project has no ViewModels; don't introduce them for a
  single screen.
- **Coroutines:** use `deps.appScope` for app-lifetime work, `Dispatchers.IO`
  for blocking I/O, and never block the main thread.
- **Vehicle actions:** go through `VehicleControl` (BLE first, cloud fallback)
  rather than calling BLE or cloud directly from the UI.
- **Capabilities:** show a UI control only when `CapabilityHolder` says the car
  supports it.

## Code quality

- Kotlin official style. Match the surrounding code rather than restyling it.
- Keep the explanatory KDoc style: comments say **why**, e.g. which stock-app
  behaviour or protocol detail a line mirrors. Update comments when behaviour
  changes. A stale protocol comment is worse than none.
- No new dependency without a one-line justification in the PR. Prefer what's
  already there (OkHttp, Retrofit, kotlinx.serialization, BouncyCastle, Compose
  Material 3).
- Keep changes minimal and focused. Don't refactor unrelated code in the same
  change.

### Formatting

`.editorconfig` is the shared contract between the IDE and ktlint (run by Trunk,
locally via a pre-commit hook and in CI). Don't change it casually: it disables
the ktlint rules that contradict this codebase's deliberate style (compact
one-line bodies, aligned trailing comments, PascalCase `@Composable` names).
Re-enabling one means a large mechanical reformat, so discuss it first.

If a formatter still wants to rewrite a whole file you only touched slightly,
commit the reformat on its own first (`trunk fmt <file>`, committed as
`style: …` with no behaviour change), then the real change on top. Never mix
mass reformatting with behaviour changes, and don't reformat files you didn't
need to touch.

## Testing

- Tests are JUnit 4 in `core/src/test/java/…`, plain JVM, offline. The examples
  to follow are `DkCmacTest` (known-answer crypto vectors) and `VinCryptoTest`.
- **Good targets:** `DkCrypto`, `DkFrame`, `DkFragmenter`, `DkProtocol`,
  `Signing`, `OverseasSign`, `VinCrypto`, `InboxAuthToken`, `Region`,
  `UpdateChecker.isNewer`, and model (de)serialization.
- **Android stubs:** `unitTests.isReturnDefaultValues = true`, so Android APIs
  return defaults. `android.util.Log` does nothing, `android.util.Base64`
  returns `null`, and `NativeSecrets` returns `""`. A test that passes only
  because of a stub proves nothing. Move the logic into a pure function and test
  that.
- **No network, device or real secrets in tests.** Use fixed test vectors and
  obviously fake values.
- Don't add Robolectric or MockK without discussing it first. Extracting pure
  functions is the preferred route.

## Secrets, logging and security (non-negotiable)

- **Never read, print or commit secret files:** `secrets.properties`,
  `secrets.json`, `zeekr_secrets.json`, `keystore.properties`, `*.jks`,
  `*.keystore`, `secrets_firebase.xml`, `core/src/main/cpp/secrets_generated.h`,
  `tools/log-decrypt/private_key.pem`. Use the `*.example` files to learn their
  shape.
- **Generated header:** `secrets_generated.h` is rewritten on every Gradle
  configure. Don't edit it.
- **Clean clone:** it must build with every secret blank. Never make a secret
  required at build time, and never hardcode one.
- **Car renders:** they're copyrighted and gitignored (`*/src/main/assets/cars/`).
  Don't add images of Zeekr cars.
- **Logging:** use `Logx.d` / `Logx.w` / `Logx.e(area, msg)`, not
  `android.util.Log`.
  - Pick an area listed in `HTTP_TAGS` or `BLE_TAGS` in `util/Logx.kt`, so the
    Settings toggles gate it.
  - Never log tokens, passwords, keys, raw VINs or request bodies. Use
    `Logx.preview(value)`, or log presence (`auth=yes`). Pass HTTP bodies and
    URLs through `HttpLog.scrub()` (`net/ApiClient.kt`). Build new OkHttp
    loggers with `HttpLog.interceptor()`.
  - Guard expensive or sensitive strings with `Logx.isHttpEnabled` or
    `Logx.isBleEnabled`.
- **Log export:** `LogCrypto` output is encrypted only. Never add a plaintext
  fallback.
- **Security-sensitive code:** changes here need a test where feasible and an
  explicit "Security impact" note in the PR. Never weaken validation to make
  something work.
  - `ble/RealDkSession.kt`, `ble/DkCrypto.kt`
  - `ble/DkTrust.kt` (vehicle CA and certificate pinning)
  - `ble/DkIdentity.kt`, `ble/DkCredential.kt`, `ble/DkProvisioning.kt`
  - `net/Signing.kt`, `net/Interceptors.kt`, `net/AccountLogin.kt`,
    `net/VinCrypto.kt`
  - `util/LogCrypto.kt`, `util/NativeSecrets.kt`, `config/ConfigStore.kt`
  - `app/…/wear/` and `core/…/wear/` (key cloning to the watch)

## Android Lint

- Lint errors fail `verify`. The issues that already existed are recorded in
  each module's `lint-baseline.xml`.
- Fix new lint errors. Don't baseline them. If a baseline change is truly needed
  (e.g. you fixed a baselined issue), run `./gradlew updateLintBaseline` and
  explain the diff in the PR.
- Don't disable lint checks or `abortOnError`.

## Versions, docs and releases

- **Versions:** don't bump `versionCode` / `versionName` in feature changes. That
  happens only at release time, following
  [docs/RELEASING.md](docs/RELEASING.md).
- **Docs:** update README or `docs/` when setup, commands or behaviour described
  there change.

## Git and PRs

- **Branches:** branch from `master` with a short prefix: `feat/`, `fix/`,
  `build/`, `ci/`, `docs/` or `chore/`. Never commit to or push `master`
  directly. Never force-push shared branches.
- **Commit messages:** `<type>: <imperative summary>` (`fix:`, `feat:`,
  `build:`, `ci:`, `docs:`, `style:`, `test:`). Release commits are
  `X.Y.Z: <summary>`.
- **Keep PRs small and single-purpose.** This is a fork-friendly project, and
  PRs get cherry-picked upstream.
- **PR template:** fill in `.github/pull_request_template.md`, including the
  security checklist.
- **AI disclosure:** keep the agent's co-author trailer (e.g. `Co-Authored-By:`)
  on AI-assisted commits. A human reviews and tests before merge.
