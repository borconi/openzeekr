# Changelog

All notable changes to OpenZeekr are recorded here. OpenZeekr is a free, non-commercial
clean-room app and is not affiliated with Zeekr.

## [0.2.1] - 2026-10-09

### Fixed
- **Digital Key works again on current phones - and repairs keys the old version stranded.**
  Zeekr recently dropped some older phones (including the Pixel 6a) from the list of models allowed
  to hold a Digital Key on the 7GT. Any key OpenZeekr had created under the old phone identity was
  left stuck on Zeekr's servers in a half-created state that could never activate and that retrying
  could not fix. OpenZeekr now presents a currently-supported phone identity, so Digital Keys
  provision normally again.
- **Automatic broken-key repair.** When setup finds one of these stranded keys bound to your device,
  OpenZeekr now deletes it and creates a fresh one automatically - instead of trying, and failing, to
  reuse it. No manual delete-and-retry needed. Repair only ever touches a stuck key that belongs to
  your own device.
- **Digital Key setup no longer fails after a server rate-limit.** If Zeekr's gateway throttled the
  key-creation request while the key was in fact created, setup used to stop with an error. It now
  recognizes that the key already exists and carries on.
- **Software updates on shared accounts.** Checking for and installing updates is an owner-only
  action on Zeekr's side. Shared (non-owner) accounts used to see a confusing "Current version:
  Unknown"; the Updates tab is now hidden for shared accounts, with a clear "managed by the car's
  owner account" message as a safety net.
- **Session expiry is handled cleanly.** When your sign-in expires, OpenZeekr now drops you to the
  sign-in screen with "Session expired - please sign in again" instead of silently retrying with a
  dead token.

### Changed
- **Reported device identity updated to a modern Pixel (Pixel 7 / Android 16).** This is only what
  Zeekr's backend sees when checking Digital Key eligibility and looking up calibration; it changes
  nothing about your actual phone.

### Developer tooling
- A dev-only (developer mode) **Cancel update** button to abort an update assignment that froze
  mid-install and is blocking remote control. The request is validated live, but a deeply stuck
  assignment may still need Zeekr or a dealer to clear it server-side.

## [0.2] - 2026-10-02

### Added
- **Software updates - the full flow.** The Updates tab now drives your car's software update end to
  end, like the official app: it shows the available version and release notes, follows the car's
  **download progress live** (with a percentage), and lets you **Install now** or **Schedule install**
  for a later time. While an install is scheduled you can still install it now or pick a different time.
- **Live update status.** Once an update is downloading or installing, the tab tracks the whole
  lifecycle on its own - Downloading, Ready to install, Vehicle self-check, Installing, Finalizing,
  Installed - updating in real time from the car's own status pushes, with no need to tap Refresh.

### Changed
- The Updates tab loads the current update status automatically when you open it.

### Developer tooling
- Cross-platform `verify` Gradle tasks (build + test + lint), a baselined Android Lint gate, an
  `.editorconfig`, and a toolchain preflight that fails early with an actionable message on a wrong
  setup. Thanks to Erik Günther. (Lint runs under `verify`/`check`, not the normal app build.)

## [0.1.9] - 2026-10-01

### Added
- **Live speed on the hero card.** When the car is being driven, the home card shows the
  current speed; while it's parked you get the normal card. Updates refresh faster while
  driving (and only while the app is open).
- **Software updates tab (experimental).** A new "Updates" tab checks whether your car has a
  new software version available. It's under development - it can only check for now, not
  install - and on most setups the check needs credentials it won't have, so it may be
  unavailable.

### Fixed
- **Crash on startup (introduced in 0.1.8).** On setups without the optional inbox
  credentials (which most people don't have), the app could crash right after the main
  screen. Those overseas-app features are now simply unavailable when the keys aren't set,
  instead of taking the app down. Thanks to Fredrik for the report and diagnosis.
- **Approach unlock** now triggers when you reach the car even if your phone had already
  connected to it as you walked up. Previously it only unlocked if the key was still linking
  at the moment you arrived, so walking up to an already-connected car often did nothing. It
  still unlocks only once per approach and re-arms after you walk away.
- **Rear seat ventilation** buttons no longer show on cars that don't have the feature (e.g. a
  7GT with heated-only rear seats). The Climate tab now follows the car's actual fitted
  ventilation zones instead of a generic four-seat layout. Thanks to Atarinside (issue #20).

### Changed
- **Walk-away auto-lock** now waits until you've clearly left the car before locking - it was
  locking too soon, as little as a step back from the door. The unlock and lock points now use
  one consistent gap (calibrated or not).
- **Calibration** step 2 now says "the driver's side" instead of "to the left", so it's correct
  on right-hand-drive cars (left for LHD, right for RHD).
- **Bottom navigation** adapts to the screen: it keeps labels where there's room and falls back
  to clean icons on smaller / large-display screens, so the tabs never crowd.

## [0.1.8] - 2026-09-30

### Added
- **Approach unlock & walk-away lock.** The car can unlock as you walk up to it and lock
  itself as you walk away, over the BLE digital key. Because every phone-and-car pair is
  different, this is set up with a quick, one-time **"Calibrate at your car"** walk in the
  **Key** tab: you stand at four spots around the car once and OpenZeekr learns your phone's
  real signal at the door and at ~6 m. Approach unlock stays off until you've calibrated, and
  the sensitivity (very close / close / far) is then based on your measured distances instead
  of one-size-fits-all guesses. The digital key itself still sets up from your account with no
  car present - only approach unlock needs the at-car walk.

### Changed
- **Bottom navigation** now shows every tab at once on any phone - tabs are no longer pushed
  off the edge of the screen where they can't be found.
- **Scaling on any phone.** The home dashboard, stat row and control tiles now fit correctly
  on phones with a large display size or font size, instead of wrapping or clipping (e.g.
  "Locked" or "578 km" splitting across lines).
- The **donate link** in Settings now opens when tapped.
- Passive-entry setup and proximity calibration are consolidated in the **Key** tab.

### Fixed
- **Paint colour.** Cars (for example the 7GT) no longer show the wrong colour - the real
  exterior paint is read from your account and is matched even when the colour name comes back
  localised.
- **Car name.** The top bar now shows your car's real name or nickname (including a name you
  set in the official app) instead of an internal model code.
- **Charging speed.** Three-phase AC charging now reports the correct power (about 11 kW)
  rather than a third of it.
- **Windows tile** now uses a car-window icon instead of the Windows logo.
- **Notifications badge.** The unread count now goes down as you read messages or tap
  "mark all read" - previously it could stay stuck at the total.
- **Login.** A clearer message when an account can't be found in the selected region, instead
  of a bare error code.

### Digital key
- Fixed the constant Bluetooth **pairing-request buzz** some phones got near the car. Pairing
  is now used only briefly during calibration and is cleared straight afterwards.
- Self-calibration now completes under OpenZeekr's own key - the groundwork that makes the new
  at-car approach-unlock calibration possible.

## [0.1.7] - 2026-09-24

### Added
- **Multi-car switcher.** Switch between every car on your account (owned and shared)
  from the car-name dropdown in the top bar. The hero card, live status, capabilities
  and remote controls all follow the selected car. Cars can be renamed individually.
- **Accept shared cars in-app.** When someone shares a car with you, OpenZeekr now shows
  an Accept / Decline prompt (with the owner, model, granted access and expiry) so you no
  longer need the official app to accept. There is also a "Check for shared cars" button
  in Settings.
- **Fridge / cool-box control.** On/off plus a target-temperature sheet, for cars that
  have the powered fridge.
- **Sun-shield control.** Rear sunshade open/close (e.g. on the 7X), matching the stock
  app's "Sun-shield".
- **Vehicle stats card.** Odometer, distance and time until the next service, and the
  12 V auxiliary battery voltage (shown amber when low).

### Changed
- The charge tile is now labelled **"Charge & more"** so it's clear it opens all the
  charge settings (charge limit, scheduled charging, battery pre-conditioning, and
  port open/close), not just the port.
- The quick-action grid reflows into clean rows of four - controls a car doesn't have no
  longer leave a hole, and a short final row is centred.
- Each car now renders with the correct model artwork and colour for its VIN.

### Fixed
- The hero card now shows the correct car when switching vehicles - previously it could
  keep rendering the first car's model and colour for every car on the account.
- The fridge tile is no longer shown on cars that only send fridge *alarms* but have no
  remote fridge control (a false positive).
- Sunroof and sun-shield controls now appear only when the car actually supports them.
- When a shared car's access ends, it is removed from the switcher and the top bar no
  longer stays stuck showing the removed car's name and VIN.

### Digital key
- Removed the experimental pairing frame introduced in 0.1.6 that could corrupt the car's
  stored key and break entry/start. Real pairing data is device-native and cannot be
  faked, so OpenZeekr no longer sends it.
- Fixed key-slot exhaustion: removing and re-sharing a key now frees the slot correctly.

## [0.1.6] - 2026-09-23
- SEA / Australia login support, logout-then-relogin fix, and gzip response handling.

## [0.1.5] - 2026-09-23
- Capture and share the login logs from the setup screen; fix the setup spinner buttons.

## [0.1.4] - 2026-09-22
- Share the log as a file, shared-account key minting, handshake-drop fix, 16 KB alignment.

## [0.1.3] - 2026-09-22
- Confirmed self-healing lock/unlock, handshake and scan fixes, security hardening.

## [0.1.2] - 2026-09-19
- Early beta.
