# MCON2CODM

Spoof any paired Bluetooth controller (OhSnap MCON and friends) as a genuine
**Xbox Wireless Controller (Model 1708)** so games like Call of Duty: Mobile
accept it as a native gamepad.

Runs on stock, unrooted Android 14+ by pairing itself with the device's own
Wireless Debugging — no Shizuku, no root, no external tooling. A tiny native
binary inside the APK takes care of the actual HID bridging via `/dev/uhid`.

---

## Install

Grab the signed APK from the [latest release](../../releases) and install it.

One-time setup inside the app:

1. Developer Options → Wireless Debugging → turn it on
2. Tap "Pair device with pairing code"
3. Switch back to MCON2CODM, enter the 6-digit code, tap **Pair**

From then on, just launch the app, tap your controller, tap **Spoof**.

---

## How it works

| Piece | Role |
|------|------|
| Native `mcon_bridge` (`src/mcon_bridge.c`) | Opens `/dev/uhid`, creates a virtual Xbox controller, exclusively grabs the real controller's `/dev/input/eventX`, and forwards inputs. |
| Jetpack Compose UI | Device picker, onboarding, and a foreground service to keep the bridge alive in the background. |
| libadb-android + org.conscrypt | Lets the APK act as its own ADB client — pairs with Wireless Debugging over the loopback interface, gets shell-level access without external tools. |

---

## Build

```
./gradlew assembleDebug
```

Requires Android SDK API 35 + NDK 27.0.12077973. The NDK is used to compile
the native bridge binary; see `src/mcon_bridge.c`.

Release builds are produced by the GitHub Actions workflow (tag `v*` to cut a
release). Three repo secrets feed the signing step:

- `KEYSTORE_BASE64` — base64-encoded keystore
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

---

## License

Personal project, all rights reserved.
