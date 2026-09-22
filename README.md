# OneTrojan

An Android 11+ single-switch, fail-closed Trojan VPN client.

## Current milestone

- Minimal Android UI with one switch.
- Android Quick Settings tile for one-tap start and stop after initial consent.
- Android VPN permission flow.
- Foreground `VpnService`.
- Full IPv4 (`0.0.0.0/0`) forwarding.
- Full IPv6 (`::/0`) capture and deliberate rejection; IPv6 is never sent to
  the Trojan relay and cannot fall back to the physical network.
- Virtual DNS assignment.
- Fail-closed startup: the TUN forwarding engine is not started until Trojan
  TCP, TCP-DNS, and UDP-DNS probes have all succeeded.
- Explicit state machine that cannot display **Protected** before both the TUN
  interface and proxy engine are ready.
- Strict configuration model requiring a numeric bootstrap server IP, avoiding
  DNS leakage before the tunnel exists.
- Pinned MIT-licensed native tun2socks core built from source for ARM64.
- Dedicated SOCKS5-to-Trojan/TLS relay with TCP and UDP support.
- Chrome/uTLS ClientHello support for servers that enforce `tlsProfile` while
  retaining certificate and SNI verification with no insecure mode.
- DNS is assigned to a virtual VPN address, intercepted locally, and converted
  to DNS-over-TCP inside Trojan; it never falls back to the physical network.
- Trojan TCP, TCP-DNS, and UDP-DNS connectivity probes before the UI reports
  **Protected**.
- Dedicated configuration screen for manual entry and pasted JSON text import.
- JSON subscription/download fields are ignored and are never fetched.
- Encrypted configuration storage backed by Android Keystore.

The client requires a strict Trojan URI containing a numeric bootstrap IP,
for example:

```text
trojan://password@example.com:443?ip=203.0.113.9&sni=example.com&dns=1.1.1.1
```

The configuration screen accepts the common JSON fields `host`, `ip`, `port`,
`peer`, `password`, `dns`, `alpn`, and `tlsProfile`. Paste JSON text to fill the form for
review; saving is a separate action. If `ip` is empty, enter the numeric
server IPv4 manually or use the explicit DNS-resolution button. The app never
accesses a URL found in `data` or another JSON field.

The password and server details are encrypted with an Android Keystore key;
the main screen remains focused on a single switch. On a real phone, add
**Trojan VPN** from the Quick Settings edit screen. Its first tap opens
OneTrojan for Android's VPN consent; later taps start and stop the tunnel
directly.

The app does not support routing rules, bypass lists, insecure TLS, plaintext
DNS, or alternate proxy protocols. If the native engine or Trojan connectivity
is unavailable, the VPN stays up and drops captured traffic.

## Build

```shell
./gradlew test assembleDebug
```

The project uses Android API 30 as its minimum and compiles against API 37.
The checked-in `app/libs/utlsbridge.aar` contains only the ARM64 Chrome/uTLS
bridge used by this Trojan client. Its reproducible Go source is in
`utlsbridge/`.
