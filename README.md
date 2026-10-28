# Grabbit

**Grab any video, audio or image from the web.**

Grabbit is an Android browser with a built-in media sniffer. Open any site, play a video or song or open a photo, and the floating download button lights up with the number of files it found. Tap it, pick the quality, and the file is saved to `Download/Grabbit`, even on sites that show no download option.

## Features

- Full browser (URL bar, search, back/forward, desktop mode, fullscreen video, share-to-Grabbit from other apps).
- Media detection on two layers:
  - **Network**: every request the page makes is inspected for video/audio files and HLS (`.m3u8`) playlists. YouTube `videoplayback` streams are recognised and labelled by quality.
  - **Page**: a script injected into every page watches `<video>`, `<audio>`, `<img>`, `<source>`, Open Graph tags and anything the page plays or inserts later.
- Floating download button with a live count; a bottom sheet shows type, resolution, duration and file size for each item.
- HLS streams are expanded into their quality variants (1080p / 720p / ...) and downloaded segment by segment, including AES-128 encrypted streams and fMP4 init segments.
- Downloads run in a foreground service with the page's cookies, referer and user agent, so CDNs that block plain requests still work. Progress notifications, cancel, retry, open and delete.
- Firebase Analytics and Crashlytics.

## Build

```bash
./gradlew assembleRelease
```

Release signing reads `key.properties` (git-ignored) pointing at `keystore/grabbit-release.jks`. Without it the release build falls back to the debug key.

## Distribute testers a build

```bash
./gradlew assembleRelease
firebase appdistribution:distribute app/build/outputs/apk/release/app-release.apk \
  --app 1:600764554484:android:b73b028c123f9a4f6012a1 \
  --release-notes-file release-notes.txt --testers "dchandrachaan@gmail.com"
```

## Project layout

| Path | What |
| --- | --- |
| `app/src/main/assets/sniffer.js` | Script injected into pages to find media |
| `app/src/main/assets/home.html` | Start page with site shortcuts |
| `sniffer/` | `UrlSniffer` (URL heuristics), `MediaRegistry` (per-page list), `SnifferBridge` (JS interface) |
| `download/` | `DownloadService` (foreground downloader), `Hls` (m3u8 parser + AES), `MediaSink` (MediaStore writer), `DownloadStore` (history) |
| `ui/` | `MainActivity` (browser), `MediaSheet` (found-media sheet), `DownloadsActivity` |

## Limits

- Sites that stream through DRM or Media Source Extensions with blob URLs (e.g. most of YouTube's adaptive streams) expose only separate video-only / audio-only streams; Grabbit labels these honestly.
- Only download content you have the right to save. Respect each site's terms.

## License

MIT
