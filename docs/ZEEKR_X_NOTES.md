# Zeekr X (BX1E) Digital Key - working notes (zeekr-x-dk branch)

Separate from master / 0.2.7. Full RE background: /home/emil/Temp/zeekr/ZEEKR_X_DK_FINDINGS.md and DK1.0_SUPPORT_MATRIX_NOTES.md.

## Failure (ground truth, David's log)
BX1E-A2-M-R, VIN L6TBX2SC2TF207872, SEA, dkId 8001f0c4. BLE connects, all 4 DK chars discovered, app sends 0x0101 CONNECT_CONFIRM, car sends NOTHING back (no 0x0102) and tears the link down (status=19), x4. Silence = rejected before the DK app layer (any DK error would itself be a 0x0102). Diag showed the 0xFF coef stub (calib hash a8100ae6 = SHA256(0xFF)).

## Done in this session (UNCOMMITTED, in this worktree)
Additive instrumentation, all behind the existing BLE-log toggle (no flow change):
- RealDkSession.kt connectConfirm(): logs the PRE-encryption 0x0101 plaintext + connectKey + cbcIv + initState. This is the decisive split: if the plaintext ts/dkID/phoneId look right and the connectKey derives from the right VIN+rnd, a silent car == key-not-registered (cause #1); if connectKey/VIN/rnd are wrong, it is a decrypt mismatch (cause #2).
- RealDkSession.kt handshake diag: warns "CALIB STUB" when coefSmall/coefBig is the 0xFF/empty placeholder (cause #3 predictor).
- DkProvisioning.kt pollCarSync(): the sync-timeout warning is now actionable (delete+recreate; phone may be unsupported for this model).
Note: a raw advert hexdump already exists (DkBleManager logs `adv ... raw=<hex>`), so no new advert instrumentation was needed.

## Step 2 - blocking dkStatus gate (PROPOSAL, needs approval; NOT wired)
Today pollCarSync() only warns on timeout and provisioning proceeds to BLE. Proposed: when loop-key-status never reaches dkStatus 3 (AUTHED) or 5 (ACTIVATED) within the window, surface a VISIBLE provisioning state (not just a log) telling the user the car has not accepted the key - "re-provision; your phone may not be supported for this car" - instead of silently attempting a BLE pair that will be rejected.
Risk / why not auto-wired: dkStatus 3 is the normal pre-BLE state and is already treated as success; the gate must only fire on genuine non-progress (timeout, or a stuck 6/11). A too-eager block would break first-pair on slow-syncing cars. This is behaviour-changing and unbuildable/untestable in WSL, so it is left for review + a Studio test.

## Step 3 - no-spoof experiment (DESIGN, not wired)
Hypothesis: our hardcoded Pixel 7 device identity (ZeekrConst.XCHANGER_DEVICE_MANUFACTURE/BRAND/MODEL/AGENT_VERSION/SDK_INT) may be why the X hands back a 0xFF coef stub - the cloud keys the calibration profile to the REAL device, and a spoofed model that lacks a profile for BX1E yields the stub, which the stricter X firmware then rejects (our lenient EU reference car does not).
Experiment: add a DEV toggle "use real device identity" that feeds Build.MANUFACTURER / Build.BRAND / Build.MODEL / Build.VERSION.RELEASE / Build.VERSION.SDK_INT into the xchanger/DK device fields instead of the Pixel 7 constants, then re-provision on the X and check whether the cloud returns a real (non-0xFF) coef and whether 0x0102 comes back.
Risks / flags: (a) the real phone must itself be on the DK 1.0 supported list for the Zeekr X or there is no profile either; (b) other regions/cars currently rely on the Pixel 7 spoof passing attestation - do NOT make real-identity the default, keep it a per-attempt DEV toggle; (c) a real unsupported model could fail provisioning earlier/differently. Keep it opt-in and logged.

## Decisive next datapoint (no X on hand)
David's EXACT phone model (is it ticked for the Zeekr X column in the DK 1.0 guide?) + one provision log (delete+recreate with HTTP+BLE logging) which now prints dkStatus. See DAVID_X_REPLY_DRAFT.txt in the RE folder.
