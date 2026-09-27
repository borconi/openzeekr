---
paths:
  - "core/src/main/java/com/openzeekr/app/ble/**"
  - "core/src/main/java/com/openzeekr/app/wear/**"
  - "app/src/main/java/com/openzeekr/app/wear/**"
  - "wear/src/main/**"
---

# Digital key / BLE / watch

Security-sensitive. Read `docs/ARCHITECTURE.md` "Digital key (BLE)" before changing anything here.

- The car accepts one BLE peer. `ProximityService` is the only phone-side owner of the link. The watch borrows it via
  the pause-link / resume-link handover (the phone auto-resumes after 45 s). Don't add another connector.
- Never log or export session keys, the digital key, or private-key material.
- Never bypass or weaken `DkTrust` (CA validation + per-VIN pinning), even to "make it connect".
- Lock/unlock waits for the car's confirmation. `REJECTED` is final. Only `NO_RESPONSE` / `WRITE_FAILED` refresh
  and retry once.
- Walk-away lock is fail-safe: "unknown" leads to a cloud lock. Never change it to leave the car open.
- The watch manifest keeps `BLUETOOTH_SCAN` with `neverForLocation` + `tools:node="replace"`, otherwise watch scans
  silently return nothing.
- Behaviour here only proves itself at the car. State what was tested on a real vehicle, and run the
  `security-reviewer` agent before merging.
