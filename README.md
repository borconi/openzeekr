# OpenZeekr

**Version 0.1.7** · [⬇ Download the latest signed APK](https://github.com/borconi/openzeekr/releases/latest) · [Changelog](#changelog)

> 🧪 **Early beta — testing in progress.** This is an experimental research project
> under active development. It's usable and being tested by early users, but expect
> rough edges and changes without notice. **Support is best-effort** — this is not a
> commercial product (at least not for now), so there are no warranties, guarantees or
> SLAs. Use at your own risk.

A reverse-engineered Android companion app for a **Zeekr (overseas / EU) vehicle** —
remote control over the Geely/ECARX TSP cloud, and a working **offline BLE digital key**
(lock/unlock at the car), with proximity unlock and remote-parking research on the
same digital-key channel.

Built from independent reverse-engineering of the author's **own** vehicle and app,
for interoperability and research.

> ⚠️ **Authorized use only.** Use this only with a Zeekr account and vehicle that
> **you own or are explicitly authorized to access.** This is a research tool, not a
> way to access cars that aren't yours.

> 🌍 **EU verified; other regions experimental.** A **region selector** (EU / SEA / LA / ME)
> picks the right Zeekr servers, project-id, country and push region for your market. Only **EU**
> has been verified end-to-end on a real car and account; **SEA / LA / ME** are reconstructed
> from the stock app's own host tables and are **untested** — bring your region's own extracted
> secrets, and use Settings › Region › Advanced to correct a host if login or the digital key
> fail for your market.

## ⚠️ Read this before you start

> **This app is vibecoded, as it still being actively developed and play around with it,
> code haven't been checked against performance / bug / leaks / etc !!!**

> 🔐 **You can only be signed in on one app at a time.** The Zeekr cloud allows **one
> active session per account** — signing into OpenZeekr signs you out of the official
> Zeekr app, and vice-versa. **Strongly recommended:** create a **secondary account**,
> **share your car to that second email** from your owner account, and use the secondary
> account in OpenZeekr. That way the official app stays logged in on your main account and
> the two don't keep kicking each other out.

> ⌚ **The Wear OS companion copies the digital key onto your watch.** That means
> **anyone with access to your watch has a working key to your car** — it can unlock and
> use the car. If you're not comfortable with that, **don't install the watch companion.**
> If your watch is lost or stolen, you can **revoke the key from the phone app**, which
> kills it with the car and renders the watch useless.

## Credits & thanks 🙏

This project stands on the shoulders of the community that mapped the Zeekr/Geely
cloud first. Huge thanks to:

- **[Wysie/zeekr_key_extractor](https://github.com/Wysie/zeekr_key_extractor)** — extracting the per-region app keys.
- **[Fryyyyy/zeekr_homeassistant](https://github.com/Fryyyyy/zeekr_homeassistant)** — the login / HMAC / vehicle-list cloud flow.
- **[mescon/zeekr-7x-home-assistant](https://github.com/mescon/zeekr-7x-home-assistant)** — the 3.0.x EU signing recipe and the runtime-key insight.

OpenZeekr reimplements the cloud layer independently (Kotlin/Android) and adds the
BLE / digital-key research, but the account and signing groundwork was inspired by
their work. Following their convention, **no decrypted app secrets are published here.**

## Support this work 💛

This comes with **no support** — but a _lot_ of time, tooling and late nights went
into reverse-engineering it and bringing it this far. If it's useful to you, a small
token of appreciation is genuinely welcome (never expected):

**→ [revolut.me/emilimpd](https://revolut.me/emilimpd)**

## Changelog

### 0.1.7

- **Critical fix - reverted a 0.1.6 change that could break your digital key.** 0.1.6 included an
  experimental Bluetooth "pairing" step that was sent on every connection; it could corrupt the car's
  stored pairing for your phone, so entry/start stopped working and re-provisioning failed. That step
  is removed and the key handshake is back to the proven 0.1.5 behaviour. **If 0.1.6 broke your key:**
  update to 0.1.7, remove the OpenZeekr digital key from the official Zeekr app (Digital keys → remove),
  then re-provision in OpenZeekr. **Do not run 0.1.6.**
- **Multiple cars on one account.** Switch between all your cars - owned and shared - from the car-name
  dropdown at the top. The dashboard, status and controls follow the selected car, and each car can be
  renamed. Fixes the hero card sometimes showing the wrong car's model/colour after switching.
- **Accept a shared car without the official app.** When someone shares a car with you, OpenZeekr now
  shows an Accept / Decline prompt (owner, model, access and expiry). There's also a "Check for shared
  cars" button in Settings. When a share ends, the car drops out of the switcher automatically.
- **Fridge / cool-box control** (on/off + target temperature) and **Sun-shield** (rear sunshade)
  open/close, for cars that have them.
- **New vehicle stats:** odometer, distance and time to the next service, and the 12 V battery voltage.
- The charge tile is now **"Charge & more"** so it's clearer it opens all the charge settings (limit,
  scheduled charging, battery pre-conditioning, port). The quick-action grid also lays out more cleanly
  when a car doesn't have every control.
- Includes the 0.1.6 improvements below (SEA/Australia login, sign-out/back-in fix, gzip response
  handling, faster reconnect).

### 0.1.6

- **Fixed: couldn't log back in after signing out.** Signing out cleared the saved VIN, but the app
  wrongly treated a VIN as mandatory before it would even attempt login - so the sign-in was rejected
  with "vin is required" (the VIN is actually obtained _during_ login). You can now sign out and back in
  normally.
- **Southeast Asia / Australia login now works.** The region (now labelled **"Australia + SEA"**) was
  reaching the wrong login endpoint and couldn't read the gateway's responses, so sign-in failed at the
  very first step. It now uses SEA's correct user-center path and TSP signing, and decodes the SEA
  gateway's (compressed) responses properly - so login completes all the way through to your vehicle list.
  This finishes the SEA support that was only partial in earlier builds.
- **More reliable response handling everywhere.** Compressed API responses are now always decoded
  correctly (some regional gateways compress every response), which also prevents occasional garbled-data
  errors on other calls.
- **Faster reconnect when you're already parked at the car.** When the app comes up next to your car it
  now reconnects directly to it instead of waiting on a fresh scan.
- **Tidier notification counter** on the home screen.

### 0.1.5

- **Send a diagnostic log from the login screen.** If sign-in fails during setup, a "Share diagnostic log"
  button now appears right there, and the login attempt's network traffic is captured automatically - so a
  tester who can't get past login (and therefore can't reach Settings) can still send us an encrypted log to
  diagnose the failure.

### 0.1.4

- **Send a debug log as a file.** Settings now has a "Share" button that sends the encrypted log through
  your email / share sheet as a file attachment. A full session log is far longer than the clipboard can
  hold, so the old copy-paste route silently cut long logs off and made them unreadable - sharing a file
  fixes that. (The log is still encrypted so only the developers can read it.)
- **Shared accounts can create a key.** Provisioning no longer refuses to mint a digital key just because
  the signed-in account isn't the registered car owner - it attempts the mint and lets the server decide,
  so a second/shared account can set up its own key.
- **Clearer error when the link drops mid-pairing.** A Bluetooth drop during the key handshake now shows a
  plain "link dropped during the handshake" message instead of a cryptic obfuscated one.
- **Works on 16 KB-page devices.** The native secrets library is now aligned for 16 KB memory pages, so it
  loads correctly on newer devices (some Android 15+ phones). Previously it could fail to load there,
  leaving the app unconfigured (unable to sign in).
- **In-app update check.** OpenZeekr now checks GitHub on launch and tells you in Settings when a newer
  release is available, with a one-tap link to download it (plus a manual "Check for updates" button).
- **Stops retrying forever when a car won't pair.** If the car accepts the Bluetooth link but never
  completes the digital-key handshake, the app no longer loops connect / fail / reconnect endlessly
  (which drained the battery) - it now backs off automatically, while an explicit connect still retries
  immediately.
- **Better handshake diagnostics.** More detail is captured when the key handshake fails, and the
  on-screen error now names the likely cause, to help pin down cars that won't pair (share the
  encrypted log from Settings).
- **Anti-relay / fake-car protection.** The car's certificate is now pinned to your car on first
  pairing (and checked for validity), so a fake or relayed "car" - even one presenting a genuine
  Geely certificate - can no longer complete the handshake and harvest your digital key.
- **No key material in the log.** Removed leftover debug lines that wrote the AES session key and the
  raw digital key into the log.
- **Safer secrets export.** "Export" now copies your configuration to the clipboard instead of
  displaying your password, tokens and keys on screen.
- **Hardened the "send to car" link handling.** Shared map links are now only expanded over
  http/https to public addresses (no internal / loopback targets).
- **Southeast Asia (SEA) login.** Selecting the SEA region now uses SEA's own signing keys, so
  accounts registered in SEA/Malaysia can sign in (previously the app signed every region's login
  with the EU keys and SEA was rejected). LA / ME will follow once their keys are added.
- **Watch stays usable when the phone isn't at the car.** The Wear OS app now only stands down while
  the phone is _actually holding_ the car's Bluetooth link (not merely when Lock-on-approach is
  enabled), so you can still lock/unlock from the watch via the handover otherwise. Thanks to Jan
  Compen for the report and patch.

### 0.1.3

- **Lock / unlock is now confirmed by the car, with self-healing.** The app waits for the car's
  acknowledgement instead of assuming a sent command worked, and if the digital-key session has
  gone stale it rebuilds the link and retries automatically - previously a lock/unlock could
  silently do nothing until you toggled Bluetooth off and on.
- **Handshake retries.** The BLE key handshake now re-tries the connect step when the car doesn't
  answer the first time, instead of failing outright.
- **Broader car discovery.** The connect scan is now unfiltered, which should fix the "can't find /
  connect to the car" link issue (issue 1) for more models, e.g. the Zeekr X.
- **Session refresh for a stale link.** A stale digital-key session is now recovered with a fresh
  reconnect. This is an interim improvement over 0.1.2 and will likely change again in a later release.
- **Logging is OFF by default.** A clean install no longer starts with logging enabled - you opt in
  explicitly.
- **Sanitized HTTP logs.** Cloud request/response logs now redact keys, tokens and other secrets.
  (This still needs a full human review, which is not yet complete.)
- **Encrypted log sharing.** The BLE log "Copy" button now encrypts the copied log, so sharing a log
  on GitHub or elsewhere is safe and your car/account data isn't leaked by accident.
- **Compile-time keys hardened.** The keys baked into the APK at build time now live inside a native
  `.so` library instead of as plaintext strings in the APK.

### 0.1.2

- **Region selection (beyond EU).** You can now pick your market — **EU / SEA / LA / ME** — in
  onboarding and under Settings › Region. This swaps in that region's Zeekr servers (TSP gateway,
  Azure overseas-app, xchanger DK backend), project-id, country and push region. **Only EU is
  verified on a real car;** SEA/LA/ME are reconstructed from the stock app's host tables and ship
  as **experimental**, with an Advanced panel to correct any host per-market. As before, each
  region needs its **own** extracted secrets (`zeekr_key_extractor --region <REGION>`).
- **Fixed logging still leaking.** HTTP traffic was still being written to logcat even when
  the debug-logging switch was **off** — the toggle is now honoured everywhere, so nothing is
  logged unless you explicitly opt in.
- **Push notifications from Zeekr, directly in OpenZeekr.** Hooked into Firebase so the app now
  receives the car's message-centre pushes (security alarms, charging, service messages, …) and
  displays them itself — no need to keep the official app running to get notified.
- **Power trunk.** Added an option to power **open / close the trunk** remotely.
- **Better walk-away lock.** Enhanced the phone-side walk-away lock logic, including a cloud
  backstop so the car still gets locked if the BLE lock command can't be confirmed as you leave.
- **Average trip consumption.** The app now shows your car's average energy consumption
  (Ø kWh/100km) alongside the vehicle status.
- **Mobile data usage.** See how much of your car's built-in eSIM data plan you've used this
  month, right inside the app.
- **Charge & departure scheduling.** Set off-peak charging windows and departure / preconditioning
  times. Saving now works - the app commits the schedule to the car and reads it back to confirm.
- **Nicer climate UI.** Improved the UI for climate **cooling / heating**.
- **Seat ventilation detection fixed.** The controls now reflect **only the options your specific
  car actually supports**, and seat ventilation is detected correctly again.

### 0.1.1

- **Prevented log leakage when logging is off.** Debug logging is now truly silent unless
  you turn it on — and enabling it shows a clear warning first, because the log can contain
  key material, session tokens and your VIN.
- **Fixed a security concern around blindly trusting the car.** The app now authenticates
  the vehicle's certificate against the Geely/Zeekr CA chain **before** releasing the
  digital key, instead of accepting whatever certificate the car presents.
- **Rewrote the approach logic.** It now uses more sensors to decide when you're
  approaching the car, while letting the phone sleep so it doesn't drain the battery.

## What it does

Legend: ✅ working & verified · 🟡 built but **unverified** (may or may not work) · 🔴 not working yet.

| Area                                                                                            | Status                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Account login** (idaas → TSP bearer)                                                          | ✅ Working (EU) — logs in and obtains the TSP bearer token                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **DK BLE digital key — pair + lock/unlock**                                                     | ✅ **Working & verified at the car.** Reverse-engineered handshake (cert exchange → ECDH → AES-128-GCM session) + control opcodes `0x110`/`0x111`; locks and unlocks over BLE, no native libs                                                                                                                                                                                                                                                                                                                                                                                   |
| **DK cloud provisioning** (enrol our own keypair → key-info)                                    | ✅ Working — provisions OpenZeekr's own digital key and pairs to the car                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **Cloud remote control** (lock/unlock, climate, engine, charge, windows, flash/horn, sentry, …) | ✅ **Core verified** — commands accepted (`000000`) via the stock body shape + `X-SIGNATURE`; some commands are vehicle-state gated (e.g. refused at low battery SOC) so remain effectively unverified                                                                                                                                                                                                                                                                                                                                                                          |
| **Unified quick action** (BLE if a DK session is connected, else cloud)                         | ✅ Working                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **Foreground service** (keeps the DK BLE session connected; approach unlock/lock)               | ✅ **Working at the car.** Sole owner of the DK link — holds it connected and reconnects on drop; releases it on request so the watch companion can borrow the car's single BLE slot (pause → act → resume), then reclaims it. Screen-off approach uses a hardware-offloaded presence scan (zero-CPU wake)                                                                                                                                                                                                                                                                      |
| **Proximity unlock / walk-away lock (RSSI)**                                                    | ✅ **Working at the car.** Rides the keep-alive session's connected-GATT RSSI with adaptive cadence (2 s idle → 250 ms burst near the threshold) + EMA smoothing; unlocks on approach and locks on walk-away (zone crossings + a link-loss walk-away lock when the session drops as you leave). Auto-retries a failed unlock by resetting the BLE link                                                                                                                                                                                                                          |
| **Vehicle status**                                                                              | ✅ **Working (EU)** — single GET (`vehicle/status/latest?latest=false&target=new`), tolerant JSON map (lock/doors/SOC/range/climate/odometer/tyres/location). Handles this platform's quirks: SOC lives in `chargeLevel` (not blank `stateOfCharge`); charging derived from live `chargeIAct×chargeUAct` kW (the `isCharging` flag is wrong)                                                                                                                                                                                                                                    |
| **Sentry footage / live view**                                                                  | 🔴 **CN-only — not available on EU (or any overseas) gateway.** `sentinel-monitoring-service` is unrouted (gateway 404 `00A01`); our request is byte-identical to stock, so it's a server-side regional gap, not a client bug. **Hidden in the app for now; back-burner** — see below                                                                                                                                                                                                                                                                                           |
| **Remote parking (RPA/RSPA)**                                                                   | 🔴 **Not working — gated by the car.** The full schema is reverse-engineered and implemented (flow, opcodes, 500 ms dead-man heartbeat, challenge auto-answer, RSSI stream, AES-CMAC frame trailer + ECIES `cmacKey` unwrap). But at the car every request is NAK'd (`0x100a`) even with an owner key, armed on the head unit — the car only authorizes RPA for a **DK 3.0 / Secure-Element key**, which a software key can't be. Schema is available; actuation is not reachable. **Breaktrough** on talking to car, **DK 3.0** might not be needed, but to early to conclude. |
| **Wear OS companion** (standalone watch app)                                                    | ✅ **Working at the car.** Pulls the phone's DK key over the Wear Data Layer (no separate sign-in — same `applicationId` + signing key), then locks/unlocks the car **directly over BLE from the watch**. Arbitrates the car's single BLE peer slot with the phone: stands down while phone Lock-on-approach is on, otherwise runs a pause → act → resume handover so the two never fight for the radio. Hero-style watch face (car render + one-tap lock/unlock)                                                                                                               |

The digital-key handshake and lock/unlock are real and verified. RPA rides the same
`DkSession` and is byte-complete — flow, opcodes and CMAC crypto are all
reverse-engineered — but the car **refuses remote parking for a software key** (it
requires a DK 3.0 / Secure-Element key), so it doesn't actuate. See `CMAC_FINDINGS.md`
(local) for the RPA crypto derivation and schema.

## How OpenZeekr differs from the official app

OpenZeekr isn't just a re-skin — a few things work here that the stock Zeekr app
either restricts or doesn't offer at all:

1. **Digital Key works on any BLE-capable phone.** The official app's offline key is
   gated to a whitelist of Zeekr-approved phone models; OpenZeekr's key pairs and
   locks/unlocks from **any** Android phone with Bluetooth LE — no model allow-list.
2. **Proximity unlock _and_ walk-away lock actually work — without DK 3.0.** Hands-free
   approach unlock / walk-away lock run off the standard digital-key BLE session, so
   you don't need the newer DK 3.0 / UWB hardware the stock flow depends on.
3. **Seat ventilation (cooling), not just heating.** The climate screen can turn on
   seat **cooling/ventilation** — the hardware supports it, but the stock app only
   exposes seat heating.
4. **Journey log export to CSV.** The full trip history (date, times, distance, energy,
   start→end location + a Google Maps route link) exports to a spreadsheet — handy for
   anyone who needs trip logs for **mileage / accounting** purposes. The official app
   has no export.
5. **Standalone Wear OS companion.** A watch app that locks/unlocks the car **directly
   over BLE from the wrist** (one tap — no proximity), usable on its own once it has
   borrowed the key from the phone. The stock app has no equivalent.

## 🔑 Getting your own keys (required — none are shipped)

OpenZeekr **never** contains any account secret. To talk to the cloud you supply
**your own** keys, extracted from **your own** app install. There are six values,
in two groups:

**1. Static, per-region keys** — `hmac_access_key`, `hmac_secret_key`,
`password_public_key`. Extract these from the Zeekr APK with
**[Wysie/zeekr_key_extractor](https://github.com/Wysie/zeekr_key_extractor)**
(`--region EU` / `SEA` / `LA`).

**2. Runtime keys** — `prod_secret` (the `X-SIGNATURE` signing key), `vin_key`,
`vin_iv`. These are assembled at runtime and aren't plain strings in the APK, so
read them from **your own** running app with a dynamic-instrumentation tool (e.g.
Frida) by observing the standard crypto primitives it initializes
(`javax.crypto.Mac` / `SecretKeySpec` / `IvParameterSpec`). See the projects above
for the current recipe per app version.

Then put them into the app (nothing is compiled in):

- **Settings** screen → paste each value, or
- **Import JSON** in the same shape as [`secrets.example.json`](secrets.example.json).

They're stored in `EncryptedSharedPreferences` on-device only.

```jsonc
// secrets.example.json — fill with YOUR OWN extracted values
{
  "hmac_access_key": "",
  "hmac_secret_key": "",
  "password_public_key": "",
  "prod_secret": "",
  "vin_key": "",
  "vin_iv": "",
  "overseas_access_key": "",
  "overseas_secret_key": "",
  "email": "",
  "password": "",
  "vin": "",
}
```

**Optional — notifications inbox.** The message inbox lives on a _different_ backend
(the overseas-app Azure gateway) with its own HMAC AK/SK auth. To enable it, supply
`overseas_access_key` + `overseas_secret_key` — the native `getNativeApplicationId()` /
`getNativeSecret()` values from **your own** app's `libenv.so` (Frida-hook those, EU/PROD).
Leave them blank and everything else works; only the bell/inbox stays off.

## Proximity unlock / walk-away lock

Phone-side policy (we decide from RSSI, then issue an explicit DK command — not the
car-side `DKB` switch). Configure on the **Controls** screen:

- **Unlock at ≥ −65 dBm** (default) — closer = higher/less-negative RSSI.
- **Lock at ≤ −85 dBm** (default) — farther = lower RSSI. The gap is hysteresis so
  it doesn't flap at the boundary.
- Signal-loss watchdog: NEAR and no advertisement for 8 s → walked-away → lock.
- Optionally pin the vehicle's BLE MAC; blank ranges the strongest advertiser.

RSSI is exponentially smoothed (α=0.4). Zone transitions fire once: FAR→NEAR
unlocks, NEAR→FAR locks — issuing **real DK lock/unlock** over the working BLE
session. Screen-off approach is handled by a **hardware-offloaded presence scan**
(zero-CPU wake, filtered on the car's advertised service UUID + manufacturer data),
so the phone can wake and connect as you walk up without draining the battery.

## Project structure

Three Gradle modules:

```text
:core   all the logic, in core/src/main/java/com/openzeekr/app/
        ble/      digital key: GATT, handshake, crypto, proximity (ble/rpa/ = remote parking)
        net/      TSP cloud client: signing (X-SIGNATURE), interceptors, Retrofit, login
        remote/   command catalog + repositories (control, status, journey, inbox, share)
        config/   SecretsConfig + ConfigStore (EncryptedSharedPreferences, import/export)
        util/     Logx (gated logging), LogCrypto, NativeSecrets (JNI)
        cpp/      libozsecrets.so — build-time-injected secrets
:app    Compose phone UI (ui/), App.kt holds the Deps container
:wear   Wear OS companion; shares :core and the phone's applicationId
```

The map uses **MapLibre + OpenFreeMap** — free, no API key, no Google Play Services.

For how these fit together at runtime — startup, request signing, the digital-key
handshake, the phone/watch BLE handover — and the invariants that break silently, see
**[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

> Note: region, base URL and project-id are EU defaults baked into
> `SecretsConfig`/`ZeekrConst`; a **region selector** covers EU / SEA / LA / ME.

## Build

Open the folder in **Android Studio (Koala or newer)** and let it sync, or from the CLI:

```bash
./gradlew verify          # build + unit tests + Android Lint + release/R8, as CI runs it
./gradlew assembleDebug   # just the debug APKs
```

On Windows use `gradlew.bat` instead of `./gradlew`.

You need a full **JDK 17** (one that includes `jlink`; JRE/headless packages fail), the
Android SDK with **NDK 27.0.12077973** and **CMake 3.22.1** for `:core`'s native lib, and
a `local.properties` with your SDK path (Android Studio writes it for you):

```properties
sdk.dir=/path/to/Android/Sdk
```

The build checks this environment before it starts and names the fix if something is
missing. Full setup instructions for Linux, macOS and Windows, plus a troubleshooting
table, are in **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)**.

Contributing: **[CONTRIBUTING.md](CONTRIBUTING.md)**. Security policy and how secrets are
handled: **[SECURITY.md](SECURITY.md)**. Instructions for AI coding agents:
**[AGENTS.md](AGENTS.md)**.

### Car renders (not shipped)

The home screen tints a paint "identity card" and lays the car's white render on top.
Those renders are Zeekr press images, so they're **gitignored** (like the secrets) and
never published. Drop your own transparent white PNG/WebP per model into
`app/src/main/assets/cars/` as `car_{001,x,7x,7gt,9x}.webp` (and
`wear/src/main/assets/cars/` for the watch face). If absent, the hero simply shows the
coloured card with no car — the app still builds and runs.

## Signing (`X-SIGNATURE`)

`X-SIGNATURE = base64(HMAC(prod_secret, stringToSign))`, where `stringToSign` is:

```text
<x-api* headers, lowercased name:value, sorted, \n-joined>
<query sorted by key, k=v joined by &>
<hex MD5 of body, or "">
<HTTP METHOD>
<url path>
```

`X-TIMESTAMP` is a plain header and is **not** part of the signed string. The TSP
gateway uses **HMAC-SHA-256** (key = `prod_secret`); this is the EU recipe and is
what the app currently assumes.

## Roadmap

Done: ✅ DK BLE handshake + session-key/IV derivation · ✅ digital-key lock/unlock at
the car · ✅ GATT UUIDs + frame layout · ✅ RPA challenge-answer grid · ✅ RPA AES-CMAC
trailer + ECIES `cmacKey` unwrap (offline-validated) · ✅ vehicle status (EU) · ✅
first-run onboarding · ✅ two-stage low-power proximity policy.

Next:

1. **Proximity at the car** — tune the connect band / actuation thresholds on real
   approach/walk-away runs.

**Remote parking** is considered **closed** for this project: the schema is fully
reverse-engineered, but the car gates actuation behind a DK 3.0 / Secure-Element key
(every request is NAK'd `0x100a` even as owner, armed on the head unit), which a
software key structurally can't be. The reversed flow/crypto stays in the tree and in
`CMAC_FINDINGS.md` for reference, but it won't actuate without hardware we can't mint.

### Back-burner: Sentry / Sentinel (footage + live view) — CN-only

The `sentinel-monitoring-service` backend is **not deployed on the EU (or any
overseas) API gateway** — a live request returns HTTP 404 `00A01` ("no route
registered"). Our path, query and signing are **byte-identical to the stock app**
(verified against both `com.zeekr.overseas` 3.0.7 and `com.zeekr.global` 1.6.3 —
same interface class, same host table, same call-sites), so this is a **server-side
regional gap, not a client bug**. All five endpoints share one service prefix, so
footage list, snapshots and live view fall together. Auth is region-scoped (the
bearer token's issuer is the EU inner gateway; the VIN is registered EU-side), so an
EU account can't simply borrow another region's gateway. The feature is therefore
**hidden in the app for now and not testable on this vehicle** — parked here until a
workaround surfaces (e.g. a gateway that both routes the service and accepts the
credentials). Client code + reversed schemas are kept in `SENTRY_ENDPOINT_FINDINGS.md`
for when that day comes.

## License

MIT — see [LICENSE](LICENSE). Provided as-is, for authorized research on your own
vehicle. Not affiliated with Zeekr, Geely, or ECARX.
