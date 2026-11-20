# Verification record — completed2026-11-20

- Normal release Kotlin compile passed. The isolated UI-host APK and test APK
  also compiled/packaged successfully with JDK17,SDK36,NDK29 and CMake3.22.1.
- Full JVM suite:6685 tests,1 failure,6 skipped. The sole failure was an existing
  source-location assertion after moving the permission action into the new
  component. It was repaired and passed in the final targeted run.
- Fresh focused Gradle suite:28 tests passed. Independent final JVM run:24 tests
  passed, including the repaired permission assertion, external-picker handoff,
  request/publication guards and header preload. These sets overlap.
- Actual Android emulator run:15 tests passed,0 failures,38.262s. Captures and
  interaction coverage are detailed in `design-qa.md`.
- Resource references/duplicates, architecture/CJK ratchets, schema drift,
  thinking labels, announced tools, tool execution/title contracts passed.
  MCP stdio suite16 tests passed; installed asset copies matched.
- Localization gate:all13 new keys translated across default English and16
  translated locale resource directories.
- New Python duplicate-resource regression tests:2 passed.

The UI host is an opt-in`.uitest` package using a plain Application and no
native sandbox binaries. It does not replace normal application identity,
startup or production APK guards. Default sandbox androidTest compilation has
an inherited RootfsManagerInstrumentedTest.extractTar reference issue; UI-host
instrumentation deliberately compiles UI tests only. Full arm64 runtime and
live provider validation require a configured Android device.

Local task logs are in `G:/project/minis ultra/`; reproducible screenshots are
committed in `docs/ui-redesign/verification-2026-11-20/`.
