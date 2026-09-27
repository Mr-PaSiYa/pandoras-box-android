# Pandora's Box

**Pandora's Box is an open-source Android media downloader built for both everyday downloads and power-user workflows.**

Powered by [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) and [`FFmpeg`](https://ffmpeg.org/), Pandora's Box lets you extract and download supported online media, choose available video/audio formats, manage queues and playlists, extract audio, and post-process downloaded media directly on Android.

It is designed to keep the basic workflow simple while providing the controls that power users expect.

---

## Features

### Media Downloads

* Download supported online videos in available qualities, including up to 1080p / Best where provided by the source.
* Extract audio in formats such as MP3, M4A, FLAC, WAV, and AAC.
* Select available audio tracks when multiple tracks are provided.
* Use `yt-dlp`'s supported extractors and format handling.

### Queue & Background Downloads

* Multiple downloads can be managed through a centralized queue.
* Concurrent downloads.
* Pause and resume queue processing.
* Background downloads through an Android Foreground Service.
* Download progress with stage-aware overall progress.
* Download cancellation and failure handling.

### Playlists & Batch Downloads

* Extract playlist entries.
* Select individual items or entire playlists.
* Reorder playlist downloads with drag-and-drop.
* Inclusion-aware playlist numbering.

### File & Download Management

* Duplicate handling with:

  * **Skip**
  * **Rename**
  * **Overwrite**
* Download history with:

  * Thumbnail
  * File size
  * Download options
  * Original URL
  * Re-download support

### Media Processing

* FFmpeg-powered media merging and conversion.
* Audio extraction and post-processing.
* Metadata tagging with Mutagen.
* Thumbnail and metadata embedding where supported.

### Updates

* Check for new Pandora's Box releases from GitHub.
* Update the app from GitHub Releases.
* Update the bundled `yt-dlp` engine from the app's settings.

### Android Experience

* Native Kotlin + XML interface.
* Dark-focused UI.
* Responsive download queue.
* Smooth queue and navigation animations.
* Designed specifically for Android rather than wrapping a desktop downloader.

---

## Why Pandora's Box?

Pandora's Box is **not intended to be another generic download manager clone**.

The project focuses specifically on the media-download workflow:

```text
URL
 ↓
Extract media information
 ↓
Choose format / quality / audio
 ↓
Configure download
 ↓
Queue
 ↓
Download
 ↓
FFmpeg / metadata processing
 ↓
Finished media file
```

The goal is to provide a clean everyday experience without removing the controls that power users need.

---

## Screenshots

Screenshots will be added as the UI reaches release-ready status.

|   Download  |    Queue    |   History   |
| :---------: | :---------: | :---------: |
| Coming soon | Coming soon | Coming soon |

---

## Architecture

Pandora's Box uses a layered architecture:

```text
Android UI
    ↓
Download Manager / Queue
    ↓
yt-dlp Engine
    ↓
Media acquisition
    ↓
FFmpeg / Mutagen post-processing
    ↓
Final media file
```

The application uses yt-dlp for media extraction and format discovery rather than maintaining a separate extractor implementation for every supported service.

Where a supported source provides HLS/DASH or other streaming formats, Pandora's Box relies on yt-dlp's existing extraction and download handling.

---

## Tech Stack

* **Android:** Kotlin + XML
* **Minimum SDK:** 24
* **Target SDK:** 34
* **Architecture:** Android Jetpack, Coroutines, StateFlow
* **Python:** Python 3.14
* **Python Bridge:** [Chaquopy](https://chaquo.com/chaquopy/)
* **Media Extraction:** [yt-dlp](https://github.com/yt-dlp/yt-dlp)
* **Media Processing:** [FFmpeg](https://ffmpeg.org/)
* **Audio Metadata:** [Mutagen](https://mutagen.readthedocs.io/)
* **Image Loading:** [Coil](https://coil-kt.github.io/coil/)
* **UI:** Material Components + ConstraintLayout
* **Supported ABI:** `arm64-v8a`

---

## Building

### Requirements

* Android Studio
* JDK 17+
* Android SDK
* Android SDK Platform matching the project's configuration
* `arm64-v8a` build support
* Python support required by the project's Chaquopy configuration

### Clone

```bash
git clone https://github.com/Mr-PaSiYa/pandoras-box-android.git
cd pandoras-box-android
```

### Open in Android Studio

Open the project directory in Android Studio and allow Gradle to sync.

### Build

For a debug APK:

```bash
./gradlew assembleDebug
```

The APK will be generated under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

> Release builds require the project's configured signing credentials. Never commit private signing keys or passwords to the repository.

---

## Project Status

Pandora's Box is actively being developed.

The core download workflow is functional, including media extraction, format selection, queue management, background downloads, playlist handling, download history, and media post-processing.

Current development focuses on reliability, compatibility, media processing, and preparing the application for wider testing.

---

## Contributing

Contributions, bug reports, testing feedback, and improvements are welcome.

If you find a problem:

1. Check the existing issues.
2. Provide the Android version and device information.
3. Describe the URL/source type involved when relevant.
4. Include relevant application logs where possible.
5. Explain the expected and actual behavior.

Please avoid posting private URLs, authentication credentials, cookies, or other sensitive information in issues.

---

## Credits

Pandora's Box is built with the help of these open-source projects:

* [yt-dlp](https://github.com/yt-dlp/yt-dlp) — media extraction and downloading
* [FFmpeg](https://ffmpeg.org/) — media processing, merging, and conversion
* [Mutagen](https://mutagen.readthedocs.io/) — audio metadata handling
* [Chaquopy](https://chaquo.com/chaquopy/) — Python on Android
* [Coil](https://coil-kt.github.io/coil/) — image loading
* Android Jetpack and Material Components

---

## License

Pandora's Box is open-source software released under the [MIT License](LICENSE).

---

## Repository

[github.com/Mr-PaSiYa/pandoras-box-android](https://github.com/Mr-PaSiYa/pandoras-box-android)
