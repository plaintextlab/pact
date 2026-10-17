//! File-per-object storage inside the (git) data dir. See spec/data-format.md.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use chrono::{Local, NaiveDate, NaiveDateTime};
use serde::de::DeserializeOwned;
use serde::Serialize;

use crate::event::{Event, Occurrence};
use crate::ics;
use crate::model::{Attachment, AttachmentKind, Group, Task};
use crate::nlp::{self, NlpConfig};
use crate::{now_millis, recur};

const TOMBSTONE_TTL_MS: i64 = 30 * 24 * 3600 * 1000;

pub struct Store {
    pub root: PathBuf,
    pub tasks: BTreeMap<String, Task>,
    pub groups: BTreeMap<String, Group>,
    pub events: BTreeMap<String, Event>,
}

fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    let tmp = path.with_extension("json.tmp");
    let mut s = serde_json::to_string_pretty(value)?;
    s.push('\n');
    fs::write(&tmp, s)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

fn read_dir_json<T: DeserializeOwned>(dir: &Path) -> Vec<T> {
    let Ok(entries) = fs::read_dir(dir) else { return vec![] };
    entries
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "json"))
        .filter_map(|e| fs::read_to_string(e.path()).ok().and_then(|s| serde_json::from_str(&s).ok()))
        .collect()
}

fn safe_file_name(name: &str) -> String {
    let cleaned: String = name.chars().map(|c| if c.is_alphanumeric() || ".-_ ".contains(c) { c } else { '_' }).collect();
    let cleaned = cleaned.trim().trim_start_matches('.').to_string();
    if cleaned.is_empty() { "file".into() } else { cleaned }
}

impl Store {
    pub fn open(root: impl Into<PathBuf>) -> Result<Self> {
        let root = root.into();
        for d in ["tasks", "groups", "events", "attachments"] {
            fs::create_dir_all(root.join(d)).with_context(|| format!("creating {}", root.join(d).display()))?;
        }
        let attrs = root.join(".gitattributes");
        if !attrs.exists() {
            fs::write(&attrs, "*.json text eol=lf\nattachments/** binary\n")?;
        }
        let mut s = Self { root, tasks: BTreeMap::new(), groups: BTreeMap::new(), events: BTreeMap::new() };
        s.reload();
        if s.groups.is_empty() {
            // Fixed ids so two fresh devices don't create duplicate default groups.
            for (i, (id, name, color)) in [("work", "Work", "blue"), ("personal", "Personal", "green"), ("study", "Study", "purple")].into_iter().enumerate() {
                let g = Group { id: id.into(), name: name.into(), color: color.into(), order: i as i64, updated: 0, deleted: false };
                s.save_group(g)?;
            }
        }
        Ok(s)
    }

    pub fn reload(&mut self) {
        self.tasks = read_dir_json::<Task>(&self.root.join("tasks")).into_iter().map(|t| (t.id.clone(), t)).collect();
        self.groups = read_dir_json::<Group>(&self.root.join("groups")).into_iter().map(|g| (g.id.clone(), g)).collect();
        self.events = read_dir_json::<Event>(&self.root.join("events")).into_iter().map(|e| (e.id.clone(), e)).collect();
    }

    pub fn task(&self, id: &str) -> Option<&Task> {
        self.tasks.get(id).filter(|t| !t.deleted)
    }

    pub fn live_tasks(&self) -> impl Iterator<Item = &Task> {
        self.tasks.values().filter(|t| !t.deleted)
    }

    pub fn live_groups(&self) -> Vec<&Group> {
        let mut v: Vec<&Group> = self.groups.values().filter(|g| !g.deleted).collect();
        v.sort_by_key(|g| (g.order, g.name.to_lowercase()));
        v
    }

    pub fn group(&self, id: Option<&str>) -> Option<&Group> {
        id.and_then(|id| self.groups.get(id)).filter(|g| !g.deleted)
    }

    pub fn find_group_by_name(&self, name: &str) -> Option<&Group> {
        self.groups.values().find(|g| !g.deleted && g.name.eq_ignore_ascii_case(name))
    }

    /// Children of `parent` (top-level when None), in display order.
    pub fn children(&self, parent: Option<&str>) -> Vec<&Task> {
        let mut v: Vec<&Task> = self.live_tasks().filter(|t| t.parent_id.as_deref() == parent).collect();
        sort_tasks(&mut v);
        v
    }

    pub fn descendants(&self, id: &str) -> Vec<String> {
        let mut out = vec![];
        let mut stack = vec![id.to_string()];
        while let Some(cur) = stack.pop() {
            for t in self.live_tasks().filter(|t| t.parent_id.as_deref() == Some(&cur)) {
                out.push(t.id.clone());
                stack.push(t.id.clone());
            }
        }
        out
    }

    pub fn save_task(&mut self, mut t: Task) -> Result<()> {
        t.updated = now_millis();
        write_json(&self.root.join("tasks").join(format!("{}.json", t.id)), &t)?;
        self.tasks.insert(t.id.clone(), t);
        Ok(())
    }

    pub fn save_group(&mut self, mut g: Group) -> Result<()> {
        g.updated = now_millis();
        write_json(&self.root.join("groups").join(format!("{}.json", g.id)), &g)?;
        self.groups.insert(g.id.clone(), g);
        Ok(())
    }

    /// Tombstones the task and all its subtasks and removes their attachments.
    pub fn delete_task(&mut self, id: &str) -> Result<()> {
        let mut ids = self.descendants(id);
        ids.push(id.to_string());
        for id in ids {
            if let Some(mut t) = self.tasks.get(&id).cloned() {
                let _ = fs::remove_dir_all(self.root.join("attachments").join(&id));
                t.deleted = true;
                t.attachments.clear();
                self.save_task(t)?;
            }
        }
        Ok(())
    }

    /// Tombstones the group; its tasks move to the Inbox.
    pub fn delete_group(&mut self, id: &str) -> Result<()> {
        let moved: Vec<Task> = self.live_tasks().filter(|t| t.group_id.as_deref() == Some(id)).cloned().collect();
        for mut t in moved {
            t.group_id = None;
            self.save_task(t)?;
        }
        if let Some(mut g) = self.groups.get(id).cloned() {
            g.deleted = true;
            self.save_group(g)?;
        }
        Ok(())
    }

    /// Marks done/undone. A recurring task moves to its next date instead of closing.
    pub fn set_completed(&mut self, id: &str, done: bool) -> Result<()> {
        let Some(mut t) = self.task(id).cloned() else { return Ok(()) };
        let today = Local::now().date_naive();
        if done {
            let next = match (&t.recurrence, t.date()) {
                (Some(r), Some(due)) => Some(recur::advance(r, due, today)).filter(|n| r.until_date().is_none_or(|u| *n <= u)),
                _ => None,
            };
            if let Some(next) = next {
                t.set_date(Some(next));
                t.completed = false;
                t.completed_at = None;
                for sub in self.descendants(id) {
                    if let Some(mut s) = self.tasks.get(&sub).cloned() {
                        s.completed = false;
                        s.completed_at = None;
                        self.save_task(s)?;
                    }
                }
                return self.save_task(t);
            }
        }
        t.completed = done;
        t.completed_at = done.then(now_millis);
        self.save_task(t)
    }

    /// Copies `src` into the task's attachment folder.
    pub fn attach_file(&mut self, task_id: &str, src: &Path, kind: Option<AttachmentKind>) -> Result<Attachment> {
        let mut t = self.task(task_id).cloned().context("no such task")?;
        let id = uuid::Uuid::new_v4().to_string();
        let name = safe_file_name(&src.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default());
        let rel = format!("attachments/{task_id}/{}-{name}", &id[..8]);
        let dst = self.root.join(&rel);
        fs::create_dir_all(dst.parent().unwrap())?;
        fs::copy(src, &dst).with_context(|| format!("copying {}", src.display()))?;
        let a = Attachment { id, kind: kind.unwrap_or_else(|| AttachmentKind::from_path(src)), name, path: Some(rel), text: None, created: now_millis() };
        t.attachments.push(a.clone());
        self.save_task(t)?;
        Ok(a)
    }

    pub fn attach_text(&mut self, task_id: &str, name: &str, text: &str) -> Result<()> {
        let mut t = self.task(task_id).cloned().context("no such task")?;
        t.attachments.push(Attachment {
            id: uuid::Uuid::new_v4().to_string(),
            kind: AttachmentKind::Text,
            name: if name.trim().is_empty() { "Note".into() } else { name.trim().into() },
            path: None,
            text: Some(text.into()),
            created: now_millis(),
        });
        self.save_task(t)
    }

    pub fn remove_attachment(&mut self, task_id: &str, att_id: &str) -> Result<()> {
        let mut t = self.task(task_id).cloned().context("no such task")?;
        if let Some(a) = t.attachments.iter().find(|a| a.id == att_id) {
            if let Some(p) = &a.path {
                let _ = fs::remove_file(self.root.join(p));
            }
        }
        t.attachments.retain(|a| a.id != att_id);
        self.save_task(t)
    }

    pub fn attachment_path(&self, a: &Attachment) -> Option<PathBuf> {
        a.path.as_ref().map(|p| self.root.join(p))
    }

    /// Removes tombstone files older than 30 days.
    pub fn purge_tombstones(&mut self) {
        let cutoff = now_millis() - TOMBSTONE_TTL_MS;
        let old: Vec<String> = self.tasks.values().filter(|t| t.deleted && t.updated < cutoff).map(|t| t.id.clone()).collect();
        for id in old {
            let _ = fs::remove_file(self.root.join("tasks").join(format!("{id}.json")));
            self.tasks.remove(&id);
        }
        let old: Vec<String> = self.groups.values().filter(|g| g.deleted && g.updated < cutoff).map(|g| g.id.clone()).collect();
        for id in old {
            let _ = fs::remove_file(self.root.join("groups").join(format!("{id}.json")));
            self.groups.remove(&id);
        }
        let old: Vec<String> = self.events.values().filter(|e| e.deleted && e.updated < cutoff).map(|e| e.id.clone()).collect();
        for id in old {
            let _ = fs::remove_file(self.root.join("events").join(format!("{id}.json")));
            self.events.remove(&id);
        }
    }

    /// The id of the group named `name`, creating it if needed.
    pub fn group_id_for_name(&mut self, name: &str) -> Result<String> {
        if let Some(g) = self.find_group_by_name(name) {
            return Ok(g.id.clone());
        }
        let n = self.groups.len();
        let g = Group::new(name, crate::model::GROUP_COLORS[n % crate::model::GROUP_COLORS.len()]);
        let id = g.id.clone();
        self.save_group(g)?;
        Ok(id)
    }

    // ---- Events ---------------------------------------------------------------------

    pub fn event(&self, id: &str) -> Option<&Event> {
        self.events.get(id).filter(|e| !e.deleted)
    }

    pub fn live_events(&self) -> impl Iterator<Item = &Event> {
        self.events.values().filter(|e| !e.deleted)
    }

    pub fn save_event(&mut self, mut e: Event) -> Result<()> {
        e.updated = now_millis();
        write_json(&self.root.join("events").join(format!("{}.json", e.id)), &e)?;
        self.events.insert(e.id.clone(), e);
        Ok(())
    }

    pub fn delete_event(&mut self, id: &str) -> Result<()> {
        match self.events.get(id).cloned() {
            Some(mut e) => {
                e.deleted = true;
                self.save_event(e)
            }
            None => Ok(()),
        }
    }

    /// Cancels one occurrence of a recurring event.
    pub fn skip_occurrence(&mut self, id: &str, date: NaiveDate) -> Result<()> {
        let Some(mut e) = self.event(id).cloned() else { return Ok(()) };
        let d = date.format(crate::model::DATE_FMT).to_string();
        if !e.exdates.contains(&d) {
            e.exdates.push(d);
        }
        self.save_event(e)
    }

    /// Splits one occurrence off a recurring event so it can be edited on its own.
    pub fn detach_occurrence(&mut self, id: &str, date: NaiveDate) -> Result<Option<Event>> {
        let Some(series) = self.event(id).cloned() else { return Ok(None) };
        let Some(occ) = series.occurrences(date, date).into_iter().find(|o| o.date() == date) else { return Ok(None) };
        let mut one = series.clone();
        one.id = uuid::Uuid::new_v4().to_string();
        one.uid = None;
        one.recurrence = None;
        one.exdates.clear();
        one.created = now_millis();
        one.set_span(occ.start, occ.end, occ.all_day);
        self.skip_occurrence(id, date)?;
        self.save_event(one.clone())?;
        Ok(Some(one))
    }

    /// All event occurrences touching `from..=to`, all-day first, then by start.
    pub fn agenda(&self, from: NaiveDate, to: NaiveDate) -> Vec<(&Event, Occurrence)> {
        let mut v: Vec<(&Event, Occurrence)> = self.live_events().flat_map(|e| e.occurrences(from, to).into_iter().map(move |o| (e, o))).collect();
        v.sort_by(|a, b| (!a.1.all_day, a.1.start, &a.0.title).cmp(&(!b.1.all_day, b.1.start, &b.0.title)));
        v
    }

    /// Creates an event from quick-add text ("Lunch tomorrow 1-2pm #Work").
    /// `date` (e.g. the day picked in the calendar) applies when the text names no date.
    pub fn quick_add_event(&mut self, text: &str, now: NaiveDateTime, cfg: &NlpConfig, group_id: Option<String>, date: Option<NaiveDate>) -> Result<Option<Event>> {
        let mut p = nlp::parse_event(text, now, cfg);
        if p.title.is_empty() {
            return Ok(None);
        }
        if let Some(d) = date {
            if !p.date_explicit && p.recurrence.is_none() {
                let shift = d - p.date;
                p.date = d;
                p.end_date += shift;
            }
        }
        let mut e = Event::new(p.title, p.date, p.start_time);
        match (p.start_time, p.end_time) {
            (Some(st), Some(et)) => e.set_span(p.date.and_time(st), p.end_date.and_time(et), false),
            _ => e.end_date = p.end_date.format(crate::model::DATE_FMT).to_string(),
        }
        e.recurrence = p.recurrence;
        e.reminders = p.reminders;
        e.urgent = p.urgent;
        e.group_id = match p.group {
            Some(name) => Some(self.group_id_for_name(&name)?),
            None => group_id,
        };
        self.save_event(e.clone())?;
        Ok(Some(e))
    }

    /// Imports an iCalendar file. Events already known by UID (including Pact's own
    /// exported `<id>@pact` UIDs) are updated in place. Returns (added, updated).
    pub fn import_ics(&mut self, text: &str) -> Result<(usize, usize)> {
        let mut categories = vec![];
        let events = ics::import(text, |cat| {
            categories.push(cat.to_string());
            Some(format!("category:{cat}"))
        });
        let mut group_ids = vec![];
        for cat in categories {
            let id = self.group_id_for_name(&cat)?;
            group_ids.push((format!("category:{cat}"), id));
        }
        let (mut added, mut updated) = (0, 0);
        for mut e in events {
            if let Some(key) = e.group_id.take() {
                e.group_id = group_ids.iter().find(|(k, _)| *k == key).map(|(_, id)| id.clone());
            }
            let known = e.uid.as_deref().and_then(|u| {
                let own = u.strip_suffix("@pact").and_then(|id| self.events.get(id));
                own.or_else(|| self.events.values().find(|x| x.uid.as_deref() == Some(u))).map(|x| (x.id.clone(), x.created, x.uid.clone()))
            });
            match known {
                Some((id, created, uid)) => {
                    e.id = id;
                    e.created = created;
                    e.uid = uid;
                    updated += 1;
                }
                None => added += 1,
            }
            self.save_event(e)?;
        }
        Ok((added, updated))
    }

    pub fn export_ics(&self) -> String {
        let events: Vec<&Event> = self.live_events().collect();
        ics::export(&events, &self.live_groups())
    }

    /// Creates a task from quick-add text. `#Group` picks (or creates) a group.
    pub fn quick_add(&mut self, text: &str, now: NaiveDateTime, cfg: &NlpConfig, defaults: QuickAddDefaults) -> Result<Option<Task>> {
        let p = nlp::parse(text, now, cfg);
        if p.title.is_empty() {
            return Ok(None);
        }
        let mut t = Task::new(p.title);
        t.parent_id = defaults.parent_id;
        t.group_id = defaults.group_id;
        if let Some(name) = p.group {
            t.group_id = Some(self.group_id_for_name(&name)?);
        }
        t.set_date(if p.date_explicit || p.recurrence.is_some() { p.date } else { defaults.date.or(p.date) });
        t.set_time(p.time);
        t.recurrence = p.recurrence;
        t.priority = p.priority.unwrap_or(4);
        t.labels = p.labels;
        t.urgent = p.urgent;
        t.reminders = if p.reminders.is_empty() && t.due_time.is_some() { defaults.reminders } else { p.reminders };
        self.save_task(t.clone())?;
        Ok(Some(t))
    }
}

/// Pact's data folder inside a (possibly shared) sync repository.
pub fn data_dir(repo: &Path, folder: &str) -> PathBuf {
    let folder = folder.trim().trim_matches('/');
    if folder.is_empty() { repo.to_path_buf() } else { repo.join(folder) }
}

/// Moves data kept at the repository root (older layout) into `folder`.
pub fn migrate_into_folder(repo: &Path, folder: &str) -> Result<()> {
    let dst = data_dir(repo, folder);
    if dst == repo || !repo.join("tasks").is_dir() || dst.join("tasks").exists() {
        return Ok(());
    }
    fs::create_dir_all(&dst)?;
    for name in ["tasks", "groups", "attachments", ".gitattributes"] {
        let src = repo.join(name);
        if src.exists() {
            fs::rename(&src, dst.join(name)).with_context(|| format!("moving {}", src.display()))?;
        }
    }
    Ok(())
}

#[derive(Default, Clone)]
pub struct QuickAddDefaults {
    pub parent_id: Option<String>,
    pub group_id: Option<String>,
    pub date: Option<chrono::NaiveDate>,
    pub reminders: Vec<i64>,
}

/// Open first, then by due moment (undated last), priority, manual order.
pub fn sort_tasks(v: &mut [&Task]) {
    v.sort_by(|a, b| {
        a.completed
            .cmp(&b.completed)
            .then_with(|| match (a.due_at(), b.due_at()) {
                (Some(x), Some(y)) => x.cmp(&y),
                (Some(_), None) => std::cmp::Ordering::Less,
                (None, Some(_)) => std::cmp::Ordering::Greater,
                (None, None) => std::cmp::Ordering::Equal,
            })
            .then(a.priority.cmp(&b.priority))
            .then(a.order.cmp(&b.order))
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp() -> PathBuf {
        let p = std::env::temp_dir().join(format!("pact-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&p).unwrap();
        p
    }

    #[test]
    fn roundtrip_and_recurring_completion() {
        let dir = tmp();
        let mut s = Store::open(&dir).unwrap();
        assert_eq!(s.live_groups().len(), 3);
        let now = Local::now().naive_local();
        let t = s.quick_add("water plants every day #Home", now, &NlpConfig::default(), Default::default()).unwrap().unwrap();
        assert!(s.find_group_by_name("home").is_some());
        let sub = s.quick_add("fill can", now, &NlpConfig::default(), QuickAddDefaults { parent_id: Some(t.id.clone()), ..Default::default() }).unwrap().unwrap();
        s.set_completed(&sub.id, true).unwrap();
        s.set_completed(&t.id, true).unwrap();
        let s2 = Store::open(&dir).unwrap();
        let t2 = s2.task(&t.id).unwrap();
        assert!(!t2.completed);
        assert!(t2.date().unwrap() > now.date());
        assert!(!s2.task(&sub.id).unwrap().completed);
        s.delete_task(&t.id).unwrap();
        assert!(s.task(&sub.id).is_none());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn events_quick_add_detach_and_ics_reimport() {
        let dir = tmp();
        let mut s = Store::open(&dir).unwrap();
        let now = NaiveDate::from_ymd_opt(2026, 10, 16).unwrap().and_hms_opt(9, 0, 0).unwrap();
        let cfg = NlpConfig::default();
        let day = |n: i64| now.date() + chrono::Duration::days(n);

        let e = s.quick_add_event("Standup every weekday 9:30-9:45am #Work", now, &cfg, None, None).unwrap().unwrap();
        assert_eq!(e.group_id.as_deref(), Some("work"));
        assert_eq!(s.agenda(day(0), day(6)).len(), 5);
        let one = s.detach_occurrence(&e.id, day(3)).unwrap().unwrap();
        assert!(one.recurrence.is_none());
        assert_eq!(s.agenda(day(0), day(6)).len(), 5, "detached, not lost");
        s.skip_occurrence(&e.id, day(4)).unwrap();
        assert_eq!(s.agenda(day(0), day(6)).len(), 4);

        let lunch = s.quick_add_event("Lunch 1-2pm", now, &cfg, None, Some(day(5))).unwrap().unwrap();
        assert_eq!(lunch.start_date, day(5).to_string());

        // Re-importing our own export updates every event instead of duplicating.
        let n = s.live_events().count();
        let text = s.export_ics();
        assert_eq!(s.import_ics(&text).unwrap(), (0, n));
        assert_eq!(s.live_events().count(), n);

        let reopened = Store::open(&dir).unwrap();
        assert_eq!(reopened.live_events().count(), n);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn migrates_root_layout_into_folder() {
        let repo = tmp();
        let mut s = Store::open(&repo).unwrap();
        let t = Task::new("old");
        s.save_task(t.clone()).unwrap();
        migrate_into_folder(&repo, "pact").unwrap();
        assert!(!repo.join("tasks").exists());
        let s2 = Store::open(data_dir(&repo, "pact")).unwrap();
        assert!(s2.task(&t.id).is_some());
        fs::remove_dir_all(repo).unwrap();
    }
}
