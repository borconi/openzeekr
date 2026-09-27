---
paths:
  - "**/*.gradle.kts"
  - "gradle.properties"
  - "**/proguard-rules.pro"
  - "**/AndroidManifest.xml"
  - "core/src/main/cpp/**"
  - ".github/**"
---

# Build, manifests, native, CI

Read `docs/ARCHITECTURE.md` "Release and R8" and "Secrets pipeline" before changing anything here.

- `:app` and `:wear` share `applicationId = "com.openzeekr.app"` and the signing key. Never change one alone.
- Release version codes follow watch = phone + 1. Only bump them via the `/release` skill.
- `:core` ships no consumer ProGuard rules. New serialization, reflection or JNI needs keep rules in both
  `app/proguard-rules.pro` and `wear/proguard-rules.pro`. Debug builds don't run R8, so check with
  `./gradlew assembleRelease`.
- `core/src/main/AndroidManifest.xml` merges into both apps. Check the watch's `tools:node` overrides.
- JNI names in `ozsecrets.c` must match `util/NativeSecrets.kt`. Keep the 16 KB page-size link flags.
- The NDK version lives only in `gradle.properties` (`openzeekr.ndkVersion`). Keep the CI install step in sync.
- A clean clone must build with no secrets. Never make `secrets.properties` or the Firebase config required.
- CI must run the same `verify` tasks developers run. Don't add CI-only checks.
- Never baseline new lint errors, or disable checks or `abortOnError`.
