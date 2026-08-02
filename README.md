# گذر — Gozar

<div dir="rtl">

یک کلاینت اندروید متن‌باز برای عبور از فیلترینگ، در حال‌وهوای Hiddify و MahsaNG.
کانفیگ‌ها را خودش از ده‌ها منبع عمومی و کانال تلگرام جمع می‌کند، سرعتشان را
می‌سنجد و سریع‌ترین‌ها را بالا می‌آورد.

## ویژگی‌ها

- **جمع‌آوری خودکار کانفیگ** از ۲۰ منبع اشتراک و ۱۸ کانال تلگرام (همگی در
  ۱۴۰۵/۰۵/۱۱ تست و زنده بودند؛ حدود ۲۶٬۰۰۰ کانفیگ).
- **پشتیبانی از همه‌ی پروتکل‌های رایج**: VLESS (با Reality و XTLS)، VMess،
  Trojan، Shadowsocks، Hysteria2، TUIC، WireGuard/WARP، SOCKS و HTTP.
- **هسته‌ی sing-box** که تونل TUN را می‌سازد و همه‌ی پروتکل‌های بالا را پوشش
  می‌دهد. (چرا فقط یک هسته: بخش «محدودیت‌ها».)
- **مرتب‌سازی سریع‌ترین‌ها بالا**: تست هم‌زمان هزاران سرور و انتخاب خودکار بهترین.
- **مسیریابی هوشمند**: عبور مستقیم سایت‌های ایرانی و شبکه‌ی محلی، مسدودسازی
  اختیاری تبلیغات، تونل بر پایه‌ی برنامه.
- **رابط فارسی راست‌چین** با فونت وزیرمتن، تم روشن/تیره و امکان سوییچ به انگلیسی.
- بدون حساب کاربری، بدون تله‌متری، بدون تبلیغات.

## گرفتن APK

پروژه را روی گیت‌هاب push کنید؛ ورک‌فلوی `Build APK` خودکار اجرا می‌شود و فایل
APK در بخش **Actions → آخرین اجرا → Artifacts → gozar-apk** آماده‌ی دانلود است.

```bash
git init
git add .
git commit -m "Gozar"
git branch -M main
git remote add origin https://github.com/<user>/<repo>.git
git push -u origin main
```

APK با کلید debug امضا می‌شود مگر اینکه secretهای `KEYSTORE_BASE64`،
`KEYSTORE_PASSWORD`، `KEY_ALIAS` و `KEY_PASSWORD` را تعریف کنید.

برای هر معماری یک APK جدا ساخته می‌شود. **اگر نمی‌دانید کدام را بگیرید،
`app-arm64-v8a-release.apk` را بردارید** — تقریباً همه‌ی گوشی‌های امروزی همین
است. حجم هر APK حدود ۳۰ مگابایت است (هسته‌ی sing-box در هر معماری حدود ۶۰
مگابایت کد نیتیو دارد که فشرده می‌شود).

## نکته‌های مهم

- **حداقل اندروید ۸ (API 26).**
- بار اول برنامه خودش منابع را می‌گیرد؛ بسته به شبکه ۱۰ تا ۶۰ ثانیه طول می‌کشد.
- تست سرعت یک probe دست‌دادن TCP است، نه تست پهنای باند: هدفش رتبه‌بندی سریع
  هزاران سرور بدون بالا آوردن هسته است. تأخیر واقعیِ سرورِ متصل جداگانه و از
  داخل تونل اندازه‌گیری می‌شود.
- کانفیگ‌های عمومی طبیعتاً بی‌ثبات‌اند. اگر اتصال نگرفت، «به‌روزرسانی منابع» و
  بعد «تست همه» را بزنید.

</div>

---

## English

An open-source Android censorship-circumvention client in the spirit of Hiddify
and MahsaNG. It collects configs from public aggregators and Telegram channels,
ranks them by latency, and floats the fastest to the top.

### Architecture

```
                  ┌──────────────┐
   TUN device ───▶│   sing-box   │───▶ VLESS / VMess / Trojan / SS /
   (VpnService)   │  (libbox.aar)│     Hysteria2 / TUIC / WireGuard
                  └──────────────┘
```

sing-box owns the TUN device, which the app creates through
`VpnService.Builder` on libbox's behalf. The app's own package is excluded from
the VPN (`addDisallowedApplication`) so its traffic — including the loopback
probe used to measure real tunnel delay — never re-enters the tunnel.

| Layer | Location |
|---|---|
| Share-link parsers | [`parser/ConfigParser.kt`](app/src/main/java/com/gozar/app/parser/ConfigParser.kt) |
| Source list | [`data/Sources.kt`](app/src/main/java/com/gozar/app/data/Sources.kt) |
| Fetching + Telegram scraping | [`net/SourceFetcher.kt`](app/src/main/java/com/gozar/app/net/SourceFetcher.kt) |
| Latency ranking | [`net/LatencyTester.kt`](app/src/main/java/com/gozar/app/net/LatencyTester.kt) |
| Core config generation | [`core/SingBoxConfig.kt`](app/src/main/java/com/gozar/app/core/SingBoxConfig.kt) |
| Tunnel | [`vpn/GozarVpnService.kt`](app/src/main/java/com/gozar/app/vpn/GozarVpnService.kt), [`vpn/PlatformInterfaceImpl.kt`](app/src/main/java/com/gozar/app/vpn/PlatformInterfaceImpl.kt) |
| UI | [`ui/`](app/src/main/java/com/gozar/app/ui) |

### Building locally

Requires **JDK 17** (sing-box's libbox build rejects anything else), the Android
SDK (platform 35, build-tools 35), Go 1.24+ and **NDK r28 or newer**. See
[`app/libs/README.md`](app/libs/README.md) for how to produce the core AAR,
then:

```bash
./gradlew :app:assembleRelease
```

### What has been verified

The project has not been compiled (no Android SDK on the authoring machine), so
the first CI run is the compiler. These pieces were checked directly:

- **libbox binding** — every signature `PlatformInterfaceImpl.kt`,
  `GozarVpnService.kt` and `BoxAdapters.kt` call was dumped from a real
  `libbox.aar` with `javap` and matched one-for-one: all 15 `PlatformInterface`
  methods, `TunOptions.getMTU()`, `RoutePrefix.address()`/`prefix()`,
  `StringBox.getValue()`, `SetupOptions.setLogMaxLines(long)`, the
  `NetworkInterface` setters, and `CommandServerHandler`. The gomobile naming
  rule matters here — `getMTU()` and `getDNSServerAddress()` do not map onto
  Kotlin property syntax, which is why the code calls the methods explicitly.
- **ABI coverage** — the core ships arm64-v8a, armeabi-v7a, x86 and x86_64, and
  declares a lower `minSdk` (23) than the app's 26.
- **Config sources** — all 38 shipped sources were fetched over the network and
  returned live configs.
- **Link extraction** — the `URI_PATTERN` literal was run against real
  subscription and Telegram payloads: 5,783 links from one subscription and 152
  from one channel, with under 0.1% malformed.
- **Resources and CI** — all 17 XML files and the workflow YAML parse.

### Known limitations

- **One core only.** Shipping sing-box *and* Xray in one APK is not possible.
  Every gomobile-generated AAR bundles its own copy of the unnamespaced `go.*`
  runtime, so the two collide with `Duplicate class go.Seq` at merge time — and
  each copy binds `go.Seq` to a different native library (`libbox.so` vs
  `libgojni.so`), so merging the classes by hand would leave one core's JNI
  unregistered at runtime. sing-box is the one kept because it is a strict
  superset here: everything Xray would contribute (VMess, VLESS, Reality,
  Trojan, Shadowsocks) plus Hysteria2, TUIC and WireGuard, which Xray lacks.

- Traffic counters read `TrafficStats` for the app's own UID. Because the app is
  excluded from its own tunnel, that UID carries the tunnel's upstream sockets —
  a close approximation of tunnel throughput, not an exact byte count.
- Iranian routing uses embedded domain and CIDR lists rather than downloadable
  `rule_set` bundles. Remote rule sets would have to be fetched *before* the
  tunnel works, which is exactly what fails on a censored network. The trade-off
  is coverage: the embedded lists cover common services, not every AS.
- Shadowsocks links carrying a `plugin=` parameter (obfs, v2ray-plugin) are
  rejected rather than imported, since the generated configs would silently drop
  the plugin and never connect.
- SSR is not supported by either core and is skipped during import.

### Signing

Release builds are signed with the committed development key in `signing/`, so
that updates install over an existing app instead of forcing an uninstall that
would discard the user's servers and settings. CI prefers a private keystore
whenever the `KEYSTORE_BASE64` secret is set. The trade-off, and how to switch,
is written up in [`signing/README.md`](signing/README.md).

### Licence

GPL-3.0, inherited from sing-box.

The bundled Vazirmatn font is © the Vazirmatn Project Authors, licensed under
the SIL Open Font License 1.1 — see [`licenses/Vazirmatn-OFL.txt`](licenses/Vazirmatn-OFL.txt).
