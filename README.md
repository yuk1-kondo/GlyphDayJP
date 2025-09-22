# GlyphDayJP

[⬇️ Download APK (weekdayDevice release)](https://github.com/yuk1-kondo/GlyphDayJP/raw/main/dist/GlyphDayJP-weekdayDevice-release.apk)

A Glyph Toy for Nothing Phone that displays "weekday kanji characters" on the Glyph Matrix. Based on the device date, it renders the current day's kanji character (`日月火水木金土`) with anti-aliasing within a 25×25 circular area.

## Features

- **Daily Display**: Automatically selects and displays the weekday kanji based on device date
- **Long Press**: Toggle inverted display (white/black inversion)
- **Shake Gesture**: Particle collapse animation (returns to current day's kanji after 5 seconds)
- **AOD Support**: Animation stops and shows static display during Always-On Display
- **Auto Update**: Automatically switches to next day's kanji at midnight

## Project Structure

- `app/src/main/java/.../MainActivity.java`: Simple preview UI for emulator
- `app/src/weekdayDevice/.../RippleWaveToyService.java`: Real device toy service (Glyph SDK integration)
- `app/src/main/java/.../WeekdayPreviewActivity.java`: Weekday preview screen
- `app/src/main/AndroidManifest.xml`: Toy registration (`com.nothing.glyph.TOY`) / permissions

## Build & Installation

1. Open the project in Android Studio
2. Place Glyph Matrix SDK (`glyph-matrix-sdk-1.0.aar`) in `app/libs/`
3. Build APK for real device and install on Nothing Phone

## Usage

### On Real Device
1. Go to Nothing Phone Settings > Glyph Interface > Glyph Toys and select "GlyphDayJP"
2. Short press the Glyph Button to cycle through toys
3. Long press to toggle white/black inverted display
4. Shake the device to start particle animation

### Emulator Preview
1. Launch the app and tap "Weekday Preview" button
2. View all weekday kanji characters

## Technical Specifications

- **Display Area**: 25×25 pixels (circular mask applied)
- **Font**: System default bold
- **Anti-aliasing**: Enabled
- **Animation**: Particle falling effect (40fps)
- **Sensors**: Accelerometer (shake detection)

## Development Environment

- **Android Studio**: Arctic Fox or later
- **Min SDK**: API 21 (Android 5.0)
- **Target SDK**: API 34 (Android 14)
- **Glyph Matrix SDK**: 1.0

## License

This project is created using the Glyph Matrix Developer Kit.

## Contributing

Please report bugs and feature requests in [Issues](https://github.com/yuk1-kondo/GlyphDayJP/issues).

## Author

[yuk1-kondo](https://github.com/yuk1-kondo)

---

**Note**: This toy uses Nothing Phone's Glyph Matrix functionality. It only works on compatible devices.