# Security

Use A4 TV Bridge with TVs you own or administer. ADB gives the controller Android shell access. Keep debugging on a trusted network, approve connections you recognize, and turn off debugging or revoke authorizations when you finish. Legacy network ADB can use an unencrypted transport. Wireless debugging with pairing uses TLS when the TV supports it.

The app keeps its ADB identity and TV settings in app storage. Device search checks for ADB services after you ask it to find TVs. Commands, transfers, and screen captures travel directly between the controller and TV. The optional one-click installer contacts GitHub and checks each APK’s published SHA-256 digest and expected package ID before sending it to the TV.

## Report a vulnerability

If GitHub shows **Security → Report a vulnerability**, use it to send a private report. Otherwise, send the developer a private message on X. Include the app version, Android version, TV model, and steps to reproduce. Remove credentials, pairing codes, private network addresses, and screen contents from any logs. [Developer on X](https://x.com/ratherasif04)

## Verify your download

The official APK uses Android package `dev.a4tvbridge.app`. Its signing certificate SHA-256 fingerprint is `8fd58d84f75ecd32dda172abfd66c3d97e5cc73b9cb92c2a6a50de83b4f264b3`.

Download the APK and checksum from the same release. Verify the file checksum and compare the printed signing fingerprint with the value above.

```sh
sha256sum -c A4-TV-Bridge-1.0.0.sha256
apksigner verify --print-certs A4-TV-Bridge-1.0.0.apk
```
