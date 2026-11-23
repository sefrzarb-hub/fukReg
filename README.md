# FukReg

Use your iPhone for the second SIM in your Android phone: calls, SMS/OTP and contacts over encrypted Bluetooth LE. No server is involved.

## Layout
- `android/` – Kotlin app (the "host" phone that has the SIM)
- `ios/` – SwiftUI app (the "client" phone in your hand)
- `docs/PROTOCOL.md` – wire protocol shared by both apps
- `.github/workflows/ios.yml` – builds the iOS app on a cloud macOS runner

## Phases
1. QR pairing, encrypted BLE link, contacts sync  ← in progress
2. SMS & OTP: receive, history, reply, SIM choice
3. Calls via CallKit + audio
4. Polish

UI languages: English + Persian (RTL).
