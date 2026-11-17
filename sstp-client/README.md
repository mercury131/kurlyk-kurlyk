# SecureGate VPN — Android SSTP client

Native Android VPN client for the securegate SSTP server (works with any MS-SSTP server that uses
MS-CHAPv2, e.g. Windows RRAS, SoftEther, accel-ppp).

* TLS (system trust store; optional "allow untrusted certificate" for self-signed test servers)
* MS-SSTP: SSTP_DUPLEX_POST, CALL_CONNECT_REQUEST/ACK, CALL_CONNECTED with crypto binding
  (SHA-256 certificate hash + compound MAC from MS-CHAPv2 MPPE keys), echo, disconnect
* PPP: LCP, MS-CHAPv2 (with server authenticator check), IPCP (IP + DNS from the server)
* Android VpnService: all traffic or only the VPN subnet, IPv6 leak blocking in full-tunnel mode,
  DNS from the server, auto-reconnect with backoff, foreground notification with "Disconnect",
  always-on VPN support, credentials stored with EncryptedSharedPreferences

## Use

Server: your `DOMAIN` (port 443 by default, `domain:port` for another port), user/password from
`SSTP_USERS` in the server `.env`. Tap **Connect** and allow the VPN request.

## Build

```bash
gradle :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest      # MD4 / MS-CHAPv2 / MPPE test vectors (RFC 1320, 2759, 3079)
# live end-to-end test against a server (connect + ping through the tunnel):
SSTP_TEST_HOST=1.2.3.4 SSTP_TEST_USER=keenetic SSTP_TEST_PASS=... gradle :app:testDebugUnitTest
```

Requires JDK 17 and Android SDK (compileSdk 36.1). The protocol core (`core/`) is plain Kotlin/JVM
without Android dependencies.
