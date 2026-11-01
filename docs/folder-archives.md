# Folder archives

The library's **Import folders** and **Export folders** actions transfer complete
folders, including cards, hints, media, and review history. Individual folder menus
also offer export. Selection ignores the library's active tag filters.

Imports always create copies. **Start from zero** uses Raven's automatic initial
schedule and clears manual history. **Continue from last review** preserves saved
history and due dates. Notification acknowledgments reset in either mode.

Compatibility targets archive version 1 in `gostafa/raven` commit
`2b4b41b3bbcfded912aa41f763aac82b0bfecf2a`. The ZIP contains `cards.json` and flat
`media/` files. Android retains portable schedules/history separately from its
runtime projection so unsigned counters and sub-millisecond timestamps survive a
resume round trip. Android continues using its existing scheduler for new reviews.

Export rejects attachment formats or file signatures that Raven does not accept.
An export snapshot includes unfiltered folder contents; shared attachments receive
separate archive entries for each item. Imports validate all metadata, stream and
check media, and commit the copied records in one Room transaction. Failed imports
remove newly staged files. Filesystem staging requires free space for the archive
and expanded media.

## Verification

From this repository, with a local desktop Raven checkout available:

```sh
python3 tools/archive-compat/verify.py --fixture app/src/androidTest/assets/desktop-folders.zip
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
python3 tools/archive-compat/verify.py \
  --input app/build/archive-compat/android-roundtrip.zip \
  --expected app/src/androidTest/assets/desktop-folders.zip \
  --output app/build/archive-compat/desktop-return.zip
python3 tools/archive-compat/verify.py --input app/build/archive-compat/android-native.zip
./gradlew :app:testDebugUnitTest --rerun-tasks
```

The Python verifier extracts the pinned commit into a temporary directory and runs
its actual ZIP reader and persistence importer, in fresh and resume modes. It
compares content, history, schedules, creation times, and media hashes, allowing
intentional identity/path remapping. The desktop working tree is unchanged.

Device service tests also write `archive-compat/android-service.zip` and
`archive-compat/android-native-service.zip` to the instrumentation additional-output
directory, which Gradle retrieves under
`app/build/outputs/connected_android_test_additional_output/`. Run the verifier
against those files to test archives produced by the Room-backed Android service.
Pass `--raven /path/to/raven` to use another local reference checkout.

The committed `desktop-native-return.zip` and `desktop-precision-return.zip` test
assets are actual Raven re-exports of Android-produced archives. Device tests
import them back into Room and compare every portable schedule, review record,
and attachment. The precision fixture includes nanosecond timestamps, counters
above JavaScript's exact integer range, and remaining-step counts above Int32.
