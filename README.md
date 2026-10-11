outbound-scan

A minimal Android app (Kotlin) that listens for PDA scan broadcasts and shows mock item info.

Quick run (from Android Studio):

1. Open the project in Android Studio.
2. Build and run on a device with scan broadcasts or use `adb` to send test intents.

Test intent example:

```bash
adb shell am broadcast -a android.intent.action.SCANRESULT --es value "TEST12345"
```
# outbound-scan
