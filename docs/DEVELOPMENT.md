# Development setup

Everything you need to build, test and lint OpenZeekr on Linux, macOS or Windows.
The build checks this environment when Gradle starts (see the preflight in
`settings.gradle.kts`) and points back here if something is missing.

## 1. JDK 17 (full JDK, with `jlink`)

The Android build needs `jlink`, which JRE and some distro "headless" packages
leave out. [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17)
works on every OS, and so does the JBR bundled with Android Studio.

- **Linux:** `sudo apt install openjdk-17-jdk` (not `-jre` / `-jdk-headless`), or
  unpack a Temurin tarball, e.g. into `~/.jdks/`.
- **macOS:** `brew install --cask temurin@17`
- **Windows:** `winget install EclipseAdoptium.Temurin.17.JDK`

Point `JAVA_HOME` at it and check it with `"$JAVA_HOME/bin/jlink" --version`.
Newer JDKs (21) also run the build, but CI uses 17.

## 2. Android SDK

With **Android Studio**, open the project and let it sync. It creates
`local.properties`. Then install the NDK and CMake versions below in
_SDK Manager › SDK Tools_ (tick "Show Package Details").

**Without Android Studio**, download the
[command-line tools](https://developer.android.com/studio#command-line-tools-only)
and unpack them to `<sdk>/cmdline-tools/latest/`, where `<sdk>` is:

- **Linux:** `~/Android/Sdk`
- **macOS:** `~/Library/Android/sdk`
- **Windows:** `%LOCALAPPDATA%\Android\Sdk`

Then accept the licences and install the packages CI uses:

```bash
sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
  "ndk;27.0.12077973" "cmake;3.22.1"
```

On Windows, run `sdkmanager.bat` and put the whole command on one line.

The NDK version is pinned once, in `gradle.properties` (`openzeekr.ndkVersion`).

Tell Gradle where the SDK is, in either of these ways:

- **`local.properties`:** create it in the repo root (it's gitignored), containing
  `sdk.dir=/home/you/Android/Sdk`. On Windows, escape the path:
  `sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`.
- **`ANDROID_HOME`:** set this environment variable to the SDK directory.

## 3. Trunk (optional, for linting)

[Trunk](https://docs.trunk.io) runs the same linters, formatters and secret
scanners as CI. The config is already in `.trunk/`, so you only need the CLI:

- **Linux / macOS:** `curl https://get.trunk.io -fsSL | bash` (or `brew install trunk-io`)
- **Windows:** Trunk's Windows support is limited. Use Git Bash with the command
  above, or WSL. CI runs Trunk on Linux either way.

Once Trunk is installed, it adds git hooks: `trunk fmt` runs on commit and
`trunk check` runs on push.

## 4. Verify the setup

```bash
./gradlew verify     # Windows: gradlew.bat verify
trunk check
```

`verify` runs the debug build, the unit tests, Android Lint and the release (R8) build.
The release step is what catches a missing ProGuard keep rule, which a debug build cannot.

A clean clone needs **no secrets** to build. The app starts unconfigured and is
set up in its Settings screen. For real secrets, see "Getting your own keys" in
the README and the `*.example` files.

## Troubleshooting

| Symptom                                                               | Cause and fix                                                                                                                          |
| --------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `The JDK at ... has no jlink` / `jlink executable ... does not exist` | You're running on a JRE or headless JDK. Install a full JDK 17 (step 1).                                                               |
| `Android SDK not found` / `SDK location not found`                    | Set `sdk.dir` in `local.properties` or `ANDROID_HOME` (step 2).                                                                        |
| `NDK ... is not installed` / `NDK not configured`                     | `sdkmanager --install "ndk;27.0.12077973"`                                                                                             |
| `CMake '3.22.1' was not found`                                        | `sdkmanager --install "cmake;3.22.1"`                                                                                                  |
| `Aborting build since new baseline file was created`                  | A module's `lint-baseline.xml` is missing. Run `./gradlew updateLintBaseline` and commit the result.                                   |
| `Lint found errors in the project`                                    | A new lint error. The console shows `file:line` and the rule id. Fix it; baselining is a last resort.                                  |
| Trunk reformats a whole file on commit                                | Your editor and ktlint disagree. `.editorconfig` is the shared contract for both; make sure your IDE honours it (Android Studio does). |
| ktlint wants to rewrap everything                                     | Check that `.editorconfig` is present. Without it ktlint falls back to its own stricter `ktlint_official` style.                       |
