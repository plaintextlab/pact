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
- **Android widget**: configurable. Choose Today, the next 7 days, Inbox, All or one group; whether to include subtasks and completed tasks; the background opacity; and a title.

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

The full list of examples is in [spec/nlp_cases.json](spec/nlp_cases.json). The Rust and Kotlin parsers are both tested against it.

## Sync

Your data folder is a plain git repository with one JSON file per task (see [spec/data-format.md](spec/data-format.md)).
Create an empty private repository on GitHub, GitLab or Gitea, then put its URL in Settings on each device.

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
