# Native core

This directory holds the proxy core the app links against. It is **not**
committed — it is ~90 MB of prebuilt native code.

| File | Source | How it gets here |
|---|---|---|
| `libbox.aar` | [SagerNet/sing-box](https://github.com/SagerNet/sing-box) `v1.13.15` | compiled from source with gomobile |

CI does this automatically (`.github/workflows/build.yml`). To build it by hand
you need Go 1.24+, **JDK 17 exactly**, and Android NDK r28 or newer:

```bash
git clone --depth 1 -b v1.13.15 https://github.com/SagerNet/sing-box /tmp/sing-box
cd /tmp/sing-box && make lib_install && export PATH="$PATH:$(go env GOPATH)/bin" && make lib_android
cp libbox.aar "$OLDPWD/app/libs/libbox.aar"
```

Both of those toolchain constraints are real and non-obvious:

- `cmd/internal/build_libbox` does a literal `strings.Contains(javaVersion,
  "openjdk 17")` and aborts on any other JDK.
- sing-box links a prebuilt `libcronet.a` (via `with_naive_outbound`) whose
  relocations NDK r27's `ld.lld` rejects with `unknown reloc`.

## Only one gomobile AAR may be present

Every gomobile-generated AAR bundles its own copy of the unnamespaced `go.*`
runtime and binds `go.Seq` to its own native library. Dropping a second one in
here (Xray's `libv2ray.aar`, for instance) fails the build with
`Duplicate class go.Seq`, and merging the classes by hand would still leave one
core's JNI unregistered at runtime. This is why the app ships sing-box only.

## Version pinning

`libbox`'s `PlatformInterface` is an explicitly unstable API that changes shape
between sing-box minor versions. `PlatformInterfaceImpl.kt` implements exactly
the `v1.13.15` interface. Changing `SING_BOX_VERSION` in the workflow without
updating that file will fail to compile.
