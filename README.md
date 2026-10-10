# Cowmoji Keyboard

> **This is a modified version of [FUTO Keyboard](https://github.com/futo-org/android-keyboard).**
> It is not made or supported by FUTO. The original README follows further down.

A FUTO Keyboard fork built around [Emoji Kitchen](https://emojikitchen.dev): pick your favourite
animal and every emoji becomes a Google Emoji Kitchen mix starring it. There are 52
animals with 22,419 combinations between them, plus a GIF search, saved quick responses and
extra themes.

It builds as a separate app ("Cowmoji Keyboard", app id `org.futo.inputmethod.latin.cow`), so it
installs alongside the official keyboard.

## Animals

When you first set the keyboard up, you choose your animal. You can change it any time in
**Settings → Animal**.

The cow (⭐) is built into the app and works offline. Every other animal downloads its combinations
once, the first time you pick it (about 4–7 MB, straight from Google's Emoji Kitchen servers), and
then works offline too. Animals marked WIP aren't in Emoji Kitchen yet.

| Animal | Combos | Animal | Combos | Animal | Combos |
|---|---|---|---|---|---|
| 🐵 Monkey | 365 | 🦁 Lion | 361 | 🐯 Tiger | 369 |
| 🐱 Cat | 372 | 🐶 Dog | 356 | 🐺 Wolf | 358 |
| 🐻 Bear | 363 | 🐨 Koala | 367 | 🐼 Panda | 362 |
| 🐭 Mouse | 363 | 🐰 Rabbit | 566 | 🦊 Fox | 357 |
| 🦝 Raccoon | 598 | 🐮 Cow ⭐ | 396 | 🐷 Pig | 365 |
| 🦄 Unicorn | 619 | 🐉 Dragon | 618 | 🐢 Turtle | 359 |
| 🐍 Snake | 464 | 🐸 Frog | 371 | 🐩 Poodle | 559 |
| 🐎 Horse | 509 | 🐐 Goat | 610 | 🦌 Deer | 414 |
| 🦙 Llama | 353 | 🦥 Sloth | 348 | 🦔 Hedgehog | 357 |
| 🦇 Bat | 573 | 🦉 Owl | 430 | 🐦 Bird | 364 |
| 🐔 Chicken | 462 | 🦚 Peacock | 422 | 🪿 Goose | 386 |
| 🕊️ Dove | 365 | 🦩 Flamingo | 443 | 🦨 Skunk | 466 |
| 🐧 Penguin | 353 | 🦈 Shark | 601 | 🐳 Whale | 616 |
| 🐟 Fish | 507 | 🦞 Lobster | 356 | 🦀 Crab | 549 |
| 🐙 Octopus | 422 | 🦂 Scorpion | 459 | 🕷️ Spider | 522 |
| 🐌 Snail | 348 | 🦗 Cricket | 273 | 🪲 Beetle | 279 |
| 🪳 Cockroach | 417 | 🐝 Bee | 356 | 🐞 Ladybug | 382 |
| 🦋 Butterfly | 569 | 🦭 Seal | WIP | Manatee | WIP |

## Features

### Animal Kitchen

A button on the action bar, showing your animal's emoji, opens a grid of every combination.

- **Tap** a combination to send it to the app as an image.
- **Press and hold** to see it full size, Telegram-sticker style. Slide your finger to preview
  others, and let go to close it without sending anything.
- **Most used** combinations sit in a strip at the top.
- **Search** by emoji name or keyword ("cry", "tears joy", "heart"), or paste an emoji. The
  keyboard hides itself when you press Enter or start scrolling, so the results get the whole panel.

### Emoji replacements

In the regular emoji panel, tapping an emoji sends your animal's combination instead (with the cow,
😂 sends cow + 😂).

- This only happens in apps that accept images (Messages, WhatsApp, Signal, Telegram, ...).
  Elsewhere the plain emoji is typed as usual.
- Emojis without a combination, and the ASCII emoticons, type normally.
- Turn it off with the switch at the top of the Animal Kitchen panel.

### GIF search

A Gboard-style GIF panel.

- Regular GIFs come from [KLIPY](https://partner.klipy.com). It needs a free API key: copy it,
  then tap **Paste API key** in the GIF panel.
- The **18+** button switches to adult results from RedGIFs (no key needed). It asks you to confirm
  you are 18 or older the first time. Previews play as muted, looping videos.
- With a free [GIPHY](https://developers.giphy.com) key (**Paste GIPHY key** at the top of the 18+
  results), GIPHY GIFs rated up to R are mixed into 18+ results. Free GIPHY keys allow 100 searches
  an hour.
- Each GIF is sent in whatever format the app accepts: GIF first, otherwise MP4. RedGIFs only has
  MP4s, so for apps that take GIFs but not video, the clip is converted on the phone into a short
  GIF (up to 6 s, 10 fps, 320 px wide).

API keys are stored only in the keyboard's settings on your phone.

### Quick Responses

A speech-bubble action holding saved phrases.

- Tap a phrase to type it; the panel then closes.
- Add phrases with **+**. Long-press one to edit it, delete it or move it to the top.
- Phrases are kept in the keyboard's settings, which Android encrypts until the phone is unlocked.

### Themes

Three extra themes in **Settings → Theme**: Half-Life, Portal and Blue Lantern, each with its own
background and font.

### Internet use

GIF search and downloading an animal's combinations are the only things that use the internet.
This build adds the `INTERNET` permission for them; stock FUTO Keyboard has no internet access. On
Android versions that let you switch off network access per app, the keyboard tells you if it's off
and links to the setting.

## Building

You need the Android SDK (the NDK and CMake download automatically). Clone with submodules, as
described in FUTO's instructions below, then:

```sh
./gradlew assembleCowRelease
adb install -r build/outputs/apk/cow/release/android-keyboard-cow-release.apk
```

Then enable "Cowmoji Keyboard" in Android's keyboard settings.

On Windows, run the build with `PYTHONUTF8=1` set, as FUTO's locale generator script needs it.

Release builds are signed with FUTO's shared debug key unless you set up your own
`keystore.properties`. They are **not** official FUTO builds, so the signing fingerprints in
FUTO's README below don't apply to them.

## Updating the combinations

Google adds new Emoji Kitchen combinations from time to time. To refresh them:

```sh
python tools/kitchen/fetch_kitchen.py --images cow
```

This downloads the emojikitchen.dev data set (about 100 MB), writes every animal's combination list
to `java/assets/kitchen/`, and refreshes the built-in cow images. Pass
`--metadata path/to/metadata.json` to reuse a copy you already downloaded.

The themes are made by `tools/themes/make_themes.py`.

## What's changed from FUTO Keyboard

| Path | Change |
| --- | --- |
| `java/src/.../uix/actions/kitchen/` | Animal Kitchen: animals, downloads, panel, chooser and emoji replacement |
| `java/src/.../uix/actions/gif/` | GIF search: providers, downloads and conversion, video previews, the panel |
| `java/src/.../uix/actions/quickresponses/` | Quick Responses panel |
| `java/src/.../uix/actions/EmojiAction.kt` | Emoji taps try the animal combination first |
| `java/src/.../uix/actions/Registry.kt` | Registers the new actions and pins them by default |
| `java/src/.../uix/Action.kt`, `ActionBar.kt`, `actions/MoreActions.kt` | Actions can show a full-colour picture (the animal emoji) as their icon |
| `java/src/.../uix/settings/` | The "Choose your animal" setup step and the Animal settings page |
| `java/src/.../content/ContentProvider.kt` | Serves animal images and GIFs to other apps |
| `java/assets/kitchen/`, `java/assets/cow_kitchen/` | Every animal's combination list, and the built-in cow images |
| `java/cow/assets/themes/` | The extra themes |
| `tools/kitchen/`, `tools/themes/` | Scripts that build the combinations and themes |
| `build.gradle`, `java/cow/` | The `cow` build flavour |

## Licensing

FUTO Keyboard is under the [FUTO Source First License 1.1](LICENSE.md), and so is this fork. You
may modify and share it only for free and only for non-commercial use.

The Emoji Kitchen images belong to Google. The combination data comes from
[emojikitchen.dev](https://emojikitchen.dev)
([xsalazar/emoji-kitchen-backend](https://github.com/xsalazar/emoji-kitchen-backend)). Theme fonts
are from Google Fonts under the SIL Open Font License. KLIPY, GIPHY and RedGIFs content is subject
to those services' terms. Half-Life and Portal are trademarks of Valve, and Blue Lantern of DC; the
themes are unofficial fan themes.

---

# FUTO Keyboard

The goal is to make a good modern keyboard that stays offline and doesn't spy on you. This keyboard is a fork of [LatinIME, The Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME), with significant changes made to it.

Check out the [FUTO Keyboard website](https://keyboard.futo.tech/) for downloads and more information.

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Issue tracking and contributing

Please check the GitHub repository to report issues: [https://github.com/futo-org/android-keyboard/](https://github.com/futo-org/android-keyboard/)

The source code is hosted on our [internal GitLab](https://gitlab.futo.org/keyboard/latinime) and mirrored to [GitHub](https://github.com/futo-org/android-keyboard/). As registration is closed on our internal GitLab, we use GitHub instead for issues and pull requests.

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

Please do not submit AI-generated pull requests, as these tend to make the codebase more difficult to understand. Pull requests made with clear heavy use of AI may be closed without comment. Please also avoid using AI to write issues, and try to explain it in your own words instead.

## Layouts

If you want to contribute layouts, check out the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts).

## Building

When cloning the repository, you must perform a recursive clone to fetch all dependencies:
```
git clone --recursive https://gitlab.futo.org/keyboard/latinime.git
```

If you forgot to specify recursive clone, use this to fetch submodules:
```
git submodule update --init --recursive
```

You can then open the project in Android Studio and build it that way, or use gradle commands:
```
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## APK signing

For official FUTO Keyboard versions, you can verify the APK's signing key fingerprint for integrity.

```
Signing key fingerprint for all versions except Google Play:

MD5: 3A:BB:71:C6:BB:E4:92:27:B1:E3:5D:81:01:48:6A:B0
SHA1: 5D:15:B3:6E:C9:6A:96:28:41:09:DD:62:93:0D:9C:39:9F:5F:06:43
SHA-256: 74:3F:AD:58:64:AB:C4:26:50:0B:2D:C2:C4:7C:8A:D3:24:CB:CD:16:03:3F:80:16:99:48:41:35:63:74:F9:95

```
