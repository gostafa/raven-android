# Raven Android UI

The Android interface follows the adjacent `gostafa/raven` desktop app, adapted to Android navigation and touch targets.

## Appearance

- App surfaces, editors, menus, dialogs, and system-bar backgrounds are black (`#000000`).
- Primary controls are white with black content. Muted text uses `#A3A3A3`; borders use `#303030`.
- Orange folder progress, recall colors, and error colors remain available alongside textual status.
- Fredericka the Great is bundled for display headings. Its OFL license is included in the app assets. Arabic headings use the system sans-serif family.
- Phone layouts use one column; windows at least 600dp wide use adaptive 300dp columns. Large font settings use one column.

## Browsing and transfers

Folder sorting supports tag count, card count, and next review. Selected tags must all match. Card sorting supports creation time, next review, and recall. Media types match any selected type; the recall range is inclusive and combines with media filters.

Use Select or long-press a tile to select items. Selection follows visible results, and Back exits selection. Selected folders can be exported through the existing ZIP workflow. Cards can be moved, copied, pasted, and reviewed individually or in groups.

The clipboard belongs to the running app and survives screen navigation and activity recreation. Copying does not change the source. A paste creates new card and part identities, copies media into independently owned files, and preserves review progress and archive state. Moves preserve Android identities. Moving to the same folder makes no change. Each batch validates all references and commits atomically; failed copies remove staged media.

No database schema migration or archive format change is required. Sorting metadata comes from existing stored creation times and review due times.

## Floating companion

Settings contains an optional floating companion, disabled by default. Grant Display over other apps, then choose Start companion while Raven is open. The bubble shows the due-item count, snaps to either screen edge after dragging, and remembers its vertical position.

The bubble hides while Raven is visible. Tapping it opens the normal due-review screen. Stop companion in Settings or in its ongoing notification removes the bubble and stops its service. Permission revocation also stops the service. The service is not started at boot and is not automatically restarted after process death.

Android 14 and later use the `specialUse` foreground-service type with a manifest explanation. Distribution through Google Play would additionally require the corresponding foreground-service declaration and review. Existing APK distribution is unchanged.

## Verification

Run from the repository root:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

On memory-constrained machines, add `--no-parallel --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process`.

`BrowserUiTest` checks restoration, filtering, transfer workflows, and captures representative library, folder, settings, and filter surfaces. Its visual tests accept instrumentation arguments `uiProfile`, `uiLocale`, and `uiFontScale`; screenshots are written to the app's external files directory. `CompanionUiTest` checks visibility, review navigation, permission revocation, dragging, rotation, and the notification Stop action on an emulator.
