# Development

Plain Java with no Gradle and no AndroidX. The package is `dev.livingsky`, and it runs on Android 12 (API 31) and up.

## Build

```sh
./build.sh            # build/living-sky.apk
./build.sh install    # also installs over adb, grants location and re-renders
./build.sh desktop    # runs the sky shaders on the PC → build/desktop-out
./build.sh test       # the weather invariants on the PC (tools/TimelineTest)
./build.sh live       # live data for Ulm, Barcelona, Malgrat, Chicago through the same code (tools/LiveHarness)
```

| Variable | Default |
|---|---|
| `ANDROID_SDK` | `$ANDROID_HOME`, then `~/Android/Sdk` |
| `BUILD_TOOLS` | `35.0.0` |
| `PLATFORM` | `android-36` |
| `JAVA_TOOL` | Empty, so the build uses the `javac` on your PATH. Set it to a command prefix such as `mise x java@21 --`. |
| `KEYSTORE` | `keystore/debug.keystore`. The build creates it if it doesn't exist. Keep it: updates must be signed with the same key. |
| `ADB_SERIAL` | The first device attached |

## Code

```
src/dev/livingsky/
  render/          pure Java, also runs on the desktop
    SkyShader        the card's sky: clouds, sun, stars, fog, rain, snow
    BandShader       the timeline's sky, hour by hour
    DaylightGrade    lights the skyline by day (sun side, shade, materials)
    CloudLayer       broken cloud at every hour: soft, see-through, lit by the sun or the moon
    Sun, Moon        real positions, moon phase and the tilt of its lit side
    Timeline         the one weather timeline: now, 15-min slots, hours, days
    WxClass          the one classifier, with every threshold
    Wx, Ensemble,    the parsed sources (pure Java, shared with the desktop tools)
    NowObs, Sources
  Renderer         draws the card bitmap and the timeline bitmap
  WidgetUpdater    builds the RemoteViews: card, clock, timeline list, taps
  Updater          location → forecast → skyline → render → push
  WeatherClient    Open-Meteo forecast (best_match)
  EnsembleClient   Open-Meteo ensemble (40 runs): the chance of rain or snow from +3 h
  NowClient        Germany: DWD radar and the nearest station, via Bright Sky
  Places           picks the nearest hand-drawn city, else the automatic skyline
  Scheduler        re-renders every quarter hour, fetches the forecast every 30 min
  DebugReceiver    adb commands, listed below
autoskyline/       automatic skylines from OpenStreetMap
assets/skylines/   hand-drawn cities (see SKYLINES.md)
```

Notes:

- The clock is a bitmap redrawn every minute, because launchers ignore fonts bundled with an app.
- One `Timeline` feeds the word, the card, the column and the strip, through one classifier (`WxClass`), so they can't disagree.
  - Now: in Germany the DWD radar, checked against the nearest station; elsewhere the 15-min forecast.
  - Next 2 h: radar nowcast (Germany), then the 15-min forecast. From 3 h: the ensemble's chance (P).
  - Bars: 20 % faint, 50 % solid. Strip day: rain icon if any hour is solid, "chance" icon if 2+ hours are faint.
- The alarm pill shows only alarms from a clock app (Google Clock, or any app that handles "set an alarm"). Calendar and meeting apps can register reminders as alarms too; those are left out and logged (`adb logcat -s LivingSky`) as `not a clock app: hidden`.

## Debug

Only adb can send these commands:

```sh
adb shell am broadcast -a dev.livingsky.DEBUG -n dev.livingsky/.DebugReceiver \
  --es place ulm --es scenario rain --es at 15:00 --es cmd render
```

| Extra | Values |
|---|---|
| `place` | `live`, `auto`, or a city id |
| `lat`, `lon`, `name` | The location for `place auto` |
| `scenario` | `clear` `partly` `overcast` `fog` `drizzle` `rain` `heavy` `showers` `thunder` `hail` `sleet` `snow` `heavysnow` `wind`, or `none` |
| `mode` | `auto`, `day` or `night` |
| `at` | A time such as `07:45`, or `none` |
| `alarm` | A time such as `'Mon 07:30'`, or `none` or `real` |
| `colmode` | `bars` or `values` |
| `clouds` | `old` shows the previous cartoon clouds and the hard day/night switch (for before/after images); `new` is the default |
| `moonage` | The moon's age in days, e.g. `7.4` for first quarter (`0` new, `14.8` full), or `none` |
| `raindebug` | `true` logs the timeline per hour (`adb logcat -s LivingSky`): source (`radar`, `obs`, `det`, `ens`, `blend`), chance, rate, cloud |
| `dump` | `true` writes `preview.png` and `column.png` to `Android/data/dev.livingsky/files/` |
| `cmd` | `render`, `refresh` or `hosted` (renders the real widget offscreen to `hosted.png`) |

To return to live data:

```sh
--ez reset true --es place live --es scenario none --es mode auto --es at none --es alarm real --es cmd render
```

## Tools

- `tools/readme_shots.sh` renders the images in `docs/img`.
- `tools/verify_skylines.sh` renders every city by day and at night.
- `tools/weather_shots.sh` renders every weather state.
- `tools/health.sh` shows the placed widgets, recent renders, errors and scheduled jobs.
