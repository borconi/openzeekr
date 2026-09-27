# Security Policy

OpenZeekr is a reverse-engineering research project that handles vehicle digital
keys, account credentials, and several cloud authentication schemes. Security is
central to it, so this document explains how secrets are handled, what to watch
for when contributing, and how to report a problem.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Report privately through GitHub's [private vulnerability
reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository (Security tab → _Report a vulnerability_). If that is
unavailable, contact the maintainer through the profile listed on the repository.

When reporting, please include:

- what the issue is and where in the code it lives,
- how to reproduce it,
- the potential impact (e.g. secret disclosure, key exfiltration, auth bypass).

This is a best-effort, non-commercial project with no SLA, but security reports
are taken seriously and prioritised over feature work.

## What counts as sensitive

The high-value assets in this project are:

- **The six extracted app secrets** (per region): the HMAC access/secret keys,
  the TSP signing secret (`prod_secret`), the VIN AES key/iv, and the RSA
  password public key.
- **The overseas-app HMAC AK/SK** and the **inbox HS256 secret**.
- **Account credentials and tokens**: email/password, the TSP bearer, the Azure
  token, and the xchanger/DK session tokens.
- **The BLE digital key material** and the derived session keys.
- **The release keystore** and the **log-decrypt private key**.

## How secrets are handled

The design goal is that **a clean clone contains no real secrets** and still
builds — it just starts unconfigured.

- **No secret is hardcoded in source.** Real values come from a gitignored
  `secrets.properties` at build time.
- The genuinely-secret values are injected into a gitignored C header
  (`core/src/main/cpp/secrets_generated.h`) and compiled into the native lib
  `libozsecrets.so`, rather than into `BuildConfig`/DEX strings. Only the RSA
  `password_public_key` — a _public_ key — stays in `BuildConfig`.
- At runtime, secrets live only in `EncryptedSharedPreferences`, or are
  imported/exported as JSON by the user.
- Release signing reads a gitignored `keystore.properties`; builds are unsigned
  if it is absent.
- FCM uses a gitignored `secrets_firebase.xml` (client config from the stock
  app); absent means push is simply disabled.

Every secret file has a committed `.example` template. The following are
gitignored and must **never** be committed:

- `secrets.properties`, `secrets.json`, `zeekr_secrets.json`
- `core/src/main/cpp/secrets_generated.h`
- `core/src/main/res/values/secrets_firebase.xml`
- `keystore.properties`, `*.jks`, `*.keystore`
- `tools/log-decrypt/private_key.pem`

`trufflehog` runs via Trunk (locally and in CI) to catch accidental secret
commits, but it is a safety net, not a substitute for care.

Following the upstream community convention, **no decrypted app secrets are
published in this repository.**

## Recurring risk: leaks into logs

This codebase has a history of secrets, tokens, keys and VINs leaking into
diagnostic logs. When you touch anything that logs, treat it as security-relevant:

- Never log raw tokens, secrets, keys, passwords, or unencrypted VINs. Log
  presence (`auth=yes/no`) rather than values.
- Diagnostic logs shared from the app are encrypted (hybrid RSA-4096-OAEP +
  AES-256-GCM) with a shipped public key; the private key stays offline. Do not
  weaken this or add plaintext log export paths.
- Be especially careful in the `net` interceptors, `ConfigStore`, and anything
  that serialises `SecretsConfig`.

## Device / physical-access notes

- The Wear OS companion clones the digital key to the watch; anyone with the
  watch can operate the car. A lost/stolen watch key can be revoked from the
  phone app.
- Only one active cloud session is allowed per account; signing into OpenZeekr
  signs the stock app out and vice versa.

## Supported versions

This is an early-beta project under active development. Only the latest release
(and `master`) receives fixes.
