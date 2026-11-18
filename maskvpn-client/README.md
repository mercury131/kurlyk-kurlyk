# maskvpn-client

Android client for the securegate masquerading VPN (the tunnel hidden inside normal HTTPS on :443).
Separate app from the SSTP client (`../sstp-client`).

## What it does
- Add a tunnel by **scanning the QR code** from `./sgctl vpn add <name>` or **importing a `.sgvpn` file**.
- Export a tunnel back to a `.sgvpn` file.
- Connect / disconnect (Android `VpnService`, own TUN), foreground notification, auto-reconnect with backoff.
- Secrets stored in Android Keystore-backed `EncryptedSharedPreferences`.
- "Allow self-signed certificate" switch for testing against a `TLS_MODE=selfsigned` server.

minSdk 28 (ChaCha20-Poly1305). All code is one module (`:app`); the protocol core is under
`app/src/main/java/com/securegate/maskvpn/core` and is unit-tested.

## Structure
- `core/` — protocol: `TunnelConfig` (sgvpn:// / .sgvpn), `Crypto` (keys, proof, HKDF, ChaCha20-Poly1305),
  `MaskVpnSession` (TLS + browser-like WebSocket upgrade + encrypted IPv4 frames).
- `vpn/` — `MaskVpnService` (TUN <-> core), `VpnState`.
- `store/` — `TunnelStore` (encrypted).
- `MainActivity` — Compose UI.

## Build / test
```
# JDK 17 + Android SDK required; local.properties -> sdk.dir
gradle :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest    # crypto vectors, config parsing, + a live test against the server
```
The live test (`MaskVpnLiveTest`) targets the sandbox by default and is skipped if unreachable;
override with `-Dvpn.host=... -Dvpn.sni=... -Dvpn.secret=... -Dvpn.port=443`.
