# Architecture

How OpenZeekr fits together, and the invariants that break things **silently**
when you change them. Read the relevant section before touching that area. For
conventions and rules, see [AGENTS.md](../AGENTS.md). For setup, see
[DEVELOPMENT.md](DEVELOPMENT.md).

Paths are relative to `core/src/main/java/com/openzeekr/app/` unless they start
with a module name.

## Modules

```text
:app  (phone UI, Compose)  ─┐
                            ├─> :core  (all logic: BLE digital key, cloud, config, native secrets)
:wear (Wear OS companion)  ─┘
```

- `:core` has the namespace `com.openzeekr.core` but the **Kotlin package**
  `com.openzeekr.app`. BuildConfig is therefore `com.openzeekr.core.BuildConfig`,
  and component names in `core/src/main/AndroidManifest.xml` are fully qualified.
- `:app` and `:wear` share `applicationId = "com.openzeekr.app"` and the same
  signing key. That's what lets the Wear Data Layer deliver between them.
- SDK levels:

  | Module  | minSdk | compile/target SDK |
  | ------- | ------ | ------------------ |
  | `:app`  | 26     | 34                 |
  | `:core` | 26     | 34                 |
  | `:wear` | 30     | 34                 |

## Startup

1. `app/.../App.kt` `onCreate` builds `Deps(this)`, the single manual DI
   container.
2. **Foreground tracking:** a started-activity counter starts
   `vehicleState.start()` plus a 30 s `AccountLogin.heartbeat()` loop while the
   app is in the foreground, and stops both in the background. These stay
   deliberately out of the foreground service.
3. `Deps` init order matters:
   - `ConfigStore` sets the `Logx` toggles.
   - `ApiClient` is a singleton.
   - Then the repositories, then `DkBleManager`.
   - `DkIdentity` copies its `deviceId` into `config.deviceIdentifier`. This is
     one id for TSP and DK, as in the stock app. It also arms BLE if a credential
     exists.
   - Then `DkProvisioning`, `DkLockController` and `ProximityController`.
     `ProximityController` receives `cloudLock` and `cloudIsLocked` lambdas.
4. `app/.../ui/AppRoot.kt` gates the UI:
   - `!onboardingDone` → `OnboardingScreen`.
   - Otherwise the tab is: not logged in (`accessToken` blank) → Settings; not
     provisioned → Key (`SetupScreen`); otherwise → Vehicle.
5. `ui/AppBootstrap.kt` requests the runtime permissions. It starts
   `ble/ProximityService` (foreground service, `connectedDevice`) only when the
   user is logged in, provisioned, and `BLUETOOTH_CONNECT` is granted.

## Cloud (TSP) requests

There are three backends. Each has its own authentication:

| Backend                                         | Client                                       | Auth                                                            |
| ----------------------------------------------- | -------------------------------------------- | --------------------------------------------------------------- |
| User center (login, user info)                  | `AccountLogin.ucClient`                      | `X-HMAC-*` (hmac access/secret key)                             |
| TSP gateway (vehicle, control, DK provisioning) | `net/ApiClient` (+ `AccountLogin.tspClient`) | `authorization: <bearer>` (no "Bearer " prefix) + `X-SIGNATURE` |
| Overseas-app / inbox (`/overseas-app*` paths)   | `net/ApiClient`                              | `OverseasSign` HMAC + `Authorization: <azureToken>`             |

`ApiClient` interceptor order (in `net/ApiClient.kt`):

1. `KickoutInterceptor`
2. `HeaderInterceptor`
3. `SignInterceptor`
4. `OverseasAppAuthInterceptor`
5. The log-level gate
6. `HttpLog`

Invariants:

- **Headers go on before signing.** `SignInterceptor` signs the headers. Any
  header added after it is unsigned and gets rejected.
- **The canonical JSON body is what gets sent.** For JSON, `SignInterceptor`
  rewrites the body to `Signing.canonicalJson` (sorted keys) and signs those
  exact bytes. The gateway MD5s the body as received. Bypassing this gives
  error `079025`.
- **`/overseas-app*` paths** skip the TSP interceptors. User-center calls must
  never go through the TSP interceptors.
- **`x-vin`** is AES-CBC encrypted (`VinCrypto`). It's sent only when the VIN,
  `vinKey` and `vinIv` are all set.
- **Serialization:** `ApiClient`'s JSON uses `encodeDefaults = false`, so a
  property equal to its default is **not sent**. Fields the server requires
  must have no default (see the note in `net/model/Models.kt`). Property names
  must match the server JSON exactly.
- **Success** is `code == "000000"` (or `success == true`). Some ECARX
  endpoints use `code == 1000/200` (`Models.kt`).
- **Session end:** HTTP 401 with `079021` means the account was opened on
  another device. `KickoutInterceptor` clears the token and raises
  `SessionSignal.loggedInElsewhere`. There is **no token refresh**; any other
  401 surfaces as a plain error.
- **Region:** changing it (`ConfigStore.setRegion`) swaps hosts, project id and
  signing secrets. Callers must then call `Deps.onEndpointChanged()` to rebuild
  Retrofit.
- **Gzip:** `AccountLogin` sets `Accept-Encoding: gzip` like the stock app, which
  turns off OkHttp's transparent decompression. So `execRoot` gunzips by hand.
  The SEA gateway gzips everything.

### Login sequence (`net/AccountLogin.login`)

1. `checkUserV2`. This is a canary: it validates the HMAC before any password is
   sent, so a signing bug can't lock the account.
2. `loginByEmailEncrypt` with an RSA-encrypted password. Returns `azureToken`.
3. `user/info`.
4. `tspCode`, then:
   - 4b. The xchanger DK session. Without it, the car refuses our key with
     `0x1010`.
   - 4c. Equipment registration.
5. `bearer_login` returns `accessToken`. `loginDeviceId` **must** use the stock
   format `brand-model-sdk-release`. The `userId` comes from the bearer JWT.
6. The vehicle list populates `vehicles`, `vin` and `isOwner`.
7. `app/hb` heartbeat.

### Remote commands

1. The `remote/Command` enum defines each command (serviceId, command, params).
2. `remote/VehicleControl.send` tries BLE first: only when a BLE byte is mapped,
   there are no extra params, and the session is `SESSION_READY`. It falls back
   to the cloud on any result that isn't `CONFIRMED`.
3. `RemoteControlRepository.send` needs a VIN and sends a heartbeat first. It
   then routes by command:
   - charge commands (`RCS`) → `sendChargeControl`
   - "system B" commands → `ecarxControl`
   - everything else → `sendControl`
4. All repository calls return `CallResult`.

## Digital key (BLE)

The car accepts **one** BLE peer at a time. On the phone,
`ble/ProximityService` is the **only** owner of that link.

1. **Provisioning** (`ble/DkProvisioning`) runs CERT → KEY_LIST → BIND →
   KEY_INFO → DONE:
   - It creates an app certificate from our CSR, then either creates an owner
     key or re-pushes a shared one, then polls `key-info` until `digitalKey`
     arrives.
   - Signed calls sign `userId + deviceId + vin` with ECDSA-SHA256.
   - The result is stored by `DkIdentity`, a software EC key in encrypted prefs.
     It's bound to the VIN it was provisioned for (`DkCredential.vin`).
2. **Connect** (`ble/DkBleManager`):
   - The scan matches 16-bit `0xFDFD`, manufacturer id `0x06FE`, a name
     containing "zeekr", or the service UUID. The GATT service itself is **not**
     advertised.
   - Then MTU → service discovery → CH1/CH2 notify.
   - State: IDLE, SCANNING, CONNECTING, CONNECTED, SESSION_READY, ERROR.
   - A deliberate `disconnect()` must not auto-retry.
3. **Handshake** (`ble/RealDkSession.establish`):
   1. CONNECT_CONFIRM `0x0101`/`0x0102`. The car answers `0x1012` (already
      authenticated) or `0x1011` (first pair).
   2. Certificate exchange. `DkTrust.requireTrustedAndPinned` validates the
      Geely CA and pins the car certificate per VIN on first use.
   3. Factor and signature.
   4. ECDH, giving the AES-GCM session keys.
   5. The digital key.
   6. The coefficient upload.

   Session keys and the digital key are **never** logged.

4. **Lock/unlock** (`ble/DkLockController`):
   - Sends `0x0110` with UNLOCK `0x01` or LOCK `0x02`, then waits 3 s for
     `0x0111`/`0x0112`.
   - `REJECTED` is final.
   - `NO_RESPONSE` or `WRITE_FAILED` refreshes the session and retries once.
5. **Proximity** (`ble/ProximityController`):
   - Converts RSSI to distance. NEAR/FAR boundary 6 m, 5 s action cooldown.
   - Walk-away lock goes over BLE. On link loss it waits 5 s, then asks the
     cloud whether the car is locked. Unlocked **or unknown** → cloud lock.
     The rule: never leave the car open.
6. **Watch handover** (`wear/WearLinkArbiter`, `app/.../wear/PhoneKeySyncService`,
   `wear/.../PhoneLink`, `KeySync`). Messages use the `/openzeekr/` path prefix.
   1. The watch asks for status.
   2. The watch sends `pause-link`. The phone suspends its keep-alive, drops the
      link and replies `link-paused`.
   3. The watch connects to the car itself.
   4. The watch sends `resume-link`.

   The phone reclaims the link automatically after 45 s. The key is cloned via
   `request-key`; `PATH_PURGE` wipes the watch copy.

## Push, status, capabilities

- **Push:**
  - FCM goes `app/.../push/ZeekrFcmService` → `util/CarNotifier`
    (channel `car_alerts`).
  - `push/PushRegistrar` registers the token with the message center.
  - There is no google-services plugin: Firebase auto-initializes from the
    string resources in the gitignored
    `core/src/main/res/values/secrets_firebase.xml`. If that file is missing,
    push is off.
- **Status:** `remote/VehicleStatusHolder` polls every 20 s while the app is in
  the foreground. `refreshAfterCommand()` does a short burst of polls.
- **Capabilities:** `remote/CapabilityHolder` **fails open** (everything shown)
  until the list loads. Call `reload()` after switching cars.
- **Multi-car:**
  - The active car is `SecretsConfig.vin`, within `vehicles`.
  - `ConfigStore.setActiveVehicle` only accepts known VINs.
  - `reconcileGarage` ignores an empty list, so a failed fetch never wipes the
    garage.

## Secrets pipeline

```text
secrets.properties (gitignored)
  └─ core/build.gradle.kts (configure time)
       ├─ core/src/main/cpp/secrets_generated.h (gitignored, regenerated) ─> libozsecrets.so ─> util/NativeSecrets
       └─ BuildConfig.SEC_PASSWORD_PUBLIC_KEY (public key only)
SecretsConfig.fromBuildDefaults() ─> ConfigStore (EncryptedSharedPreferences) <─ Settings UI / JSON import
```

- A clean clone builds with everything blank. `NativeSecrets` returns `""` when
  the library isn't loaded, which includes JVM unit tests.
- The JNI names in `ozsecrets.c` (`Java_com_openzeekr_app_util_NativeSecrets_*`)
  must match the `external fun` declarations in `util/NativeSecrets.kt`.
  **Renaming or moving the class breaks it at runtime**, not at build time.
- 16 KB page alignment for Android 15+ lives in `core/src/main/cpp/CMakeLists.txt`.

## Release and R8

- Release builds use `isMinifyEnabled` and `isShrinkResources`. They're signed
  from the gitignored `keystore.properties`; without it they build unsigned.
- `:core` ships **no consumer ProGuard rules**. Keep rules live in
  **both** `app/proguard-rules.pro` and `wear/proguard-rules.pro`:
  - kotlinx.serialization and Retrofit
  - BouncyCastle
  - Tink and `androidx.security.crypto` (without these,
    EncryptedSharedPreferences breaks on first launch)
  - `NativeSecrets` native methods
  - MapLibre (app only)
- New reflection, serialization or JNI needs rules in both files. Debug builds
  don't use R8, so `verify` won't catch a missing rule. Test with
  `./gradlew assembleRelease`.

## Change checklist: "if you touch X, also check Y"

| If you change…                                               | Also check / it breaks…                                                               |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------- |
| `applicationId`, signing, package of `:wear`                 | Phone and watch must match, or the Data Layer silently delivers nothing               |
| `versionCode`                                                | Watch = phone + 1, in one Play release (see the `/release` skill)                     |
| A class in `net/model`                                       | Wire names; `encodeDefaults = false` drops fields equal to their default              |
| Interceptor order, or headers added in an interceptor        | `X-SIGNATURE` covers the headers and canonical body → `079025`                        |
| `ConfigStore.setRegion` or base URLs                         | Call `Deps.onEndpointChanged()`                                                       |
| `NativeSecrets` name/package, or `ozsecrets.c`               | JNI binding + the R8 keep rules in app and wear                                       |
| A new `@Serializable`, Retrofit interface, reflection or JNI | ProGuard rules in **both** app and wear; test `assembleRelease`                       |
| `core/src/main/AndroidManifest.xml`                          | It merges into **both** apps. The watch removes/overrides some entries (`tools:node`) |
| BLE scan permissions on the watch                            | Keep `neverForLocation` + `tools:node="replace"`, or watch scans return nothing       |
| The DK handshake, `DkTrust`, pins                            | Security review required; never weaken validation to "make it connect"                |
| Walk-away lock or `cloudIsLocked`                            | "Unknown" must still lead to a cloud lock (fail safe = locked)                        |
| `ProximityService` / link ownership                          | One BLE owner; the watch pause/resume protocol and the 45 s auto-resume               |
| Numbers sent to the car                                      | Format with `Locale.US` (the car rejects `21,6`; see `VehicleScreen.kt`)              |
| New log lines                                                | `Logx` area gating; `Logx.preview()`; `HttpLog.scrub()` for bodies                    |
