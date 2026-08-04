# گذر — Gozar

<div dir="rtl">

کلاینت متن‌باز عبور از فیلترینگ برای **اندروید** و **ویندوز**، در حال‌وهوای
Hiddify و MahsaNG. کانفیگ‌ها را خودش از ده‌ها منبع عمومی و کانال تلگرام جمع
می‌کند، سرعتشان را واقعاً می‌سنجد و سریع‌ترین‌ها را بالا می‌آورد.

## دانلود

آخرین نسخه‌ی پایدار: **[صفحه‌ی Releases](../../releases/latest)**

| | فایل | توضیح |
|---|---|---|
| اندروید | `app-arm64-v8a-release.apk` | اگر نمی‌دانید کدام، همین را بگیرید |
| ویندوز | `Gozar-1.0.0.msi` | بدون نیاز به دسترسی مدیر |

هر push روی `main` هم یک بیلد تازه در تگ `latest` می‌گذارد.

## چطور سرور انتخاب می‌کند

این بخش تفاوت اصلی با بقیه است. رتبه‌بندی **سه مرحله** دارد:

| مرحله | روی چند سرور | چه چیزی را ثابت می‌کند |
|---|---|---|
| پینگ TCP | همه (تا ۲۰۰۰) | چیزی روی آن پورت گوش می‌دهد |
| درخواست واقعی از داخل تونل | ۵۰ برتر | پروکسی واقعاً ترافیک رد می‌کند |
| دانلود واقعی | ۸ برتر | چقدر پهنای باند باقی مانده |

مرحله‌ی اول به‌تنهایی گمراه‌کننده است: سرورهایی که در ۴۰ میلی‌ثانیه جواب
دست‌دادن می‌دهند و بعد در TLS شکست می‌خورند، بالاترین رتبه را می‌گرفتند. و
تأخیر هم چیزی درباره‌ی پهنای باند نمی‌گوید — این سرورها رایگان‌اند و بین هزاران
نفر مشترک. برای همین **مبنای نهایی سرعت اندازه‌گیری‌شده است**.

تست سرعت عمداً پشت سر هم اجرا می‌شود، نه موازی: دو دانلود هم‌زمان خط خودِ کاربر
را نصف می‌کنند و هر دو نصف سرعت واقعی خوانده می‌شوند. هر کدام سقف ۱.۵ مگابایت یا
۲.۵ ثانیه دارد.

## ویژگی‌ها

- **۶۲ منبع** (۴۰ اشتراک، ۲۲ کانال تلگرام)، شامل فهرست‌های MahsaNet که
  **به تفکیک اپراتور ایران** تقسیم شده‌اند — گلوگاه معمولاً مسیریابی بین‌المللی
  خودِ اپراتور است.
- **همه‌ی پروتکل‌های رایج**: VLESS (Reality و XTLS)، VMess، Trojan،
  Shadowsocks، Hysteria2، TUIC، WireGuard، Cloudflare WARP، SOCKS و HTTP.
- **سرورهای با ورودی داخلی** جداگانه شناسایی می‌شوند — وقتی اینترنت بین‌المللی
  قطع شود، تنها چیزی‌اند که باقی می‌ماند.
- **سهمیه و انقضای اشتراک‌های خصوصی** از هدر `subscription-userinfo`.
- **انتخاب پروتکل** توسط کاربر.
- **رابط فارسی راست‌چین** با فونت وزیرمتن، به‌علاوه انگلیسی؛ تم روشن و تیره.
- بدون حساب کاربری، بدون تله‌متری، بدون تبلیغات.

## نکته‌های مهم

- **اندروید ۸ (API 26) به بالا.** WARP به اندروید ۱۳ به بالا نیاز دارد، چون
  تولید کلید X25519 از آنجا اضافه شده.
- **ویندوز از پروکسی سیستمی استفاده می‌کند، نه TUN.** TUN روی ویندوز درایور
  Wintun و پنجره‌ی UAC در هر بار اجرا می‌خواهد؛ این روش بدون دسترسی مدیر نصب
  می‌شود و اگر برنامه kill شود مسیریابی سیستم را خراب رها نمی‌کند.
- بار اول منابع را می‌گیرد و می‌سنجد؛ چند دقیقه طول می‌کشد.
- کانفیگ‌های عمومی ذاتاً ناپایدارند.

</div>

---

## English

An open-source censorship-circumvention client for **Android** and **Windows**,
in the spirit of Hiddify and MahsaNG. It collects configs from public
aggregators and Telegram channels, measures what they can actually do, and
floats the fastest to the top.

Download from the [releases page](../../releases/latest). Every push to `main`
also refreshes a rolling `latest` build.

### How servers are ranked

Three stages, because the cheap answer is misleading:

| Stage | Servers | What it proves |
|---|---|---|
| TCP handshake | all, up to 2000 | something is listening on the port |
| Real request through the proxy | best 50 | the proxy actually carries traffic |
| Real download | best 8 | how much bandwidth is left |

Scraped lists are full of servers that answer a handshake in 40 ms and then fail
at TLS or authentication — sorting on that puts the worst entries first. And
delay says nothing about throughput: these are free servers shared by thousands
of people. So the final ranking is built on **measured KB/s**. Hiddify ranks on
`lowest-delay`; this does not.

The download stage runs strictly sequentially. Two at once would split the
user's own line and both would read as half their real speed, turning the
ranking into noise. Each is capped at 1.5 MB or 2.5 s.

### Architecture

```
Android                                   Windows
┌─────────────────┐                       ┌─────────────────┐
│ VpnService TUN  │                       │  system proxy   │
│        ↓        │                       │        ↓        │
│ sing-box        │                       │ sing-box.exe    │
│ (libbox.aar,    │                       │ (child process, │
│  in process)    │                       │  no TUN)        │
└─────────────────┘                       └─────────────────┘
          └──────────── :shared ────────────┘
        parsers · sources · ranking · config
```

| Layer | Location |
|---|---|
| Share-link parsers | [`shared/…/parser/ConfigParser.kt`](shared/src/main/kotlin/com/gozar/app/parser/ConfigParser.kt) |
| Source list | [`shared/…/data/Sources.kt`](shared/src/main/kotlin/com/gozar/app/data/Sources.kt) |
| Ranking rule | [`shared/…/data/Latency.kt`](shared/src/main/kotlin/com/gozar/app/data/Latency.kt) |
| Handshake sweep | [`shared/…/net/Prober.kt`](shared/src/main/kotlin/com/gozar/app/net/Prober.kt) |
| Delay through the proxy | [`shared/…/net/RealDelay.kt`](shared/src/main/kotlin/com/gozar/app/net/RealDelay.kt) |
| Throughput | [`shared/…/net/SpeedTest.kt`](shared/src/main/kotlin/com/gozar/app/net/SpeedTest.kt) |
| Core config generation | [`shared/…/core/SingBoxConfig.kt`](shared/src/main/kotlin/com/gozar/app/core/SingBoxConfig.kt) |
| Android tunnel | [`app/…/vpn/GozarVpnService.kt`](app/src/main/java/com/gozar/app/vpn/GozarVpnService.kt) |
| Windows app | [`desktop/…`](desktop/src/main/kotlin/com/gozar/desktop) |

### Why the app is not excluded from its own tunnel

`addDisallowedApplication` looks like the right way to keep the core's own
dials out of the tunnel it is creating, and it does not hold: with it in place
Android still reported `tun0` as this app's default network, so the core's
outbound connections looped back into the tunnel and died in about 20 ms with
"unexpected end of stream". What actually works is tunnelling everything and
calling `VpnService.protect(fd)` on the core's sockets, plus requesting a
`NOT_VPN` network for DNS and the interface monitor. See
[`PlatformInterfaceImpl.kt`](app/src/main/java/com/gozar/app/vpn/PlatformInterfaceImpl.kt).

### Building

CI builds both platforms on every push; there is no Android SDK on the
authoring machine, so the compiler of record is GitHub Actions.

Locally you need **JDK 17** — sing-box's `build_libbox` hard-checks the java
banner and aborts on anything else — plus the Android SDK (platform 35),
Go 1.24+, and **NDK r28 or newer**, since r27's linker rejects relocations in
the prebuilt `libcronet.a` that sing-box links.

```bash
./gradlew :app:assembleRelease      # Android
./gradlew :desktop:packageReleaseMsi  # Windows, on Windows
```

### Known limitations

- **One proxy core only.** Shipping sing-box *and* Xray in one APK is not
  possible: every gomobile-generated AAR bundles its own copy of the
  unnamespaced `go.*` runtime, so the two collide with `Duplicate class go.Seq`
  at merge time — and each copy binds `go.Seq` to a different native library
  (`libbox.so` vs `libgojni.so`), so merging the classes by hand would leave one
  core's JNI unregistered. sing-box is kept because it is a strict superset
  here.
- **WARP and WireGuard skip the delay and speed stages.** sing-box configures
  them as endpoints rather than outbounds, and the Clash API resolves tags
  through the outbound manager only, so they keep their handshake ranking.
- Iranian routing uses embedded domain and CIDR lists rather than downloadable
  `rule_set` bundles — a remote rule set would have to be fetched *before* the
  tunnel works, which is exactly what fails on a censored network.
- Shadowsocks links carrying `plugin=` are rejected rather than imported, since
  the generated config would silently drop the plugin and never connect.
- SSR is not supported by the core and is skipped on import.

### Signing

Release builds are signed with the committed development key in `signing/`, so
updates install over an existing app instead of forcing an uninstall that would
discard the user's servers and settings. CI prefers a private keystore whenever
the `KEYSTORE_BASE64` secret is set. The trade-off is written up in
[`signing/README.md`](signing/README.md).

### Licence

GPL-3.0, inherited from sing-box — see [`LICENSE`](LICENSE).

The bundled Vazirmatn font is © the Vazirmatn Project Authors under the SIL
Open Font License 1.1 — see [`licenses/Vazirmatn-OFL.txt`](licenses/Vazirmatn-OFL.txt).
