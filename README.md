<p align="center"><img src="docs/logo.svg" width="58%" alt="Gozar" /></p>

<p align="center">
<b>English</b> · <a href="#-فارسی">فارسی</a>
</p>

<p align="center">
<a href="https://github.com/basiliemami1-art/Roozbe61/releases/"><img src="https://img.shields.io/github/downloads/basiliemami1-art/Roozbe61/total?style=flat-square&logo=github" alt="Downloads"></a>
<a href="https://github.com/basiliemami1-art/Roozbe61/releases/latest"><img src="https://img.shields.io/github/v/release/basiliemami1-art/Roozbe61?style=flat-square" alt="Latest version"></a>
<a href="https://github.com/basiliemami1-art/Roozbe61/releases/latest"><img src="https://img.shields.io/github/release-date/basiliemami1-art/Roozbe61?style=flat-square" alt="Release date"></a>
<a href="https://github.com/basiliemami1-art/Roozbe61/actions"><img src="https://img.shields.io/github/actions/workflow/status/basiliemami1-art/Roozbe61/build.yml?branch=main&style=flat-square&logo=githubactions&logoColor=white" alt="Build"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0-blue?style=flat-square" alt="Licence"></a>
</p>

---

## What is Gozar?

Gozar is a free, open-source censorship-circumvention client for **Android** and
**Windows**. You do not bring it configs — it collects them itself, from 65
public sources, and then works out which of them are actually any good.

That last part is the whole point. Most clients rank servers by how fast they
answer a handshake. That number is close to meaningless: it proves something is
listening on a port, not that the proxy works, and certainly not that it has any
bandwidth left. Gozar measures what it is going to use.

## 🚀 Main features

### Servers are ranked on measured speed, not ping

Three stages, each narrowing the field for the next:

| Stage | Servers | What it establishes |
|---|---|---|
| **TCP handshake** | all, up to 2,000 | something is listening on that port |
| **Real request through the proxy** | best 50 | the proxy carries traffic at all |
| **Real download through the proxy** | best 8 | how much bandwidth is actually left |

Scraped lists are full of servers that answer in 40 ms and then fail at TLS or
authentication — ranking on the handshake puts precisely those at the top. And
delay says nothing about throughput: these are free servers shared by thousands
of people. So the final order is built on **measured KB/s**.

The download stage runs strictly one server at a time. Two at once would split
your own line between them and both would read as half their real speed, which
would turn the ranking into noise. Each is capped at 1.5 MB or 2.5 seconds.

### Configs collected automatically

65 sources — 43 subscription endpoints and 22 Telegram channels — fetched in
parallel and merged. Included are MahsaNet's lists **split by Iranian mobile
operator**, because the bottleneck here is usually the operator's own
international routing: a server that is fast on Hamrah-e Aval can be unusable on
Irancell.

Sources are shipped only if they were measured to contribute endpoints no other
source already carries. Several of the largest public aggregators turned out to
be 99% duplicates and are deliberately absent.

### Every common protocol

VLESS (Reality, XTLS, Vision), VMess, Trojan, Shadowsocks, Hysteria2, TUIC,
WireGuard, Cloudflare WARP, SOCKS and HTTP — all on one **sing-box** core.

### Add your own, however you have it

One field takes whatever you paste: a config link of any protocol, dozens at
once, a whole base64 subscription body, a subscription URL, or a Telegram
channel. It works out which it is. Paid subscriptions also show their remaining
traffic and expiry date, read from the panel's own headers.

### Built for a network that is being cut

Servers whose entry point resolves to an address **inside Iran** are identified
and kept in reserve: when international routing is cut and the domestic network
stays up, they are the only ones still reachable.

The connect button does not give up. It works down the ranked list, widening
after each pass, until something carries traffic or you cancel — and it tells
you why the last one failed.

### Persian, properly

Right-to-left throughout, in Vazirmatn, with English available. Light and dark.
No account, no telemetry, no ads.

## 📥 Direct download

<table>
  <thead><tr><th>Platform</th><th>Download</th><th>Notes</th></tr></thead>
  <tbody>
    <tr>
      <td><b>Android</b></td>
      <td>
        <a href="https://github.com/basiliemami1-art/Roozbe61/releases/latest/download/app-arm64-v8a-release.apk"><img src="https://img.shields.io/badge/APK-ARM64-044d29.svg?logo=android" alt="APK ARM64"></a><br>
        <a href="https://github.com/basiliemami1-art/Roozbe61/releases/latest/download/app-armeabi-v7a-release.apk"><img src="https://img.shields.io/badge/APK-ARMv7-168039.svg?logo=android" alt="APK ARMv7"></a><br>
        <a href="https://github.com/basiliemami1-art/Roozbe61/releases/latest/download/app-x86_64-release.apk"><img src="https://img.shields.io/badge/APK-x86__64-45bf55.svg?logo=android" alt="APK x86_64"></a>
      </td>
      <td>Android 8+. Take <b>ARM64</b> unless you know otherwise — almost every phone in use is ARM64. WARP additionally needs Android 13+.</td>
    </tr>
    <tr>
      <td><b>Windows</b></td>
      <td>
        <a href="https://github.com/basiliemami1-art/Roozbe61/releases/latest/download/Gozar-Windows-x64.msi"><img src="https://img.shields.io/badge/MSI-x64-0078d7.svg?logo=windows" alt="MSI x64"></a>
      </td>
      <td>Windows 10/11 x64. <b>No administrator rights needed.</b></td>
    </tr>
  </tbody>
</table>

Every push to `main` also refreshes a rolling
[`latest` build](https://github.com/basiliemami1-art/Roozbe61/releases/tag/latest)
if you want the newest code rather than the newest release.

## ⚙️ First run

1. Open the app. It fetches the sources by itself — a few minutes the first time.
2. Wait for the three test stages to finish. The last one shows a speed for each
   of the best servers.
3. Press connect.

If it is slow later, update the sources and run the test again. Public configs
are inherently unstable; that is the nature of them, not a fault in the app.

## 🔍 How it works

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

Windows uses the system proxy rather than a TUN device on purpose: TUN there
needs the Wintun driver and an elevation prompt on every launch, while this
installs without administrator rights and cannot leave your routing broken if
the app is killed.

Both platforms share one Kotlin module for parsing, sources, ranking and
config generation, so the two cannot drift apart.

## 🛠️ Building

CI builds both platforms on every push. Locally you need **JDK 17** — sing-box's
`build_libbox` hard-checks the java banner and aborts on anything else — plus
the Android SDK (platform 35), Go 1.24+, and **NDK r28 or newer**, since r27's
linker rejects relocations in the prebuilt `libcronet.a` that sing-box links.

```bash
./gradlew :app:assembleRelease         # Android
./gradlew :desktop:packageReleaseMsi   # Windows, on Windows
```

## 📋 Known limitations

- **One proxy core.** sing-box *and* Xray cannot ship in one APK: every
  gomobile-generated AAR bundles its own copy of the unnamespaced `go.*` runtime,
  so they collide on `go.Seq` at merge time, and each copy binds it to a
  different native library — merging by hand would leave one core's JNI
  unregistered. sing-box is kept because it is a strict superset here.
- **WARP and WireGuard skip the delay and speed stages.** sing-box configures
  them as endpoints rather than outbounds, and the Clash API resolves tags
  through the outbound manager only, so they keep their handshake ranking.
- Iranian routing uses embedded domain and CIDR lists rather than downloadable
  `rule_set` bundles — a remote rule set would have to be fetched *before* the
  tunnel works, which is exactly what fails on a censored network.
- Shadowsocks links carrying `plugin=` are rejected rather than imported, since
  the generated config would silently drop the plugin and never connect.

## ✏️ Acknowledgements

- [sing-box](https://github.com/SagerNet/sing-box) — the proxy core, and the
  reason this is GPL-3.0.
- [MahsaNet](https://github.com/mahsanet/MahsaFreeConfig),
  [Hiddify](https://github.com/hiddify/hiddify-app) and every maintainer of the
  public source lists this ships with.
- [Vazirmatn](https://github.com/rastikerdar/vazirmatn) by Saber Rastikerdar,
  under the SIL Open Font License.

## 📄 Licence

GPL-3.0 — see [LICENSE](LICENSE).

---

<h2 id="-فارسی">فارسی</h2>

<div dir="rtl">

## گذر چیست؟

گذر یک کلاینت آزاد و متن‌باز عبور از فیلترینگ برای **اندروید** و **ویندوز** است.
لازم نیست کانفیگ برایش بیاورید — خودش از ۶۵ منبع عمومی جمع می‌کند، و بعد
تشخیص می‌دهد کدامشان واقعاً به‌درد می‌خورند.

همین بخش آخر مهم است. بیشتر برنامه‌ها سرورها را بر اساس سرعت پاسخ دست‌دادن
مرتب می‌کنند. آن عدد تقریباً بی‌معناست: فقط ثابت می‌کند چیزی روی آن پورت گوش
می‌دهد — نه اینکه پروکسی کار می‌کند، و قطعاً نه اینکه پهنای باندی برایش مانده.
گذر همان چیزی را می‌سنجد که قرار است استفاده کند.

## 🚀 ویژگی‌های اصلی

### رتبه‌بندی بر پایه‌ی سرعت اندازه‌گیری‌شده، نه پینگ

سه مرحله، هر کدام میدان را برای بعدی باریک می‌کند:

| مرحله | تعداد سرور | چه چیزی را ثابت می‌کند |
|---|---|---|
| **دست‌دادن TCP** | همه، تا ۲۰۰۰ | چیزی روی آن پورت گوش می‌دهد |
| **درخواست واقعی از داخل تونل** | ۵۰ برتر | پروکسی اصلاً ترافیک رد می‌کند |
| **دانلود واقعی از داخل تونل** | ۸ برتر | چقدر پهنای باند واقعاً مانده |

فهرست‌های اسکرپ‌شده پر است از سرورهایی که در ۴۰ میلی‌ثانیه جواب می‌دهند و بعد
در TLS یا احراز هویت شکست می‌خورند — مرتب‌سازی بر پایه‌ی دست‌دادن دقیقاً همان‌ها
را اول لیست می‌نشاند. و تأخیر چیزی درباره‌ی پهنای باند نمی‌گوید: این‌ها سرورهای
رایگانی‌اند که هزاران نفر هم‌زمان استفاده می‌کنند. پس ترتیب نهایی بر
**سرعت اندازه‌گیری‌شده** بنا شده است.

مرحله‌ی دانلود حتماً تک‌به‌تک اجرا می‌شود. دو تا هم‌زمان، خط خودِ شما را بینشان
تقسیم می‌کنند و هر دو نصف سرعت واقعی خوانده می‌شوند — رتبه‌بندی نویز می‌شد. سقف
هر کدام ۱.۵ مگابایت یا ۲.۵ ثانیه است.

### جمع‌آوری خودکار کانفیگ

۶۵ منبع — ۴۳ اشتراک و ۲۲ کانال تلگرام — که موازی گرفته و ادغام می‌شوند. از
جمله فهرست‌های MahsaNet که **به تفکیک اپراتور موبایل ایران** تقسیم شده‌اند، چون
گلوگاه اینجا معمولاً مسیریابی بین‌المللی خودِ اپراتور است: سروری که روی همراه
اول سریع است می‌تواند روی ایرانسل غیرقابل‌استفاده باشد.

منبعی وارد می‌شود که سنجیده شده باشد سرورهایی می‌آورد که هیچ منبع دیگری ندارد.
چند تا از بزرگ‌ترین گردآورنده‌های عمومی ۹۹٪ تکراری از آب درآمدند و عمداً غایب‌اند.

### همه‌ی پروتکل‌های رایج

VLESS (با Reality، XTLS، Vision)، VMess، Trojan، Shadowsocks، Hysteria2، TUIC،
WireGuard، Cloudflare WARP، SOCKS و HTTP — همه روی یک هسته‌ی **sing-box**.

### افزودن دستی، به هر شکلی که دارید

یک ورودی هر چه بچسبانید را می‌گیرد: لینک کانفیگ هر پروتکلی، ده‌ها تا با هم، یک
بدنه‌ی base64 کامل، لینک اشتراک، یا کانال تلگرام. خودش تشخیص می‌دهد کدام است.
اشتراک‌های خصوصی ترافیک باقی‌مانده و تاریخ انقضایشان را هم نشان می‌دهند، از روی
هدرهای خود پنل.

### ساخته‌شده برای شبکه‌ای که قطع می‌شود

سرورهایی که ورودی‌شان به آدرسی **داخل ایران** می‌رسد شناسایی و کنار گذاشته
می‌شوند: وقتی اینترنت بین‌المللی قطع شود و شبکه‌ی داخلی بماند، تنها چیزی هستند
که هنوز در دسترس است.

دکمه‌ی اتصال تسلیم نمی‌شود. لیست رتبه‌بندی‌شده را پایین می‌رود، بعد از هر دور
دامنه را پهن‌تر می‌کند، تا وقتی چیزی ترافیک رد کند یا شما انصراف بدهید — و
می‌گوید آخرین سرور چرا شکست خورد.

### فارسیِ درست

راست‌به‌چپ در سراسر برنامه، با فونت وزیرمتن، به‌علاوه‌ی انگلیسی. روشن و تیره.
بدون حساب کاربری، بدون تله‌متری، بدون تبلیغات.

## 📥 دانلود مستقیم

| پلتفرم | فایل | توضیح |
|---|---|---|
| **اندروید** | [`app-arm64-v8a-release.apk`](https://github.com/basiliemami1-art/Roozbe61/releases/latest/download/app-arm64-v8a-release.apk) | اندروید ۸ به بالا. اگر نمی‌دانید کدام، همین را بگیرید. WARP به اندروید ۱۳ به بالا نیاز دارد. |
| اندروید (ARMv7) | [`app-armeabi-v7a-release.apk`](https://github.com/basiliemami1-art/Roozbe61/releases/latest/download/app-armeabi-v7a-release.apk) | گوشی‌های قدیمی‌تر |
| اندروید (x86_64) | [`app-x86_64-release.apk`](https://github.com/basiliemami1-art/Roozbe61/releases/latest/download/app-x86_64-release.apk) | شبیه‌سازها |
| **ویندوز** | [`Gozar-Windows-x64.msi`](https://github.com/basiliemami1-art/Roozbe61/releases/latest/download/Gozar-Windows-x64.msi) | ویندوز ۱۰/۱۱. **بدون نیاز به دسترسی مدیر.** |

## ⚙️ اولین اجرا

۱. برنامه را باز کنید. خودش منابع را می‌گیرد — بار اول چند دقیقه طول می‌کشد.
۲. صبر کنید سه مرحله‌ی تست تمام شود. مرحله‌ی آخر برای سرورهای برتر سرعت نشان می‌دهد.
۳. دکمه‌ی اتصال را بزنید.

اگر بعداً کند شد، منابع را به‌روزرسانی کنید و دوباره تست بگیرید. کانفیگ‌های
عمومی ذاتاً ناپایدارند؛ این ماهیت آن‌هاست، نه ایراد برنامه.

## 🔍 چطور کار می‌کند

ویندوز عمداً به‌جای TUN از پروکسی سیستمی استفاده می‌کند: TUN آنجا درایور Wintun
و پنجره‌ی دسترسی مدیر در هر بار اجرا می‌خواهد، در حالی که این روش بدون دسترسی
مدیر نصب می‌شود و اگر برنامه بسته شود مسیریابی سیستم را خراب رها نمی‌کند.

هر دو پلتفرم یک ماژول کاتلین مشترک برای پارس، منابع، رتبه‌بندی و تولید کانفیگ
دارند، پس نمی‌توانند از هم جدا بیفتند.

## 📋 محدودیت‌های شناخته‌شده

- **فقط یک هسته.** sing-box و Xray نمی‌توانند در یک APK باشند: هر AAR ساخته‌شده
  با gomobile نسخه‌ی خودش از رانتایم `go.*` را می‌آورد و روی `go.Seq` برخورد
  می‌کنند. sing-box نگه داشته شده چون اینجا زیرمجموعه‌ی کاملی از Xray است و
  بیشتر.
- **WARP و WireGuard از مرحله‌ی تأخیر و سرعت بیرون‌اند** و رتبه‌ی دست‌دادن خود را
  نگه می‌دارند — در sing-box این‌ها endpoint هستند نه outbound.
- مسیریابی ایران از فهرست‌های دامنه و CIDR جاسازی‌شده استفاده می‌کند، نه
  `rule_set` دانلودی — که باید *پیش از* کار کردن تونل گرفته شود، و دقیقاً همان
  چیزی است که روی شبکه‌ی سانسورشده شکست می‌خورد.

## 📄 پروانه

GPL-3.0 — به [LICENSE](LICENSE) نگاه کنید.

</div>
