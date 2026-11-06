# BYD Assistant NG — Song PLUS edition

A modified build of BYD Assistant NG (GPL-3.0, see LICENSE and NOTICE.md) for the BYD Song PLUS DM-i 2025 (DiLink 5.0).

## Changes from upstream
- Car writes go through the car's `autoservice` binder first (setInt, transaction 6), then the SDK's `set()`.
  On DiLink 5.0 the SDK refuses writes from the ADB shell identity, which is why commands answered "failed".
- State read-back falls back to `autoservice` getInt (transaction 5) when the SDK answers "unsupported".
- Climate uses the A/C feature table (AC_*_SET ids, device 1000) first, then the original byd_airconditioning route.
  Fixed: air source, ventilation, compressor, auto, front/rear defrost pointed at state ids instead of SET ids.
- Ambient colour / brightness use SET_INTERIOR_ATMOSPHERE_LAMP_COLOR_SET / _BRIGHTNESS_SET (the Song has no
  setIALColor route): colour is the car's own palette number (1-31), brightness its raw level.
- Removed controls this car does not have (feature reads 65535 or missing): fridge, rear seat heating/ventilation,
  mirror tilt in reverse, headrest sound.
- Feature ids under 2,000,000 are treated as firmware placeholders and never written.
- Own application id `com.bydassistantng.songplus` (installs next to the upstream app), name "BYD Assistant NG (Song PLUS)".

## Build
`./build-voice.sh` (needs Docker). Output: `out/byd-assistant-songplus.apk`. Keep `signing/`.

## 1.2.2
- An accepted write counts as done; the car's status is reported as a note, not as a failure (the Song's states lag
  or use another scale, so a strict check said "failed" for commands that worked).
- Ambient colour by name (pink, purple, blue, cyan, green, yellow, orange, red, warm/cold white...): palette number
  = the Leopard's setIALColor code / 4.

## 1.3.0
- Offline instant car commands (package `quick`): Vosk speech recognition on the head unit (models from
  alphacephei.com/vosk/models, Apache-2.0, downloaded once in Settings → Car: Tunisian Derja 158 MB, French 41 MB,
  English 40 MB) + a phrase parser for English, French and Tunisian Arabic. Without internet the wheel button runs
  one offline command and shows the result on the banner; with internet the first phrase of a conversation is done
  at once and Gemini is told it is already done.
- Scenes (`run_scene`): cool down, warm up, defrost, close everything, air out, climate off.
- `read_car what=status`: windows, doors, sunroof, A/C, seats, battery and ranges in one reading.
- French as an assistant language; Tunisian (Derja) as an Arabic dialect; the prompt knows Tunisian car words.
- Release build without code shrinking (Vosk uses JNA).

## 1.4.0: names
- A new **Names** step in setup, right after the language: the assistant's name, the car's name and your name. All are optional.
- Already set up? Home asks once after the update ("Later" skips it). Change them any time in **Settings → Assistant → Names**.
- The names go into the assistant's instructions: it introduces itself by its name, knows that the car's name means this car, and greets you by name now and then. Names are cleaned (24 characters, no quotes or brackets), so they can't change the instructions.
- The assistant is still started with the steering-wheel button or a tap. It doesn't listen for its name all the time.

## 1.5.0: rebuilt on upstream's DiLink 5.0 work
Upstream solved DiLink 5.0 the same way this edition did, more thoroughly and with tests, so this edition is now
rebased onto it instead of carrying its own version of the same thing.

- **Dropped from here, taken from upstream:** the hand-written `autoservice` route and the climate fallback. Upstream's
  native route, A/C route, permission-refusal reporting and platform detection replace them.
- **Controls are found by name.** Upstream reads the car's own feature table and uses this car's number for each
  control, offering only what the car actually has. The hand-built Song PLUS number list is no longer what drives it.
- **Climate is named too (new here).** Upstream reached the climate through the A/C binder property without a name, and
  its numbers are not a Song PLUS's. Every climate control now carries the car's name for it, a name resolves whichever
  group the firmware files it under, and climate is never withheld when a car does not use the name — it falls back to
  the verified number. Covered by `ClimateNameResolutionTest`.
- **Kept from this edition:** the offline Vosk commands (Tunisian Derja, French, English), the scenes, `read_car`, and
  the names given to the assistant, the car and the driver.
- **Kept from upstream:** Indonesian, the shared-log redaction, and the release-notes tooling.

## 1.6.0: update from your own server
- **Updates from your own server**, not GitHub. Build with
  `-Passistant.updateUrl=https://<your server>/<path>/api/app/voice/releases` and, where the server asks for one,
  `-Passistant.updateToken=<token>`. Left empty, the app behaves exactly as upstream and checks GitHub.
  Downloads stay held to that server's own host, as a GitHub source is held to github.com, so a release list
  cannot send the app to fetch an APK from somewhere else. The token is sent to that host alone.
- **Controls the car names but does not have are withheld.** The fridge, the rear seat heating and the mirror that
  tilts in reverse all read "not fitted" on a Song PLUS; they are no longer offered.
- **A control can be added by name alone**, with no feature number written down.
- **Fixed:** `ac.compressor` named a feature this car does not have (`AC_COMPRESSOR_STATE`); it is `AC_COMPRESSOR_MODE`.
