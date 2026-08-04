<div dir="rtl">

## گذر ۱.۰.۰

اولین انتشار پایدار — برای **اندروید** و **ویندوز**.

### دانلود

| | فایل |
|---|---|
| **اندروید** | `app-arm64-v8a-release.apk` — مگر بدانید معماری دیگری لازم دارید |
| **ویندوز** | `Gozar-1.0.0.msi` — بدون نیاز به دسترسی مدیر |

### چه می‌کند

کانفیگ‌ها را خودش از **۶۲ منبع** (۴۰ اشتراک عمومی و ۲۲ کانال تلگرام) جمع می‌کند،
و سرورها را در سه مرحله می‌سنجد:

۱. **پینگ** روی همه — فقط برای باریک کردن میدان
۲. **درخواست واقعی از داخل تونل** برای ۵۰ سرور برتر — که ثابت می‌کند سرور واقعاً کار می‌کند
۳. **دانلود واقعی** از ۸ سرور برتر — که سرعت واقعی را می‌سنجد

مرحله‌ی سوم مهم است: دست‌دادن TCP فقط می‌گوید چیزی روی آن پورت گوش می‌دهد، و
سرورهای رایگان بین هزاران نفر مشترک‌اند. رتبه‌بندی نهایی بر **پهنای باند
اندازه‌گیری‌شده** است، نه تأخیر.

### پروتکل‌ها

VLESS (با Reality و XTLS)، VMess، Trojan، Shadowsocks، Hysteria2، TUIC،
WireGuard، Cloudflare WARP، SOCKS و HTTP.

### نکته‌ها

- **اندروید ۸ به بالا.** WARP به اندروید ۱۳ به بالا نیاز دارد (تولید کلید X25519).
- **ویندوز** از پروکسی سیستمی استفاده می‌کند نه TUN — نه درایور می‌خواهد، نه
  دسترسی مدیر، و اگر برنامه بسته شود مسیریابی سیستم سالم می‌ماند.
- بار اول منابع را می‌گیرد و می‌سنجد؛ چند دقیقه طول می‌کشد.
- کانفیگ‌های عمومی ذاتاً ناپایدارند. اگر کند بود، «به‌روزرسانی منابع» و بعد
  «تست همه» را بزنید.
- بدون حساب کاربری، بدون تله‌متری، بدون تبلیغات.

</div>

---

## Gozar 1.0.0

First stable release, for **Android** and **Windows**.

| | File |
|---|---|
| **Android** | `app-arm64-v8a-release.apk` unless you know you need another ABI |
| **Windows** | `Gozar-1.0.0.msi` — no administrator rights needed |

### How servers are chosen

Configs are collected from **62 sources** (40 subscriptions, 22 Telegram
channels), then measured in three stages: a handshake ping over everything, a
real request through the proxy for the best 50, and a real download through the
best 8.

That last stage is the point. A handshake only proves something is listening,
and these are free servers shared by thousands of people — so the final ranking
is built on **measured throughput**, not latency. Hiddify ranks on
`lowest-delay`; this does not.

### Protocols

VLESS (Reality, XTLS), VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard,
Cloudflare WARP, SOCKS and HTTP.

### Notes

- **Android 8+.** WARP additionally needs Android 13+, which is where the
  platform gained X25519 key generation.
- **Windows** uses the system proxy rather than a TUN device: no driver, no
  elevation prompt, and killing the app cannot leave routing broken.
- Public configs are inherently unstable. If it is slow, update the sources and
  run the test again.
- No account, no telemetry, no ads.

GPL-3.0, inherited from sing-box.
