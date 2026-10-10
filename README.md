# OpenZeekr

[![Latest release](https://img.shields.io/github/v/release/borconi/openzeekr?label=version&sort=semver)](https://github.com/borconi/openzeekr/releases/latest) · [⬇ Download the latest signed APK](https://github.com/borconi/openzeekr/releases/latest) · [Changelog & releases](https://github.com/borconi/openzeekr/releases)

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

This comes with **no support** — but a *lot* of time, tooling and late nights went
into reverse-engineering it and bringing it this far. If it's useful to you, a small
token of appreciation is genuinely welcome (never expected):

**→ [revolut.me/emilimpd](https://revolut.me/emilimpd)**

## What it does

Legend: ✅ working & verified · 🟡 built but **unverified** (may or may not work) · 🔴 not working yet.

| Area | Status |
|------|--------|
| **Account login** (idaas → TSP bearer) | ✅ Working (EU) — logs in and obtains the TSP bearer token |
| **DK BLE digital key — pair + lock/unlock** | ✅ **Working & verified at the car.** Reverse-engineered handshake (cert exchange → ECDH → AES-128-GCM session) + control opcodes `0x110`/`0x111`; locks and unlocks over BLE, no native libs |
| **DK cloud provisioning** (enrol our own keypair → key-info) | ✅ Working — provisions OpenZeekr's own digital key and pairs to the car |
| **Cloud remote control** (lock/unlock, climate, engine, charge, windows, flash/horn, sentry, …) | ✅ **Core verified** — commands accepted (`000000`) via the stock body shape + `X-SIGNATURE`; some commands are vehicle-state gated (e.g. refused at low battery SOC) so remain effectively unverified |
| **Unified quick action** (BLE if a DK session is connected, else cloud) | ✅ Working |
| **Foreground service** (keeps the DK BLE session connected; approach unlock/lock) | ✅ **Working at the car.** Sole owner of the DK link — holds it connected and reconnects on drop; releases it on request so the watch companion can borrow the car's single BLE slot (pause → act → resume), then reclaims it. Screen-off approach uses a hardware-offloaded presence scan (zero-CPU wake) |
| **Proximity unlock / walk-away lock (RSSI)** | ✅ **Working at the car.** Rides the keep-alive session's connected-GATT RSSI with adaptive cadence (2 s idle → 250 ms burst near the threshold) + EMA smoothing; unlocks on approach and locks on walk-away (zone crossings + a link-loss walk-away lock when the session drops as you leave). Auto-retries a failed unlock by resetting the BLE link |
| **Vehicle status** | ✅ **Working (EU)** — single GET (`vehicle/status/latest?latest=false&target=new`), tolerant JSON map (lock/doors/SOC/range/climate/odometer/tyres/location). Handles this platform's quirks: SOC lives in `chargeLevel` (not blank `stateOfCharge`); charging derived from live `chargeIAct×chargeUAct` kW (the `isCharging` flag is wrong) |
| **Sentry footage / live view** | 🔴 **CN-only — not available on EU (or any overseas) gateway.** `sentinel-monitoring-service` is unrouted (gateway 404 `00A01`); our request is byte-identical to stock, so it's a server-side regional gap, not a client bug. **Hidden in the app for now; back-burner** — see below |
| **Remote parking (RPA/RSPA)** | 🔴 **Not working — gated by the car.** The full schema is reverse-engineered and implemented (flow, opcodes, 500 ms dead-man heartbeat, challenge auto-answer, RSSI stream, AES-CMAC frame trailer + ECIES `cmacKey` unwrap). But at the car every request is NAK'd (`0x100a`) even with an owner key, armed on the head unit — the car only authorizes RPA for a **DK 3.0 / Secure-Element key**, which a software key can't be. Schema is available; actuation is not reachable. **Breaktrough** on talking to car, **DK 3.0** might not be needed, but to early to conclude.  |
| **Wear OS companion** (standalone watch app) | ✅ **Working at the car.** Pulls the phone's DK key over the Wear Data Layer (no separate sign-in — same `applicationId` + signing key), then locks/unlocks the car **directly over BLE from the watch**. Arbitrates the car's single BLE peer slot with the phone: stands down while phone Lock-on-approach is on, otherwise runs a pause → act → resume handover so the two never fight for the radio. Hero-style watch face (car render + one-tap lock/unlock) |

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
2. **Proximity unlock *and* walk-away lock actually work — without DK 3.0.** Hands-free
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

OpenZeekr **never** ships any account secret. To talk to the cloud you supply
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

Then put them into the app at runtime:

- **Settings** screen → paste each value, or
- **Import JSON** in the same shape as [`secrets.example.json`](secrets.example.json).

They're stored in `EncryptedSharedPreferences` on-device only.

**Or bake them into a local build.** Copy
[`secrets.properties.example`](secrets.properties.example) to `secrets.properties` in the
repo root (gitignored) and fill in the same values, using the upper-case keys
(`HMAC_ACCESS_KEY`, `PROD_SECRET`, …; prefix `SEA_`/`EM_` for per-region signing sets). They
are compiled into the native library (`libozsecrets.so`; `PASSWORD_PUBLIC_KEY` goes into
`BuildConfig`), so the app starts pre-configured. Blank or missing values leave the app
unconfigured as usual. **An APK built this way contains your keys — don't distribute it.**

```jsonc
// secrets.example.json — fill with YOUR OWN extracted values
{ "hmac_access_key":"", "hmac_secret_key":"", "password_public_key":"",
  "prod_secret":"", "vin_key":"", "vin_iv":"",
  "overseas_access_key":"", "overseas_secret_key":"",
  "email":"", "password":"", "vin":"" }
```

**Optional — notifications inbox.** The message inbox lives on a *different* backend
(the overseas-app Azure gateway) with its own HMAC AK/SK auth. To enable it, supply
`overseas_access_key` + `overseas_secret_key` — the native `getNativeApplicationId()` /
`getNativeSecret()` values for **your own** region, read from your app's `libenv.so`
(class `com.zeekr.env.ServerUtils`; Frida-hook those two methods and use the pair whose
`getNativeHost()` matches your region's gateway). Leave them blank and everything else
works; only the bell/inbox stays off.

> The inbox request (`/overseas-app/...`) is authenticated by the AK/SK X-HMAC signature
> **plus** the login-issued bearer, so the AK/SK pair above is all you need — there is no
> separate client-minted inbox token for AU/SEA. (Verified against stock 1.5.3 on the
> `gateway-pub-hw-em-sg` gateway, 2026-10-09.)

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

```
config/   SecretsConfig + ConfigStore (encrypted, import/export)
net/      Signing (X-SIGNATURE), interceptors, Retrofit TspApi, models
remote/   Command catalog (serviceIds) + repositories (auth, control, sentry)
ble/      DkSession (+placeholder), DkBleManager (GATT scaffold), DkLockController
ble/rpa/  RpaOpcodes + RpaController (heartbeat / challenge / flow)
ui/       Compose screens: Controls, Parking, Key (DK setup), Settings
          (Sentry screen exists but its tab is hidden — see back-burner note)
```

> Note: region, base URL and project-id are EU defaults baked into
> `SecretsConfig`/`ZeekrConst` — non-EU use would need these made configurable.

## Build

Open the folder in **Android Studio (Koala or newer)** and let it sync, or from the CLI:

```bash
./gradlew assembleDebug
```

Create `local.properties` with your SDK path (Android Studio does this for you):

```
sdk.dir=/path/to/Android/Sdk
```

The app is split into two modules: **`:core`** (all BLE/DK/crypto/cloud logic + baked
secrets) and **`:app`** (the Compose UI). The map uses **MapLibre + OpenFreeMap** — free,
no API key, no Google Play Services.

### Car renders (not shipped)

The home screen tints a paint "identity card" and lays the car's white render on top.
Those renders are Zeekr press images, so they're **gitignored** (like the secrets) and
never published. Drop your own transparent white PNG/WebP per model into
`app/src/main/assets/cars/` as `car_{001,x,7x,7gt,9x}.webp`. If absent, the hero simply
shows the coloured card with no car — the app still builds and runs.

## Signing (`X-SIGNATURE`)

`X-SIGNATURE = base64(HMAC(prod_secret, stringToSign))`, where `stringToSign` is:

```
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
2. **Faster BLE reconnect.** Unlock works reliably, but when the DK session has gone
   stale the app currently tears the link down and does a full reconnect/handshake, so
   you can stand at the car waiting several seconds for nothing. Reconnect logic should
   detect the dead session sooner and re-establish (or keep the session warm) so the car
   responds the moment you reach it.

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
