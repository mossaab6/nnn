# Cloudflare Relay (اختياري)

يستقبل رسائل البوت من تيليجرام (Webhook) ويحفظها في قاعدة D1 مجانية حتى يسحبها التطبيق، فلا تضيع أي رسالة أو ملف حتى لو بقي الهاتف مطفأً أياماً.
الملفات نفسها تبقى على خوادم تيليجرام؛ يُحفظ هنا فقط نص الرسالة الصغير.

الخطة المجانية من Cloudflare تكفي بكثير للاستخدام الشخصي (100,000 طلب يومياً للـ Worker وقاعدة D1 مجانية).

## التثبيت (مرة واحدة)
تحتاج Node.js (https://nodejs.org) وحساب Cloudflare مجاني.

```cmd
cd cloud-relay
npm install
npx wrangler login
npx wrangler d1 create shamcash-relay
```
انسخ `database_id` الذي يظهر وضعه في `wrangler.toml` مكان `PASTE-YOUR-DATABASE-ID-HERE`.

في التطبيق: الإعدادات ← «الخدمة السحابية» ← اختر Cloudflare ← «توليد مفتاح» ← «نسخ المفتاح»، ثم:
```cmd
npx wrangler secret put RELAY_SECRET
npx wrangler deploy
```
(الصق المفتاح عندما يُطلب.) سيظهر رابط مثل `https://shamcash-relay.<name>.workers.dev`.

اختياري — رد فوري «تم استلام الملف» حتى لو كان الهاتف مطفأً:
```cmd
npx wrangler secret put BOT_TOKEN
```

أخيراً في التطبيق: الصق الرابط ← «حفظ وربط Webhook». للعودة للوضع المباشر اختر «مباشر» ← «حفظ».

## الواجهات
| المسار | الوصف |
|---|---|
| `POST /telegram` | Webhook تيليجرام (يتحقق من `X-Telegram-Bot-Api-Secret-Token`) |
| `GET /pull?after=ID` | يسحب الرسائل المحفوظة (Bearer RELAY_SECRET) |
| `POST /ack {"upTo":ID}` | يحذف الرسائل التي عالجها التطبيق |
| `GET /health` | عدد الرسائل بالانتظار |
