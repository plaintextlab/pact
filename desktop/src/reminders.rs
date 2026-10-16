//! Reminder scheduling, desktop notifications and the urgent-task alarm sound.

use std::collections::HashSet;
use std::time::Duration;

use chrono::{Duration as CDuration, NaiveDateTime, NaiveTime};
use pact_core::model::Task;
use pact_core::settings::format_offset;
use pact_core::store::Store;

/// Fire reminders that were missed by at most this much (e.g. while asleep).
const GRACE_MINUTES: i64 = 15;

pub struct Fire {
    pub task_id: String,
    pub title: String,
    pub body: String,
    pub alarm: bool,
}

#[derive(Default)]
pub struct Reminders {
    fired: HashSet<String>,
    snoozed: Vec<(String, NaiveDateTime)>,
}

impl Reminders {
    pub fn snooze(&mut self, task_id: &str, until: NaiveDateTime) {
        self.snoozed.push((task_id.to_string(), until));
    }

    /// Events due now. All-day tasks use `all_day_time` as their reminder anchor.
    pub fn check(&mut self, store: &Store, now: NaiveDateTime, all_day_time: NaiveTime) -> Vec<Fire> {
        let mut out = vec![];
        let window_start = now - CDuration::minutes(GRACE_MINUTES);
        for t in store.live_tasks().filter(|t| !t.completed) {
            let Some(date) = t.date() else { continue };
            let due = date.and_time(t.time().unwrap_or(all_day_time));
            let mut offsets: Vec<i64> = t.reminders.clone();
            if t.time().is_some() || t.urgent {
                offsets.push(0);
            }
            for off in offsets {
                let at = due - CDuration::minutes(off);
                if at > now || at <= window_start {
                    continue;
                }
                let key = format!("{}|{}|{}", t.id, due, off);
                if self.fired.insert(key) {
                    out.push(fire_for(t, off));
                }
            }
        }
        let (ready, waiting): (Vec<_>, Vec<_>) = self.snoozed.drain(..).partition(|(_, at)| *at <= now);
        self.snoozed = waiting;
        for (id, _) in ready {
            if let Some(t) = store.task(&id).filter(|t| !t.completed) {
                out.push(fire_for(t, 0));
            }
        }
        out
    }
}

fn fire_for(t: &Task, offset: i64) -> Fire {
    let when = match (offset, t.due_time.as_deref()) {
        (0, Some(time)) => format!("Due now ({time})"),
        (0, None) => "Due today".to_string(),
        (m, _) => format!("Due in {}", format_offset(m)),
    };
    let body = if t.notes.trim().is_empty() { when } else { format!("{when}\n{}", t.notes.lines().next().unwrap_or("")) };
    Fire { task_id: t.id.clone(), title: t.title.clone(), body, alarm: t.urgent }
}

pub fn notify(app_name: &str, title: &str, body: &str, persistent: bool) {
    let mut n = notify_rust::Notification::new();
    n.appname(app_name).summary(title).body(body);
    if persistent {
        n.timeout(notify_rust::Timeout::Never);
        #[cfg(all(unix, not(target_os = "macos")))]
        n.urgency(notify_rust::Urgency::Critical);
    }
    let _ = n.show();
}

/// A ringing alarm; dropping it stops the sound.
pub struct AlarmSound {
    _stream: rodio::OutputStream,
    sink: rodio::Sink,
}

impl AlarmSound {
    pub fn ring(seconds: u32) -> Option<Self> {
        use rodio::source::{SineWave, Source, Zero};
        let (stream, handle) = rodio::OutputStream::try_default().ok()?;
        let sink = rodio::Sink::try_new(&handle).ok()?;
        // Two-tone beeping: 880 Hz / 660 Hz, 0.25 s each, 0.5 s pause.
        for _ in 0..seconds.max(1) {
            sink.append(SineWave::new(880.0).take_duration(Duration::from_millis(250)).amplify(0.25));
            sink.append(SineWave::new(660.0).take_duration(Duration::from_millis(250)).amplify(0.25));
            sink.append(Zero::<f32>::new(1, 44_100).take_duration(Duration::from_millis(500)));
        }
        sink.play();
        Some(Self { _stream: stream, sink })
    }

    pub fn stop(&self) {
        self.sink.stop();
    }
}
