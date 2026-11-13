# Contributing to GameLodge

Thanks for helping! Bug reports, ideas, translations and code are all welcome.

## Report a bug or suggest an idea

- **Bugs:** [open an issue](https://github.com/thecodingdad/gamelodge/issues/new/choose) with the bug report form.
  The GameLodge version, your device and the steps that lead to the problem help most.
- **Ideas:** use the feature request form, or start a [discussion](https://github.com/thecodingdad/gamelodge/discussions)
  first if you'd like to talk it through.
- **Questions:** the [documentation and FAQ](https://thecodingdad.github.io/gamelodge/) cover most of setup and
  streaming; anything else goes to [Discussions](https://github.com/thecodingdad/gamelodge/discussions).

## Translations

GameLodge is available in English, German, Spanish, French, Italian and Brazilian Portuguese. The texts live in
`shared/src/commonMain/composeResources/values-<language>/strings.xml` (and a few Android-only ones in
`app/src/main/res/values-<language>/strings.xml`). Fixes to an existing language or a new one are very welcome –
copy `values/strings.xml` to a new `values-<code>/` folder and translate it.

## Code

1. For anything larger than a small fix, open an issue or discussion first, so we can agree on the approach.
2. Fork the repository and clone it with its submodules:
   ```bash
   git clone --recurse-submodules https://github.com/<you>/gamelodge.git
   ```
3. Build and test (JDK 21, Android SDK 36, NDK 27.0.12077973):
   ```bash
   ./gradlew :app:assembleSystemwideDebug :app:testSystemwideDebugUnitTest   # Android
   ./gradlew :shared:desktopTest :desktop:test                               # shared code and Windows
   ```
4. Open a pull request against `main` with a short description of what changes and how you tested it.

### Project layout

| Path | Content |
|---|---|
| `shared/` | Kotlin Multiplatform: the whole UI (Compose) and logic for Android and Windows |
| `app/` | Android: platform parts, streaming through Artemis |
| `desktop/` | Windows: platform parts, streaming through Moonlight-Qt, the Xbox player |
| `host/`, `protocol/` | The PC as host: pairing, client API, web interface |
| `server/` | Standalone metadata server (Docker) |
| `artemis/`, `moonlight-qt/` | Forks of the streaming clients (submodules) – changes there stay as small as possible |
| `docs/` | The documentation site (MkDocs) |

### Guidelines

- **Every screen works with a controller, touch and mouse.** Button hints show only while a controller is connected.
- **Texts are never hard-coded:** add them to `values/strings.xml` and to every language folder (an English text in
  the other languages is fine – a translator will pick it up). `StringResourcesTest` checks that none are missing.
- **English** for code, commit messages, docs and strings.
- **Keep pull requests focused** – one change per pull request makes it easy to review.
- **Privacy and security:** no telemetry, and credentials never end up in logs, files in the repository or backups.
  See [Privacy](https://thecodingdad.github.io/gamelodge/privacy/).
- Update the documentation in `docs/` when you change how something works.

By contributing you agree that your contribution is licensed under the [GPL-3.0](LICENSE).
