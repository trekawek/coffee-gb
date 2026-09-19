# Apple local translation helper

This native accessory app recognizes text with Vision, identifies the screen's
source language with NaturalLanguage, and translates to English with Apple's
Translation framework. It runs on macOS 15 or newer, with separate Intel and
Apple silicon builds. End users do not need Xcode, API keys, an account, or a
subscription. The first use of a language pair can require an internet connection
and Apple's permission to download its models. Subsequent translations use those
models locally.

## Build and verify on macOS

Xcode 16+ and the macOS 15+ SDK are build requirements only:

```sh
packaging/apple-translation/build.sh /tmp/CoffeeGBTranslation.app arm64
packaging/apple-translation/test.sh /tmp/CoffeeGBTranslation.app
```

Use `x86_64` for the Intel package. `build.sh` ad-hoc signs the helper; the release
packaging process signs the nested app with the application's Developer ID.
`test.sh` without an argument builds a temporary helper for the current Mac.
The helper's `--self-test` mode and the script exercise protocol validation,
coordinate conversion, source-language selection, English exclusion, blank-screen
and synthetic Japanese Vision OCR, and whole-process request handling. A separate
`--session-self-test` creates the same transparent SwiftUI host used for installed
languages and verifies that `.translationTask` supplies a session. It never calls
the session's translation or download methods. These checks need no language models. They
cannot verify real translation quality, the permission UI, or latency.

Manual release checks on a Mac:

1. With Japanese translation languages uninstalled, translate a Japanese game
   screen. The helper should show Apple's download permission sheet, and the
   emulator should remain cancellable while setup runs. Cancelling or closing
   the helper must return a cancelled status without leaving a process running;
   dismiss the overlay to resume a game that translation paused.
2. Approve the download and verify that the detected text receives English
   overlays. Check the Japanese text's position against its original pixel box.
3. Disconnect the network and translate again. No helper window or download
   prompt should appear; measure the whole shortcut-to-overlay time against the
   application's normal deadline.
4. Repeat with a paused game, a rotated screen, a plain English screen, and an
   unsupported source language. Repeat on both supported Mac architectures.

Language models can be removed for testing in **System Settings > General >
Language & Region > Translation Languages**. Recognition of small pixel fonts
and translation of short, context-poor labels need real game validation.

## Private process protocol

The JVM launches `CoffeeGBTranslation.app/Contents/MacOS/CoffeeGBTranslation`
directly, sends one UTF-8 JSON line on stdin, and reads UTF-8 JSON lines on stdout:

```json
{"version":1,"image":"BASE64_PNG","width":160,"height":144}
```

The image is limited to 512×512, decoded PNG data to 1 MiB, and the request line
to 2 MiB. Image header dimensions must equal the declared dimensions. Image and
recognized text stay in memory; the helper never writes them to logs or disk.

Each process emits one terminal event:

```json
{"event":"result","regions":[{"text":"Menu","x":32,"y":16,"width":48,"height":8}]}
```

or:

```json
{"event":"error","code":"unsupported_language"}
```

Error codes are `invalid_request`, `unsupported_language`, `language_detection`,
`cancelled`, `download_failed`, and `translation_failed`. Errors do not expose raw
framework messages or recognized text. A result contains at most 64 regions,
with at most 2,048 characters per region. All coordinates are integer native
input pixels from the top left. Vision works on a 4× nearest-neighbor enlargement;
its normalized bottom-left boxes are clipped and rounded outward when converted.

Only if languages need downloading, the helper first emits `{"event":"setup"}`.
The caller suspends its normal translation deadline and uses its separate,
cancellable setup deadline. After languages are installed, the helper emits
`{"event":"translating"}` and the caller starts its normal deadline again.
Already-installed translations emit neither progress event. The caller can
terminate the process at any point; a parent-exit monitor also prevents orphans.

macOS 15 requires sessions created by SwiftUI's `.translationTask`; the newer
headless `TranslationSession(installedSource:target:)` constructor requires
macOS 26 and is deliberately not used. A transparent, noninteractive host window
keeps the SwiftUI session alive during installed-language translation. It becomes
visible only for first-use setup. The host remains ordered in until translation
finishes, because removing the SwiftUI view invalidates the session.

The source language is chosen once for the screen, using non-Latin lines before
English titles and kana as strong Japanese evidence. Existing English labels are
excluded. Mixed foreign-language screens currently use that one source language.

## Apple references

- [TranslationSession and on-device processing](https://developer.apple.com/documentation/translation/translationsession)
- [SwiftUI translationTask](https://developer.apple.com/documentation/swiftui/view/translationtask(_:action:))
- [LanguageAvailability](https://developer.apple.com/documentation/translation/languageavailability)
- [Download preparation](https://developer.apple.com/documentation/translation/translationsession/preparetranslation())
- [Vision text recognition](https://developer.apple.com/documentation/vision/recognizing-text-in-images)

Apple documents that its Translation framework may collect usage/performance
metrics including the app bundle ID and language pair; those metrics do not
include original or translated content.
