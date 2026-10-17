# Pact

A fully open source to-do app with natural-language quick add, git sync and Material design.
It runs on Android and on Linux, Windows and macOS.

| Folder | What it is |
|---|---|
| `core/` | Shared Rust crate: data model, natural-language date parser, recurrence, storage, git sync |
| `desktop/` | Desktop app written in Rust ([egui](https://github.com/emilk/egui)) |
| `android/` | Android app in Kotlin + Jetpack Compose (Material 3), with a home-screen widget |
| `spec/` | Files both apps share: data format, color themes, parser test cases |

## Features

- **Quick add in plain English**: `Meeting tomorrow at 10am #Work p1 remind me 15 min before`
- **Subtasks** to any depth
- **Groups** (Work, Personal, Study…), each with a color, plus `@labels` and priorities P1–P4
- **Views**: Inbox, Today, Upcoming, Calendar (repeating tasks are projected onto future days), search
- **Attachments**: images, files, text notes and voice recordings on any task or subtask
- **Reminders** a configurable number of minutes before a task is due
- **Urgent tasks** ring like an alarm clock: on Android a full-screen alarm, on desktop a ringing dialog with snooze
- **Pinned notifications** keep a task in the notification shade. On Android, if you swipe one away, it is posted again.
- **Themes**: Gruvbox (default), Everforest, Nord, Catppuccin, Tokyo Night, Dracula, Solarized, Rosé Pine, Kanagawa. Each has a light and a dark variant.
- **App name**: on desktop, any name you like. On Android, pick a launcher name: Pact, ToDo, Tasks, To-do list, Checklist or Reminders.
- **Calendar** with Month, Week, Day and Agenda views. Events have start/end times or are all-day, can span several days and repeat (daily, weekdays, weekly on chosen days, monthly, yearly, every N, until a date or N times). You can edit or delete one occurrence or the whole series. Events also have a location, a link, notes, a calendar (your groups) with its color, reminders and an optional alarm. Dated tasks show alongside events.
- **Quick add for events**: `Lunch with Sam tomorrow 1-2pm`, `Standup every weekday 9:30-9:45am #Work`, `Workshop friday from 2pm to 5pm`, `Review in 2 days at 3pm for 30 min`, `Conference 20 oct all day`
- **.ics import/export** to move events to and from Google Calendar, Outlook, Apple Calendar and others
- **Android only**: show your phone's calendars (Google, Exchange…) read-only next to Pact events; long-press the app icon for Calendar, New event and New task shortcuts; pin a Calendar icon from Settings; a **calendar widget** with a month grid you can page through, or an upcoming-events list
- **Android task widget**: configurable. Choose Today, the next 7 days, Inbox, All or one group; whether to include subtasks and completed tasks; the background opacity; and a title.

## Quick add cheat sheet

| You type | You get |
|---|---|
| `tomorrow 10am`, `tonight`, `this evening`, `friday 3pm`, `at 5` (= 17:00) | date and time |
| `next week` | Monday of next week (configurable) |
| `next month`, `next year` | the 1st, or the same day (configurable) |
| `in 2 days`, `in 3 hours`, `in half an hour`, `3 days from now`, `in a fortnight` | relative dates |
| `in 5 business days`, `in 3 working days` | skips weekends |
| `next tue`, `this weekend`, `end of month`, `mid month`, `day after tomorrow` | named dates |
| `15 march`, `dec 25th`, `20/11` (or `11/20`, configurable), `2026-12-01`, `the 5th`, `in december` | absolute dates |
| `every day`, `every weekday`, `every mon, wed and fri`, `every other week`, `every 3 months`, `every 15th`, `every last day of the month`, `every june 10`, `every morning` | repeating tasks |
| `#Work`, `@errands`, `p1`, `priority high` | group (created if new), label, priority |
| `remind me 15 min before`, `!30m` | reminders |
| `!!`, `!urgent`, `alarm 10 min before` | urgent: rings an alarm |

Events understand the same phrases plus `3-5pm`, `11am to 1pm`, `from 9 to 5`, `9:30-10:15`, `for 45 min`, `for 2 hours` and `all day`.

The full list of examples is in [spec/nlp_cases.json](spec/nlp_cases.json) and [spec/event_nlp_cases.json](spec/event_nlp_cases.json). The Rust and Kotlin parsers are both tested against them.

## Sync

Pact keeps one JSON file per task in a folder (default `pact/`) of a git repository (see [spec/data-format.md](spec/data-format.md)).
The repository can be shared with other apps, such as a single `appsync` repo holding sync data for several apps. Pact only commits its own folder.
Put the repository URL (and the same folder name) in Settings on each device.

- **Desktop** uses your installed `git`, so SSH keys and credential helpers just work.
- **Android** uses an HTTPS URL plus a username and a personal access token.

Pact commits after edits, merges the other devices' changes and pushes. If the same task was edited on two devices, the newer edit wins.

## Building

### Desktop
```bash
cargo run --release -p pact-desktop
```
On Linux, voice recording needs ALSA headers (`libasound2-dev`). To build without voice recording: `cargo build --release -p pact-desktop --no-default-features`.

Tests: `cargo test`. Demo data: `cargo run -p pact-core --example seed -- demo-data`, then start the app with `PACT_DATA_DIR=demo-data`.

### Android
Open `android/` in Android Studio, or run:
```bash
cd android && ./gradlew assembleDebug testDebugUnitTest
```
Requires JDK 17 and Android SDK 35. Minimum Android version is 8.0 (API 26).

## License
MIT
