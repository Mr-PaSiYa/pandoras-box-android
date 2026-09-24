# Pandora's Box

Pandora's Box is a modern, feature-rich Android media downloader powered by `yt-dlp` and `FFmpeg`. Designed with a clean dark interface, it allows users to download videos, extract audio, manage download queues, and post-process media directly on their Android devices.

---

## Features

- **Video & Audio Downloads**: Download videos in multiple resolutions (up to 1080p / Best) or extract audio in formats like MP3, M4A, FLAC, WAV, and AAC.
- **Playlist & Batch Processing**: Fetch playlist entries and selectively queue individual videos or entire playlists.
- **Concurrent Download Queue**: Manage active downloads with pause, resume, and cancellation support handled reliably via a Foreground Service.
- **Media Trimming & Post-Processing**: Trim video or audio using custom timestamps and automatically embed metadata/thumbnails using FFmpeg and Mutagen.
- **Download History**: Keep track of completed downloads with quick actions to play, open, or delete files.
- **Polished Dark UI**: Clean, responsive layout with smooth tab transitions and custom animated bottom navigation.
- **In-App Auto Updates**: Check for latest app releases and automatically update the application directly within the app.
- **In-App Engine Updates**: Update the underlying `yt-dlp` python package directly from the settings tab to maintain compatibility.

---

## Screenshots

*(Screenshots coming soon)*

| Download Screen | Queue & History | Settings & Updates |
|:---:|:---:|:---:|
| *Placeholder* | *Placeholder* | *Placeholder* |

---

## Tech Stack

- **Language**: Kotlin, Python 3.14
- **Architecture**: Android Jetpack Components, Coroutines & StateFlow
- **Python Bridge**: [Chaquopy](https://chaquo.com/chaquopy/)
- **Core Libraries**:
  - [yt-dlp](https://github.com/yt-dlp/yt-dlp) for media extraction
  - [FFmpeg](https://ffmpeg.org/) for media encoding, trimming, and merging
  - [Mutagen](https://mutagen.readthedocs.io/) for audio metadata tagging
  - [Coil](https://coil-kt.github.io/coil/) for image and thumbnail loading
  - Material Components & ConstraintLayout for UI

---

## Requirements & Building

### Requirements

- **Android Studio**: Ladybug / Jellyfish or newer
- **JDK**: Java 17+
- **Android SDK**: `minSdk 24` (Android 7.0+), `targetSdk 34` (Android 14)
- **Target ABI**: `arm64-v8a`
- **Python**: Python 3.x installed on host machine (required by Chaquopy during build)

### How to Build

1. **Clone the repository**:
   ```bash
   git clone https://github.com/Dark-PandorasBox/pandoras-box-android.git
   cd pandoras-box-android
   ```

2. **Open in Android Studio**:
   Open the project directory in Android Studio and allow Gradle to sync.

3. **Build APK**:
   Build via Android Studio or run from terminal:
   ```bash
   ./gradlew assembleDebug
   ```
   The generated APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Credits & References

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) - Feature-rich command-line audio/video downloader
- [Chaquopy](https://chaquo.com/chaquopy/) - Python SDK for Android
- [FFmpeg](https://ffmpeg.org/) - Cross-platform solution to record, convert and stream audio and video
- GitHub Repository: [PandorasBox/pandoras-box-android](https://github.com/Mr-PaSiYa/pandoras-box-android)

---

## License

This project is open-source under the [MIT License](LICENSE).
