---
name: release
description: Prepare an OpenZeekr release (version bump for phone + watch, changelogs, README, verify, release commit). Use only when the user explicitly asks to cut a release.
argument-hint: "[version, e.g. 0.1.8]"
disable-model-invocation: true
---

# Release

Prepare release **$ARGUMENTS**.

Follow [docs/RELEASING.md](../../../docs/RELEASING.md) exactly, in order. It is the
single source of truth for the procedure; this skill only invokes it.

Constraints for this run:

- Do **not** tag, push or publish. Those are maintainer-only steps. Stop after the
  release commit and report the commands the maintainer should run.
- Never read `keystore.properties`.
- If `CHANGELOG.md` has no `## [Unreleased]` content, stop and ask what the release
  contains instead of inventing entries.

Report at the end: the new version codes, the changelog entry you wrote, and the
remaining maintainer commands.
