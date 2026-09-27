# Changelog

All notable changes to OpenZeekr are recorded here. OpenZeekr is a free, non-commercial
clean-room app and is not affiliated with Zeekr.

## [Unreleased]

<!-- Add user-visible changes here under Added / Changed / Fixed / Digital key.
     At release time this heading becomes the new version (see the release procedure). -->

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
- The fridge tile is no longer shown on cars that only send fridge _alarms_ but have no
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
