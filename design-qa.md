# Minis Ultra native UI QA — 2026-11-20

## Evidence and scope

Source: `docs/ui-redesign/index-v9.html`, captured at 1600×2000px. The settings
phone-content crop is 326×690px. Implementation captures are real Compose
screens on Android API35 x86_64, 1080×2300px at480dpi (360×766dp), saved in
`docs/ui-redesign/verification-2026-11-20/`.

The reference crop and latest settings capture were placed together at
360×766px each in `G:/project/minis ultra/.review/qa-comparison.png` for visual
comparison. This normalizes scale but retains the Android system status and
navigation areas; those differ from the source's iOS chrome.

## Checked surfaces

- Typography: native Android font fallback,28sp root title,15sp row labels,
  compact helper/value text. Settings light/dark and1.5× font-scale captures
  have readable labels and scrollable overflow.
- Spacing/layout: inset grouped cards, small glyphs, independent48dp help
  targets, floating four-tab navigation and full-screen runtime details.
- Colors/assets: semantic light/dark surfaces and real Material library icons.
  App-owned imagery was not substituted with generated drawings.
- Copy/navigation:33 existing settings features remain in localized categories;
  descriptions move behind question marks while actual values stay visible.

## Comparison history

1. The first settings capture had an unnecessary root back arrow and an empty
   top-bar gap. Root navigation was suppressed and the empty large-title bar
   removed. The latest `settings-light.png` records the corrected layout.
2. The first runtime capture preceded compositor presentation and was blank.
   The screenshot helper now waits350ms after Compose and Android idle;
   `runtime-light.png` now visibly shows the actual saved controls.
3. Initial interaction failures exposed an unobserved state read in the
   navigation test, early injected Back, and a40dp permission button. State is
   read during composition, Back waits for the sheet, and the production
   permission target is48dp. The source-entry pane reporter also preserves
   reports arriving before the outer navigation flow updates.

Latest device run: **15 tests passed** in38.262s. Covered help open/outside/Back,
parent-action isolation, default/custom back, runtime Cancel/Save, phone pane
visibility, cold-chat tab anchoring/state restoration, globally shared send
mode, six insertion actions/dismissal order, sheet Back and48dp touch targets.
No actionable P0/P1/P2 remained in this tested scope.

## Accepted differences and remaining coverage

The current functional grouping uses four root destinations and an Agent hub,
rather than reproducing the source's static labels exactly. Native icon tiles,
Android system chrome and the existing app accent are retained. Real-time
frosted blur and collapsing large titles remain optional visual follow-ups.
Full arm64 sandbox startup, real model calls, camera/media picker end-to-end,
physical-device OEM behavior and tablet screenshots were not covered by the
isolated x86 UI host. The test APK is not the production agent package.

`final result: passed`
