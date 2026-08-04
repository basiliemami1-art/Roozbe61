<div dir="rtl">

## گذر ۱.۱.۰

### مهم‌ترین تغییر: رتبه‌بندی بر پایه‌ی سرعت واقعی

تا نسخه‌ی قبل، سرورها بر اساس **تأخیر** مرتب می‌شدند — همان کاری که Hiddify هم
می‌کند. آن عدد نمی‌گوید سروری که فوری جواب می‌دهد پهنای باندی هم برایش مانده یا
نه، و این سرورها بین هزاران نفر مشترک‌اند.

حالا یک مرحله‌ی سوم اضافه شده: ۸ سرور برتر یک **دانلود واقعی** می‌گیرند و
رتبه‌بندی نهایی بر KB/s اندازه‌گیری‌شده بنا می‌شود. تک‌به‌تک اجرا می‌شود، چون دو
دانلود هم‌زمان خط خودتان را تقسیم می‌کنند و اندازه‌گیری را بی‌معنا.

### افزودن دستی کانفیگ، هر پروتکلی

یک ورودی که هر چه بچسبانید را می‌گیرد: لینک کانفیگ، ده‌ها تا با هم، بدنه‌ی
base64، لینک اشتراک، یا کانال تلگرام. ویندوز قبلاً اصلاً این امکان را نداشت.

### سرورهای مرده حذف می‌شوند

بعد از هر تست، سرورهایی که جواب ندادند از لیست پاک می‌شوند. تست‌نشده‌ها و
ستاره‌دارها می‌مانند.

### برنامه سبک شد

سه ایراد واقعی: سقف ۱۰ هزار سرور (حالا ۳ هزار)، مرتب‌سازی کل لیست در هر بار
رسم صفحه، و سریالایز شدن کل لیست در هر تلاش اتصال.

### منابع بیشتر

۶۵ منبع، از جمله فهرست‌های MahsaNet **به تفکیک اپراتور** (همراه اول و ایرانسل)
— چون گلوگاه معمولاً مسیریابی بین‌المللی خودِ اپراتور است.

### دانلود

| | فایل |
|---|---|
| اندروید | `app-arm64-v8a-release.apk` |
| ویندوز | `Gozar-Windows-x64.msi` |

</div>

---

## Gozar 1.1.0

**Servers are now ranked on measured throughput, not latency.** Previously they
were ordered by delay — which is what Hiddify does too. That number says nothing
about whether a fast-answering server has any bandwidth left, and these are free
servers shared by thousands of people. A third stage now puts a real download
through the best 8 and ranks on measured KB/s. It runs one at a time, because
two at once would split your own line and make the measurement meaningless.

**Manual configs, any protocol.** One field takes a config link, dozens at once,
a base64 subscription body, a subscription URL, or a Telegram channel — and
works out which it is. Windows previously had no way to add one at all.

**Dead servers are removed** after each test, keeping untested and starred ones.

**The app got lighter**: the list ceiling dropped from 10,000 to 3,000, the
desktop no longer re-sorts everything on every recomposition, and the whole list
is no longer serialised to disk on every connect attempt.

**65 sources**, including MahsaNet's lists split by Iranian mobile operator.

| | File |
|---|---|
| Android | `app-arm64-v8a-release.apk` |
| Windows | `Gozar-Windows-x64.msi` |

GPL-3.0, inherited from sing-box.
