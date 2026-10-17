//! Reminder scheduling, desktop notifications and the urgent-task alarm sound.

use std::collections::HashSet;
use std::time::Duration;

use chrono::{Duration as CDuration, NaiveDateTime, NaiveTime};
use pact_core::deadline::{self, DeadlineConfig};
use pact_core::model::Task;
use pact_core::settings::format_offset;
use pact_core::store::Store;

/// Fire reminders that were missed by at most this much (e.g. while asleep).
const GRACE_MINUTES: i64 = 15;

#[derive(Clone)]
pub struct Fire {
    pub task_id: String,
    pub title: String,
    pub body: String,
    pub alarm: bool,
    /// An event occurrence (no "Complete").
    pub is_event: bool,
}

#[derive(Default)]
pub struct Reminders {
    fired: HashSet<String>,
    snoozed: Vec<(Fire, NaiveDateTime)>,
}

impl Reminders {
    pub fn snooze(&mut self, fire: Fire, until: NaiveDateTime) {
        self.snoozed.push((fire, until));
    }

    /// Events due now. All-day tasks use `all_day_time` as their reminder anchor.
    /// `deadlines` is None when escalating deadline reminders are off.
    pub fn check(&mut self, store: &Store, now: NaiveDateTime, all_day_time: NaiveTime, deadlines: Option<&DeadlineConfig>) -> Vec<Fire> {
        let mut out = vec![];
        let window_start = now - CDuration::minutes(GRACE_MINUTES);
        if let Some(cfg) = deadlines {
            for t in store.live_tasks().filter(|t| !t.completed) {
                let Some(d) = t.deadline() else { continue };
                let due = deadline::moment(d, t.deadline_clock(), cfg);
                // Only the latest missed alert fires, so waking the laptop doesn't replay a backlog.
                let Some(at) = deadline::alerts(due, cfg).into_iter().filter(|a| *a <= now && *a > window_start).last() else { continue };
                if self.fired.insert(format!("d|{}|{}", t.id, at)) {
                    let when = format!("{}{}", d.format("%a %-d %b"), t.deadline_clock().map(|c| format!(" {}", crate::list::fmt_time(c))).unwrap_or_default());
                    out.push(Fire {
                        task_id: t.id.clone(),
                        title: format!("⚑ {}", t.title),
                        body: format!("{} · {when}", deadline::message(due, now)),
                        alarm: t.urgent && at == due,
                        is_event: false,
                    });
                }
            }
        }
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
        for e in store.live_events().filter(|e| !e.reminders.is_empty() || e.urgent) {
            let mut offsets = e.reminders.clone();
            if e.urgent && !offsets.contains(&0) {
                offsets.push(0);
            }
            for o in e.occurrences(now.date() - CDuration::days(1), now.date() + CDuration::days(2)) {
                let start = if o.all_day { o.date().and_time(all_day_time) } else { o.start };
                for off in &offsets {
                    let at = start - CDuration::minutes(*off);
                    if at > now || at <= window_start {
                        continue;
                    }
                    if self.fired.insert(format!("e|{}|{}|{}", e.id, o.start, off)) {
                        let lead = if *off == 0 { "Now".to_string() } else { format!("Starts in {}", format_offset(*off)) };
                        let when = if o.all_day { "All day".to_string() } else { format!("{}–{}", crate::list::fmt_time(o.start.time()), crate::list::fmt_time(o.end.time())) };
                        let body = [lead, when, e.location.clone()].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" · ");
                        out.push(Fire { task_id: e.id.clone(), title: e.title.clone(), body, alarm: e.urgent, is_event: true });
                    }
                }
            }
        }
        let (ready, waiting): (Vec<_>, Vec<_>) = self.snoozed.drain(..).partition(|(_, at)| *at <= now);
        self.snoozed = waiting;
        for (f, _) in ready {
            // Snoozed tasks that got completed in the meantime stay quiet.
            if f.is_event || store.task(&f.task_id).is_some_and(|t| !t.completed) {
                out.push(f);
            }
        }
        out
    }
}

fn fire_for(t: &Task, offset: i64) -> Fire {
    let when = match (offset, t.time()) {
        (0, Some(time)) => format!("Due now ({})", crate::list::fmt_time(time)),
        (0, None) => "Due today".to_string(),
        (m, _) => format!("Due in {}", format_offset(m)),
    };
    let body = if t.notes.trim().is_empty() { when } else { format!("{when}\n{}", t.notes.lines().next().unwrap_or("")) };
    Fire { task_id: t.id.clone(), title: t.title.clone(), body, alarm: t.urgent, is_event: false }
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
