# Rang Manch India – Android project

WebView app jo aapki website (`web.zip`) ko app ke andar bundle karta hai.
Package: `dev.workers.rangmanch.app` • minSdk 24 • targetSdk 34

## 1) GitHub se APK banana (Android Studio zaroori nahi)
1. GitHub par naya **private** repo banao aur is poore folder ko push karo (`.github/` folder zaroor jaye).
2. Signing secrets daalo: Repo → Settings → Secrets and variables → Actions → *New repository secret*
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
   (values `RangManch-KEYSTORE-PRIVATE/SECRETS-README.txt` me hain — wo folder repo me mat daalna).
3. (Optional) Repo → Settings → Variables → `SITE_URL` = aapki live website, jaise `https://rangmanch.xyz.workers.dev`
   (sirf *share* links ke liye; nahi diya to share link app ka internal link ban jayega).
4. **Actions** tab → *Build Rang Manch APK* → **Run workflow**. 5–8 min baad run ke neeche **Artifacts → RangManch-APK-AAB**
   me `RangManch-N.apk` (phone me install) aur `RangManch-N.aab` (Play Store) milenge.

## 2) Google login chalane ke liye (zaroori, ek baar)
Supabase → Authentication → URL Configuration → **Redirect URLs** me add karo: `rangmanch://auth`
(Google login Chrome Custom Tab me khulta hai aur is link se wapas app me aata hai.)

## 3) Android Studio me kholna
*Open* → is folder ko select karo → Gradle sync → ▶ Run. (JDK 17 chahiye – Android Studio ke saath aata hai.)

## 4) Website update karne par
Website ka naya version aaye to us build ki saari files ka zip banao, naam `web.zip` rakho aur repo me purani `web.zip` replace karo. Workflow naya APK bana dega.

## App me kya hai
- Local files se instant load, offline shell
- Gallery/video upload (file chooser), camera/mic permission, fullscreen video, back button
- Google login (Custom Tab + `rangmanch://auth`), UPI/tel/mailto links system app me
