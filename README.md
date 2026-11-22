# StickerBridge

**Telegram sticker packs → WhatsApp.** Open-source Android app: paste a Telegram
sticker pack link, and add it to WhatsApp with one tap.

> Hindi: Telegram ke sticker pack ka link daalo, app usse WhatsApp format mein badal dega,
> fir "Add to WhatsApp" dabao. Bas.

## Features

- ✅ Static stickers
- ✅ Animated stickers (`.tgs`) → animated WebP, converted on the phone
- 🧪 Video stickers (`.webm`), experimental: the background may turn black
- ✅ Big packs are split automatically (WhatsApp allows max 30 stickers per pack)
- ✅ Static and animated stickers go into separate packs (a WhatsApp rule)
- ✅ Works with WhatsApp and WhatsApp Business
- ✅ Share a pack link from Telegram straight into the app
- 🔒 No server, no ads, no tracking. Your bot token stays on your phone and is only sent to `api.telegram.org`

## How to use / Kaise use karein

1. **Get a bot token (one time only)**
   - Open Telegram and search **@BotFather**
   - Send `/newbot`, then pick any name and a username that ends in `bot`
   - BotFather gives you a token like `123456789:ABCdef...`. Copy it.
2. Open StickerBridge and paste the token.
3. In Telegram, open any sticker pack → ⋮ → **Copy link** (`https://t.me/addstickers/...`).
4. Paste the link and tap **Import pack**.
5. Tap **Add to WhatsApp**. 🎉

> Why a bot token? Telegram only lets apps download sticker files through its official
> Bot API. Making a bot is free and takes 30 seconds. Older apps that used a shared token
> stopped working when that token got blocked. Your own token avoids that.

## Download

Download the latest APK from the [Releases](../../releases) page. Allow "Install unknown apps" on your phone when it asks.

## Build it yourself

Requirements: JDK 17+ and the Android SDK (or just Android Studio).

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

### Automatic builds on GitHub

`.github/workflows/build.yml` builds the APK on every push.

- **Every push to `main`:** the APK appears under *Actions → latest run → Artifacts*.
- **Push a tag such as `v1.0.0`:** a GitHub Release is created with the APK attached:
  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```

### Optional: sign with your own key

Without a key, the APK is signed with a random debug key on each build, so users must
uninstall the old version before installing a new one. To keep updates smooth, create a key once:

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias stickerbridge
base64 -w0 release.jks   # copy the output
```

Then add these **repository secrets** (Settings → Secrets and variables → Actions):
`KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`stickerbridge`),
`SIGNING_KEY_PASSWORD`. Never commit `release.jks`, and keep a backup of it.

## How it works

| Part | File |
|---|---|
| Telegram Bot API client (`getStickerSet`, `getFile`) | `telegram/TelegramApi.kt` |
| Resize to 512×512, fit the size limits, render `.tgs` with Lottie | `convert/StickerConverter.kt` |
| Pure-Kotlin animated WebP muxer (Android can only encode still WebP) | `convert/AnimatedWebpWriter.kt` |
| Splitting into WhatsApp packs, local storage | `data/StickerImporter.kt`, `data/PackStore.kt` |
| ContentProvider that WhatsApp reads packs from | `whatsapp/StickerContentProvider.kt` |

This app follows WhatsApp's official [third-party sticker app spec](https://github.com/WhatsApp/stickers/tree/main/Android).

## Disclaimer

This project is not affiliated with Telegram or WhatsApp. Respect the copyright of sticker
creators, and only import packs for personal use.

## License

[MIT](LICENSE)
