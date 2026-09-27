<!-- markdownlint-disable MD041 -->
<!-- Keep PRs focused on one change so they are easy to review and cherry-pick. -->

## What & why

<!-- What does this change do, and why? Link any related issue. -->

## How it was tested

<!-- Commands run and results, plus any real device/car or emulator testing. -->

- [ ] `./gradlew verify` (`gradlew.bat verify` on Windows) passes on a clean clone
- [ ] `trunk check` is clean
- [ ] If `lint-baseline.xml` changed, the PR explains why
- [ ] If an AI agent helped: I reviewed the whole diff and kept the co-author trailer

## Security checklist

<!-- See SECURITY.md. Answer honestly — this project has a history of log leaks. -->

- [ ] This change does **not** commit any secret (secrets.properties, keystore,
      firebase config, generated native header, private keys).
- [ ] This change does **not** log tokens, secrets, keys, passwords, or raw VINs.
- [ ] If it touches networking/auth/crypto (`net`, `ConfigStore`, the digital
      key), I have described the impact above.
- [ ] No new dependency or auth scheme was added without justification.

## Changelog

- [ ] `CHANGELOG.md` updated for user-visible changes (or N/A).
