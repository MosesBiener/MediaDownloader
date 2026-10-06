# Media Downloader

Standalone Android media downloader UI backed by yt-dlp and FFmpeg.

## v1 features
- Video, audio, and captions modes
- Quality and output-format selectors
- Custom output folder via Android Storage Access Framework
- Default output folder: `Downloads/Media Downloader`
- Optional per-download subfolder
- Optional subtitle embedding for video
- Foreground download service with progress notification
- Share-to-app URL handling
- ARM64 Android 7+ build

## Build
Run the included GitHub Action or:

```bash
gradle :app:assembleDebug
```

The installable APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Licensing
This v1 uses `youtubedl-android` (GPL-3.0) and FFmpegKit's LGPL variant. If distributing publicly, review and comply with all upstream license notices and the terms of the websites from which users download media.
