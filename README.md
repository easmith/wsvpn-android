# WSVPN Android

An Android VPN client for [WSVPN](https://github.com/Doridian/wsvpn) servers.

## Features

- Connect to WSVPN servers over WebSocket (`ws://` and `wss://`)
- htpasswd authentication (HTTP Basic Auth)
- Per-app VPN filtering: choose which apps route traffic through the VPN
- Auto-reconnect on disconnect
- Insecure TLS mode for self-signed certificates

## Server Setup

This client requires a [WSVPN server](https://github.com/Doridian/wsvpn). Configure the server with htpasswd authentication and ensure IP forwarding and NAT are enabled:

```bash
sysctl -w net.ipv4.ip_forward=1
iptables -t nat -A POSTROUTING -s <VPN_SUBNET> ! -d <VPN_SUBNET> -j SNAT --to <SERVER_PUBLIC_IP>
```

## Building

Open the project in Android Studio and build, or use Gradle:

```bash
./gradlew assembleDebug
```

Requires Android SDK 26+ (Android 8.0).
