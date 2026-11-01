# Review reminders

Raven schedules one Android alarm for the earliest unhandled review occurrence. At delivery it rechecks Room and posts one notification per card, combining parts that became due together. The notification channel plays the bundled Raven sound. There is no continuously running foreground service.

The v2 reminder channel repairs bundled-sound references retained from older builds. Android does not update a registered channel's sound when the app registers it again. Migration preserves channel importance, intentional silence, custom sounds, and other notification preferences. The old channel remains available for already-visible notifications.

Delivery receipts identify a part and its due timestamp. Reopening the app, recovery work, or reboot does not alert again for the same handled occurrence. A newly scheduled due timestamp can alert again. Reviewing a visible card acknowledges its due occurrences without interrupting that card; other cards still notify.

Notification taps open only the selected card's due parts. Card and folder practice retain their existing scheduling behavior. Supporting parts are optional hints, and ratings are immediately available.

## Permissions and recovery

- A dismissible library banner introduces notification and precise alarm permissions after the first reviewable card exists. Settings contains reminder controls and system settings links.
- Without precise alarm access, Raven uses an inexact alarm. Without notification permission, it keeps occurrences pending and cancels the delivery alarm rather than retrying continuously.
- WorkManager provides unique immediate reconciliation and periodic recovery. App startup, resume, card changes, ratings, deletion, reboot, clock changes, and app updates also reconcile reminders.
- Silent mode, Do Not Disturb, and channel settings control sound. Android can throttle idle alarms and notification sounds.
- Closing the app or killing its background process preserves alarms. Android **Force stop** blocks delivery until the user reopens the app.

## Automated checks

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

Tests cover occurrence grouping, suppression, blocked delivery, receipt persistence, migration from database version 1, due counts advancing without writes, rating failures, duplicate taps, hints, large fonts, RTL, completion timing, and cold/warm notification navigation with rotation.

## Physical-device acceptance

1. Allow notifications and precise alarms. Keep the review channel audible and turn off Do Not Disturb for the sound check.
2. Create a reviewable card, rate a new part **Again**, then leave review. Its next due time is one minute later.
3. Close or swipe away Raven. Check that the card receives one notification and the Raven sound. Repeat with `adb shell am kill com.gostafa.raven` after sending the app to the background.
4. Tap the notification. Verify only due parts of that card appear. Review them and verify the notification is removed and does not return for the old due timestamp.
5. Repeat with a device reboot before the due time. Unlock the device without opening Raven and verify delivery.
6. Disable precise alarm access, reopen Raven, and repeat. Delivery may be delayed; the app should use its inexact fallback without crashing.
7. Deny notifications, let a part become due, then grant notifications and reopen Raven. Verify the pending occurrence delivers once.
8. Keep a card visible during its due time. Verify it does not interrupt itself while another newly due card still notifies.
9. Force-stop Raven and confirm delivery remains blocked. Reopen it and verify pending reminders and future alarms recover.

Confirm audible playback on a physical device in addition to checking the notification channel and emulator delivery.
