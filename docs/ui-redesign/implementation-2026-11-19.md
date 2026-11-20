# Native UI continuation — 2026-11-19

## Branches and scope

`main` remains the upstream mirror (initially `93a8f2c3`, advanced by upstream
to `dc473389` during this task and fetched locally). `dev` fast-forwards
`wip/settings-ia` (`b40ae29f`, whose parent is `46339821`), preserving both
branches' history. Upstream changes are not automatically merged into `dev`.

The design target is the existing `index-v9.html`, adapted to the current
native Kotlin/Compose application. This is production UI work, not a new web
prototype. The old audit and guide are historical: Gateway's channels,
accounts, mappings and model-picker integration already exist; the conditional
plan strip and mutually exclusive drawers also exist.

## Implemented behavior

- Four home destinations: chats, tools, tasks and settings. Only exact root
  routes show the compact bottom navigation; wide screens use a rail. Chat,
  editors and detail screens retain their full area and original navigation.
- Settings home groups existing features behind category pages. A shared
  catalog preserves all 33 original feature destinations, including activity,
  sheet and external-link actions. Appearance remains a direct shortcut.
- Runtime parameters have a real destination. Slider changes stay in a local
  draft until Save; dismissing or cancelling discards the draft.
- Descriptive help appears beside labels as a small question-mark icon;
  pressing it opens contextual help. Outside clicks and Back dismiss the
  popup. Current values, errors and status remain visible. Small glyphs keep
  a 48dp touch target and a translated accessibility label.
- A single toolbar search entry replaces the duplicate floating search
  button. Folder creation and model-group shortcuts remain available.
- Chat's overflow actions use category drilldowns; send mode uses a small
  icon and explicit choices. Existing action handlers and permission semantics
  remain the authority.
- The composer collects photo/media/file selection, expanded editing, commands
  and voice language in one insertion sheet. Existing picker dismissal,
  editor/caret and permission callbacks remain in their original owners.
  A compact permission-mode icon stays visible and opens the session controls.
  Send-mode state is shared reactively between mounted chats.

## Source comparison

OmniBot is a design/behavior reference, not a source-code dependency. Its
[settings grouping](https://github.com/omnimind-ai/OmniBot/blob/5addd9be2078acdd92b468bf3270923a94bc3470/ui/lib/features/home/pages/settings/settings_page.dart)
and [connection revision checks](https://github.com/omnimind-ai/OmniBot/blob/5addd9be2078acdd92b468bf3270923a94bc3470/ui/lib/services/model_provider_config_service.dart)
informed independent implementations. OpenMinis already has provider tests,
typed refresh failures and Gateway management, so those systems are reused.
No OmniBot code/assets or stored credentials are copied.

The model-catalog race found during comparison is covered separately: an old
request must not publish after its connection or credential revision changes,
or after its provider is removed. This does not change configured providers,
URLs, selected models or secrets.

## Verification

Use JDK 17 and the repository Gradle wrapper:

```sh
cd src/android
./gradlew :app:compileReleaseKotlin :app:testReleaseUnitTest -Pminis.skipRootfsAssetGuard=true
./gradlew :app:compileReleaseAndroidTestKotlin -Pminis.uiTests=true -Pminis.skipMinifyForTests=true
```

`minis.uiTests=true` creates a separate `.uitest` package with a plain Android
Application and Compose test host. It excludes native sandbox binaries and
rootfs guards so real Compose controls can run on an x86 emulator. This is a
UI test fixture, not a distributable working agent. Ordinary release builds
retain native builds/guards, signing, package identity and application startup.

From the repository root, run resource references, architecture/CJK ratchets,
schema drift and localization gates. New UI text has real translations in
all 17 published resource directories. Localization compares committed refs.

Device acceptance must exercise category/search navigation and restoration,
tab selection, phone/tablet layout, dark/light mode, enlarged fonts, help
open/outside/Back, runtime Cancel/Save, chat menu categories and send-mode
selection. A screenshot of `index-v9.html` is reference capture only; it does
not verify the Android implementation. Build/test/device results are recorded
in `verification-2026-11-19.md` after execution.
