# Android Video and Audio Downloader app with browser, player and custom downloaders

## Disclaimer

This project was created for research and educational purposes to explore the downloading of a wide variety of video formats and stream types. The developer does not take any responsibility for illegal actions performed by users of this application.

[![F-Droid](https://img.shields.io/f-droid/v/com.myAllVideoBrowser?color=b4eb12&label=F-Droid&logo=fdroid&logoColor=1f78d2)](https://f-droid.org/packages/com.myAllVideoBrowser)

<a href="https://f-droid.org/packages/com.myAllVideoBrowser"><img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid"></a>

## Features

- **Extensive Site Support**: Download videos from YouTube, Facebook, Twitter, Instagram, Dailymotion, Vimeo, and over [1000 other sites](http://rg3.github.io/youtube-dl/supportedsites.html).
- **Advanced Stream Interception**: Intercepts and downloads complex stream types like HLS (`.m3u8`) and DASH (`.mpd`), in addition to standard `.mp4` video streams.
- **Live Stream Downloader**: Capture and save live video streams and live audio (e.g., radio) broadcasts as they happen.
- **Full-Featured Browser**: A built-in browser to easily find content, with support for history, bookmarks, and cookies.
- **Powerful Download Manager**: Manages all your download tasks in the background.
- **Offline Playback**: Play downloaded videos and audio offline with the integrated player.
- **Advanced Networking**:
  - **Proxy Chaining**: Route your traffic through multiple proxies (`HTTP`, `SOCKS`) for enhanced privacy.
  - **Encrypted DNS**: Secure your DNS queries with **DNS-over-HTTPS (DoH)** and **DNS-over-TLS (DoT)**, supporting standard and encrypted `sdns://` URLs.

Thanks
to [@cuongpm](https://github.com/cuongpm), [@yausername](https://github.com/yausername) and [@JunkFood02](https://github.com/JunkFood02)

Inspired from [cuongpm/youtube-dl-android](https://github.com/cuongpm/youtube-dl-android)

## Screenshots

<img src="screenshots/screenshot_1.png" width="170"> <img src="screenshots/screenshot_2.png" width="170"> <img src="screenshots/screenshot_3.png" width="170"> <img src="screenshots/screenshot_4.png" width="170">
<img src="screenshots/screenshot_5.png" width="170"> <img src="screenshots/screenshot_6.png" width="170"> <img src="screenshots/screenshot_7.png" width="170"> <img src="screenshots/screenshot_8.png" width="520">

## Translations

Please help with translations using [Weblate](https://toolate.othing.xyz/projects/super-video-downloader/).

<a href="https://toolate.othing.xyz/projects/super-video-downloader/">
<img alt="Translation status" src="https://toolate.othing.xyz/widget/super-video-downloader/multi-auto.svg"/>
</a>

## Major technologies

- **Language**: Kotlin first
- **Architecture**: MVVM (ViewModel, LiveData) with Repository Pattern
- **UI**: Android Views with DataBinding
- **Dependency Injection**: Dagger 2
- **Concurrency**: Coroutines & RxJava
- **Database**: Room
- **Networking**: OkHttp, libv2ray

# How to run
A compact set of copy-paste commands to build the app on different OSes.

- Build debug APK (macOS / Linux):

```bash
# from repository root
./gradlew :app:assembleDebug
```

- Build debug APK (Windows CMD/PowerShell):

```powershell
# from repository root (PowerShell)
.\\gradlew.bat :app:assembleDebug
```

- Vendor Go dependencies only:

```bash
./gradlew :app:vendorGoDependencies
```

- Clean build artifacts:

```bash
./gradlew clean
```

- If `go` is not on PATH, override the detected `go` binary:

```bash
# macOS / Linux
export GO_EXECUTABLE=/opt/homebrew/bin/go
./gradlew :app:vendorGoDependencies

# Or pass as Gradle property
./gradlew -PGO_EXECUTABLE=/opt/homebrew/bin/go :app:vendorGoDependencies
```

## License

This package is licensed under the [LICENSE](./LICENSE) for details.

## Donate
You can support the project by donating to the address below.

| Type | Address |
| :--- | :--- |
| <img src="https://en.bitcoin.it/w/images/en/2/29/BC_Logo_.png" alt="Bitcoin" width="50"/> | `bc1q97xgwurjf2p5at9kzm96fkxymf3rh6gfmfq8fj` |
