# Native cores

This directory holds the two proxy cores the app links against. They are **not**
committed — each is tens of megabytes of prebuilt native code.

| File | Source | How it gets here |
|---|---|---|
| `libbox.aar` | [SagerNet/sing-box](https://github.com/SagerNet/sing-box) `v1.13.15` | compiled from source with gomobile |
| `libv2ray.aar` | [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) `v26.7.31` | downloaded from the GitHub release |

CI does both automatically (`.github/workflows/build.yml`). To populate them by
hand:

```bash
./gradlew :app:fetchCores
```

That fetches `libv2ray.aar`. `libbox.aar` has no official prebuilt release and
must be compiled (needs Go 1.24+ and the Android NDK):

```bash
git clone --depth 1 -b v1.13.15 https://github.com/SagerNet/sing-box /tmp/sing-box
cd /tmp/sing-box && make lib_install && export PATH="$PATH:$(go env GOPATH)/bin" && make lib_android
cp libbox.aar "$OLDPWD/app/libs/libbox.aar"
```

## Version pinning

`libbox`'s `PlatformInterface` is an explicitly unstable API and changes shape
between sing-box minor versions. `PlatformInterfaceImpl.kt` implements exactly
the `v1.13.15` interface. Changing `SING_BOX_VERSION` in the workflow without
updating that file will fail to compile.
