# CLAUDE.md

Project instructions are shared by all agents in AGENTS.md, imported here:

@AGENTS.md

## Claude Code specifics

### Subagents (`.claude/agents/`)

| Agent               | Use it                                                                                     | Edits files?                           |
| ------------------- | ------------------------------------------------------------------------------------------ | -------------------------------------- |
| `code-reviewer`     | After finishing a change, before committing or opening a PR                                | No                                     |
| `security-reviewer` | Any change to secrets, logging, `net/`, `ble/`, the watch key clone, manifests or R8 rules | No                                     |
| `test-writer`       | New or changed pure logic; reproduce a bug with a failing test first                       | Only `core/src/test/`                  |
| `build-doctor`      | `verify`, Trunk or CI fails; final green check before "done"                               | Build fixes only, never lowers the bar |

Typical flow for a change:

1. Implement it.
2. Run `test-writer` for any testable logic.
3. Run `build-doctor` until `verify` and Trunk are green.
4. Run `code-reviewer`, plus `security-reviewer` when a sensitive path is
   touched.
5. Fix the findings, then report back, including anything that still needs
   testing at the car.

### Path-scoped rules (`.claude/rules/`)

These load automatically when you read files in their area, and hold the
invariants for that area:

- `digital-key-ble.md`: `ble/`, `wear/` (phone and watch)
- `cloud-network.md`: `net/`, `remote/`, `push/`, `config/`
- `build-and-release.md`: Gradle files, manifests, ProGuard, `cpp/`, `.github/`

### Other

- **Permissions:** shared permissions live in `.claude/settings.json`. It allows
  the verify and Trunk commands, denies reading secret files, and blocks
  force-push. Put personal overrides in `.claude/settings.local.json`
  (gitignored).
- **Release:** `/release <version>` runs `.claude/skills/release/SKILL.md`. It
  only runs when invoked explicitly.
- **Loaded files:** `/context` should list this file and `AGENTS.md` under
  Memory files, and `/agents` lists the subagents.
