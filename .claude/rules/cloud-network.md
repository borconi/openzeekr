---
paths:
  - "core/src/main/java/com/openzeekr/app/net/**"
  - "core/src/main/java/com/openzeekr/app/remote/**"
  - "core/src/main/java/com/openzeekr/app/push/**"
  - "core/src/main/java/com/openzeekr/app/config/**"
---

# Cloud / networking / config

Read `docs/ARCHITECTURE.md` "Cloud (TSP) requests" before changing anything here.

- Interceptor order is part of the protocol: headers first, then `SignInterceptor`. `X-SIGNATURE` covers the headers
  and the canonical (sorted-key) JSON body, which is also what gets sent. Breaking this gives error `079025`.
- User-center, TSP and `/overseas-app` use different auth. Never route one through another's interceptors.
- `ApiClient` uses `encodeDefaults = false`: a model property equal to its default is not sent. Wire-required fields
  have no default. Property names must match the server JSON exactly.
- After a region or base-URL change, call `Deps.onEndpointChanged()`.
- There is no token refresh. `079021` (401) means the account was opened elsewhere, handled by `KickoutInterceptor`.
- Numbers sent to the car are formatted with `Locale.US`.
- Log with `Logx` HTTP areas only. Scrub bodies with `HttpLog.scrub()`; never log tokens, keys or raw VINs.
- Parsing, signing and canonicalization are pure: add a JUnit test (`test-writer` agent) for any change.
