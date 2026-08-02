# Signing

`dev-signing.jks` is committed on purpose, and its password is in
`app/build.gradle.kts` in plain text. That is a deliberate trade-off, not an
oversight.

## Why a committed key

Android refuses to install an update whose signature differs from the installed
app. CI previously generated a fresh debug key on every run, so every build had
a different signature and every update meant uninstall-and-reinstall — which
threw away the user's collected servers, latency results and settings. A
constant signature makes updates seamless.

## What it costs

Anyone can build an APK signed with this key. That does **not** let them touch
an installed app remotely: to exploit it they would have to persuade you to
install their APK, and at that point they have already won. What the committed
key removes is one speed bump — such an APK could replace the installed app in
place instead of requiring an uninstall first.

For a personal build this is a reasonable trade. For anything you distribute to
other people, it is not.

## Moving to a private key

No code change is needed. Generate a key, keep it off the repo, and add four
repository secrets — CI prefers them over the committed one automatically.

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias gozar \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Gozar, O=Gozar, C=IR"
```

Then in **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `gozar` |
| `KEY_PASSWORD` | the key password |

The first build after that changes the signature once, so it needs a single
uninstall-and-reinstall. Every build after it updates cleanly.

## Current development key

```
Alias:  gozar
SHA256: 28:BA:E9:45:68:CB:36:4F:AA:99:D3:FE:7A:0E:B9:CB:
        EE:4F:62:A7:A1:4D:5B:3A:B4:EE:06:A5:9D:21:26:3B
```
