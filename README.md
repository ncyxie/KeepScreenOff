# Keep Screen Off

Xposed module that stops selected apps from holding the screen awake. The
display turns off on your system screen-timeout instead.

Handles both the `FLAG_KEEP_SCREEN_ON` window flag and screen wakelocks, which
video players acquire during playback. `PARTIAL_WAKE_LOCK` is left alone, so
background audio and downloads are unaffected.

## Requirements

- LSPosed or Vector, with libxposed API 102
- Android 8.0+

## Usage

Enable the module, scope it to the apps you want, then force-stop them.

The system framework does not need to be scoped, as every hook runs in the app's own process.

## Limitations

Views that set `keepScreenOn` in XML are not covered; `ViewRootImpl` aggregates
that into the window flags below every hook here.

## Building

```
./gradlew assembleRelease
```

## Credits

Maintained by ncyxie. Originally by [w311ang](https://github.com/w311ang),
forked from [VarunS2002/Xposed-Disable-FLAG_SECURE](https://github.com/VarunS2002/Xposed-Disable-FLAG_SECURE),
since rewritten for libxposed API 102.

## License

GPL-3.0 — see [LICENSE](LICENSE).
