# Contributing to StickerBridge

Thanks for helping! 🙌

## Reporting bugs
Use the **[bug report template](https://github.com/J4XD/StickerBridg/issues/new/choose)**. Please include:
- your Android version and phone model
- the WhatsApp version
- the sticker pack link
- the exact error message or a screenshot

## Pull requests
1. Fork the repo and create a branch: `git checkout -b fix/my-fix`
2. Build and test on a real device: `./gradlew assembleRelease`
3. Run the unit tests: `./gradlew testReleaseUnitTest`
4. Keep PRs focused, with one fix or feature per PR, and describe what you tested.

## Ideas that would be great
- Proper transparency for video stickers (decoding the VP9 alpha channel)
- Preview grid of stickers before adding
- Translations (`app/src/main/res/values-xx/strings.xml`)
