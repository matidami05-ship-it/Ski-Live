# Ski Live

Live Activities for Android. Live scores, timers, ride and delivery trackers and custom cards that sit on your lock screen and update in place — plus a floating pill at the top of the screen for incoming notifications, other apps' live notifications and one-time codes.

## Where it shows up

- **Android 16 Live Updates** — lock screen card with a segmented progress bar, the status bar chip (score, minutes left, code) and Samsung's **Now Bar** on One UI 8.
- **Older Android** — a live, ongoing notification with a progress bar.
- **The pill** — a black floating pill (top-left by default) that shows, in order: a notification that just arrived, a one-time code (tap to copy), your Ski Live activities, and other apps' live notifications (Uber, Maps navigation, calls, timers, downloads). Tap to expand, hold to dismiss.

## Live activities

| Kind | What it does |
| --- | --- |
| Game | Follow any NBA, NFL, MLB, NHL, college, WNBA or soccer game. Score, clock, last play and team logos, refreshed every 15 seconds while live. Star a team and its games start on their own. |
| Timer | Countdown on the lock screen with a "Time's up" alert. |
| Ride / order | Ride, food delivery, pickup, package or flight with stages (Driver assigned → On the way → Arriving) that move along until the ETA. |
| Custom | Any title, text, chip, countdown or progress. |
| Code | Created automatically when a login code arrives in a text or email. |

## Setup on the phone

1. Install `SkiLive.apk` from the latest release.
2. Open the app and tap **Allow** on each row of *Finish setup*: notifications, live notifications, the floating pill, and notification access.
3. Notification access greyed out? Android blocks it for APK installs. Open **App info → ⋮ → Allow restricted settings**, then try again.

## Use it from other apps

Any app (Ski Assistant, Scoreline, Tasker, adb) can drive a live activity with a broadcast:

```sh
adb shell am broadcast -a com.ski.live.action.UPDATE -n com.ski.live/.live.ApiReceiver \
  --es id pizza --es kind tracker --es title "Pizza" --es icon food \
  --ei eta_minutes 25 --es steps "Ordered,Baking,On the way,Delivered"

adb shell am broadcast -a com.ski.live.action.END -n com.ski.live/.live.ApiReceiver --es id pizza
```

Extras: `id`, `kind` (`custom`, `timer`, `tracker`), `title`, `text`, `detail`, `chip`, `progress` (0–100), `eta_minutes` or `end_at` (epoch ms), `steps` (comma separated), `icon` (`car`, `truck`, `food`, `bag`, `plane`, `timer`, `ball`, `key`, `star`), `source`. Sending `UPDATE` again with the same `id` updates it.

## Privacy

Notification reading happens only on the phone. Nothing is uploaded; the only network calls are to ESPN's public scoreboard for games you follow and to fetch team logos.

## Build

CI builds the APK on every push and publishes it on the Releases page. Locally: `./gradlew assembleRelease`.
