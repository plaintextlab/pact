use std::collections::HashSet;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use anyhow::Result;
use chrono::{Datelike, Duration as CDuration, Local, NaiveDate, NaiveDateTime, NaiveTime};
use eframe::egui::{self, Color32, CornerRadius, Margin, RichText, Vec2};
use pact_core::event::Event;
use pact_core::model::{AttachmentKind, Group, Task};
use pact_core::settings::Settings;
use pact_core::store::{self, QuickAddDefaults, Store};
use pact_core::sync::SyncConfig;

use crate::calendar::CalMode;
use crate::events::{EditScope, EventEdit};
use crate::reminders::{notify, AlarmSound, Fire, Reminders};
use crate::style::{self, Colors};
use crate::sync_worker::SyncWorker;
use crate::voice::Recorder;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum View {
    Inbox,
    Today,
    Upcoming,
    Calendar,
    All,
    Completed,
    Group(String),
    Label(String),
}

#[derive(Clone)]
pub enum Action {
    Save(Task),
    Complete(String, bool),
    Delete(String),
    Select(Option<String>),
    ToggleExpand(String),
    SaveGroup(Group),
    DeleteGroup(String),
    PickAttachment(String, Option<AttachmentKind>),
    AttachText(String, String, String),
    RemoveAttachment(String, String),
    StartRecording(String),
    StopRecording,
    QuickAdd(String, QuickAddDefaults),
    Open(PathBuf),
    SetView(View),
    EditGroup(Option<String>),
    SyncNow,
    /// Event id and the occurrence clicked (for recurring events).
    OpenEvent(String, Option<NaiveDate>),
    NewEvent(NaiveDateTime, bool),
    SaveEvent(EditScope),
    SaveEventCopy(Event),
    DeleteEvent(EditScope),
    QuickAddEvent(String, Option<NaiveDate>),
    ImportIcs,
    ExportIcs,
}

pub struct ActiveAlarm {
    pub fire: Fire,
    pub sound: Option<AlarmSound>,
}

pub struct GroupEdit {
    pub id: Option<String>,
    pub name: String,
    pub color: String,
}

#[derive(Default)]
pub struct DetailInputs {
    pub subtask: String,
    pub reschedule: String,
    pub label: String,
    pub custom_reminder: i64,
}

pub struct NoteEdit {
    pub task_id: String,
    pub name: String,
    pub text: String,
}

pub struct PactApp {
    pub store: Store,
    pub settings: Settings,
    settings_path: PathBuf,
    default_data_dir: PathBuf,
    /// The (possibly shared) git repository; the store lives in a folder inside it.
    repo_root: PathBuf,
    pub folder_text: String,
    pub colors: Colors,
    pub view: View,
    pub selected: Option<String>,
    pub collapsed: HashSet<String>,
    pub quick_add: String,
    pub search: String,
    pub cal_month: NaiveDate,
    pub cal_day: NaiveDate,
    pub cal_mode: CalMode,
    pub event_edit: Option<EventEdit>,
    /// Quick add creates events instead of tasks (calendar view).
    pub quick_add_event: bool,
    pub show_settings: bool,
    pub group_edit: Option<GroupEdit>,
    pub note_edit: Option<NoteEdit>,
    pub inputs: DetailInputs,
    pub recorder: Option<(String, Recorder)>,
    pub sync: SyncWorker,
    reminders: Reminders,
    alarms: Vec<ActiveAlarm>,
    pub toast: Option<(String, Instant)>,
    dirty_since: Option<Instant>,
    last_auto_sync: Instant,
    last_reminder_check: Option<Instant>,
    style_dirty: bool,
    title_applied: String,
    pub presets_text: String,
    pub default_reminders_text: String,
    pub event_reminders_text: String,
    screenshot: Option<(PathBuf, u32)>,
}

pub fn today() -> NaiveDate {
    Local::now().date_naive()
}

impl PactApp {
    pub fn new(cc: &eframe::CreationContext<'_>, settings: Settings, settings_path: PathBuf, default_data_dir: PathBuf) -> Result<Self> {
        let repo_root = std::env::var_os("PACT_DATA_DIR").map(PathBuf::from).or_else(|| settings.data_dir.clone()).unwrap_or_else(|| default_data_dir.clone());
        store::migrate_into_folder(&repo_root, &settings.git_folder)?;
        let store = Store::open(store::data_dir(&repo_root, &settings.git_folder))?;
        let colors = Colors::from_settings(&settings);
        crate::list::set_clock_24h(settings.clock_24h);
        style::apply(&cc.egui_ctx, &colors, settings.ui_scale);
        let view = match std::env::var("PACT_VIEW").as_deref() {
            Ok("calendar") => View::Calendar,
            Ok("upcoming") => View::Upcoming,
            Ok("inbox") => View::Inbox,
            _ => View::Today,
        };
        let mut app = Self {
            store,
            presets_text: join_nums(&settings.reminder_presets),
            default_reminders_text: join_nums(&settings.default_reminders),
            event_reminders_text: join_nums(&settings.event_reminders),
            folder_text: settings.git_folder.clone(),
            cal_mode: CalMode::from_setting(&settings.calendar_view),
            settings,
            settings_path,
            default_data_dir,
            repo_root,
            colors,
            view,
            selected: None,
            collapsed: HashSet::new(),
            quick_add: String::new(),
            search: String::new(),
            cal_month: today().with_day(1).unwrap(),
            cal_day: today(),
            event_edit: None,
            quick_add_event: true,
            show_settings: false,
            group_edit: None,
            note_edit: None,
            inputs: DetailInputs { custom_reminder: 15, ..Default::default() },
            recorder: None,
            sync: SyncWorker::new(),
            reminders: Reminders::default(),
            alarms: vec![],
            toast: None,
            dirty_since: None,
            last_auto_sync: Instant::now(),
            last_reminder_check: None,
            style_dirty: false,
            title_applied: String::new(),
            screenshot: std::env::var_os("PACT_SCREENSHOT").map(|p| (PathBuf::from(p), 0)),
        };
        // Dev hooks used for screenshots: PACT_CAL_MODE=week, PACT_EDIT_FIRST_EVENT=1.
        if let Ok(m) = std::env::var("PACT_CAL_MODE") {
            app.cal_mode = CalMode::from_setting(&m);
        }
        if std::env::var("PACT_EDIT_FIRST_EVENT").is_ok() {
            let first = app.store.agenda(today(), today() + CDuration::days(7)).first().map(|(e, o)| (e.id.clone(), o.date()));
            if let Some((id, d)) = first {
                app.apply(&cc.egui_ctx, vec![Action::OpenEvent(id, Some(d))]);
            }
        }
        if std::env::var("PACT_SELECT_FIRST").is_ok() {
            app.selected = app.store.children(None).first().map(|t| t.id.clone());
        }
        app.store.purge_tombstones();
        if app.screenshot.is_none() {
            app.request_sync();
            if app.settings.notifications {
                let pinned: Vec<Task> = app.store.live_tasks().filter(|t| t.pinned && !t.completed).cloned().collect();
                for t in pinned {
                    notify(&app.settings.app_name, &format!("📌 {}", t.title), &crate::list::friendly_due(&t, today()), true);
                }
            }
        }
        Ok(app)
    }

    pub fn repo_root(&self) -> PathBuf {
        self.repo_root.clone()
    }

    fn sync_config(&self) -> SyncConfig {
        SyncConfig { remote: self.settings.git_remote.clone(), branch: self.settings.git_branch.clone(), folder: self.settings.git_folder.clone() }
    }

    pub fn save_settings(&mut self) {
        self.colors = Colors::from_settings(&self.settings);
        crate::list::set_clock_24h(self.settings.clock_24h);
        self.style_dirty = true;
        if let Err(e) = self.settings.save(&self.settings_path) {
            self.show_toast(format!("Could not save settings: {e}"));
        }
        let repo = self.settings.data_dir.clone().unwrap_or_else(|| self.default_data_dir.clone());
        let wanted = store::data_dir(&repo, &self.settings.git_folder);
        if wanted != self.store.root {
            let opened = store::migrate_into_folder(&repo, &self.settings.git_folder).and_then(|_| Store::open(&wanted));
            match opened {
                Ok(s) => {
                    self.store = s;
                    self.repo_root = repo;
                    self.selected = None;
                }
                Err(e) => self.show_toast(format!("Could not open data folder: {e}")),
            }
        }
    }

    pub fn show_toast(&mut self, msg: impl Into<String>) {
        self.toast = Some((msg.into(), Instant::now()));
    }

    fn mark_dirty(&mut self) {
        self.dirty_since = Some(Instant::now());
    }

    pub fn request_sync(&mut self) {
        self.sync.request(self.repo_root.clone(), self.sync_config());
        self.dirty_since = None;
        self.last_auto_sync = Instant::now();
    }

    pub fn quick_add_defaults(&self) -> QuickAddDefaults {
        let mut d = QuickAddDefaults { reminders: self.settings.default_reminders.clone(), ..Default::default() };
        match &self.view {
            View::Group(id) => d.group_id = Some(id.clone()),
            View::Today => d.date = Some(today()),
            View::Calendar => d.date = Some(self.cal_day),
            _ => {}
        }
        d
    }

    fn apply(&mut self, ctx: &egui::Context, actions: Vec<Action>) {
        for a in actions {
            let res: Result<()> = match a {
                Action::Save(t) => self.store.save_task(t),
                Action::Complete(id, done) => {
                    let was_recurring = self.store.task(&id).is_some_and(|t| t.recurrence.is_some() && t.due_date.is_some());
                    let r = self.store.set_completed(&id, done);
                    if done {
                        if let Some(t) = self.store.task(&id) {
                            let msg = if was_recurring { format!("Next: {}", crate::list::friendly_due(t, today())) } else { format!("Completed “{}”", t.title) };
                            self.show_toast(msg);
                        }
                    }
                    r
                }
                Action::Delete(id) => {
                    if self.selected.as_deref() == Some(id.as_str()) {
                        self.selected = None;
                    }
                    self.store.delete_task(&id)
                }
                Action::Select(id) => {
                    if id.is_some() {
                        self.event_edit = None;
                    }
                    if id != self.selected {
                        self.inputs = DetailInputs { custom_reminder: 15, ..Default::default() };
                    }
                    self.selected = id;
                    Ok(())
                }
                Action::ToggleExpand(id) => {
                    if !self.collapsed.remove(&id) {
                        self.collapsed.insert(id);
                    }
                    Ok(())
                }
                Action::SaveGroup(g) => self.store.save_group(g),
                Action::DeleteGroup(id) => {
                    if self.view == View::Group(id.clone()) {
                        self.view = View::Inbox;
                    }
                    self.store.delete_group(&id)
                }
                Action::PickAttachment(task_id, kind) => {
                    let mut dlg = rfd::FileDialog::new();
                    if kind == Some(AttachmentKind::Image) {
                        dlg = dlg.add_filter("Images", &["png", "jpg", "jpeg", "gif", "webp", "bmp"]);
                    }
                    let mut r = Ok(());
                    for f in dlg.pick_files().unwrap_or_default() {
                        if let Err(e) = self.store.attach_file(&task_id, &f, None) {
                            r = Err(e);
                        }
                    }
                    r
                }
                Action::AttachText(task_id, name, text) => self.store.attach_text(&task_id, &name, &text),
                Action::RemoveAttachment(task_id, att) => self.store.remove_attachment(&task_id, &att),
                Action::StartRecording(task_id) => match Recorder::start() {
                    Ok(r) => {
                        self.recorder = Some((task_id, r));
                        Ok(())
                    }
                    Err(e) => Err(e),
                },
                Action::StopRecording => match self.recorder.take() {
                    Some((task_id, rec)) => {
                        let name = format!("voice-{}.wav", Local::now().format("%Y%m%d-%H%M%S"));
                        let tmp = std::env::temp_dir().join(&name);
                        let r = rec.finish(&tmp).and_then(|_| self.store.attach_file(&task_id, &tmp, Some(AttachmentKind::Audio)).map(|_| ()));
                        let _ = std::fs::remove_file(&tmp);
                        r
                    }
                    None => Ok(()),
                },
                Action::QuickAdd(text, defaults) => {
                    let now = Local::now().naive_local();
                    match self.store.quick_add(&text, now, &self.settings.nlp, defaults) {
                        Ok(Some(t)) => {
                            let when = crate::list::friendly_due(&t, today());
                            self.show_toast(if when.is_empty() { format!("Added “{}”", t.title) } else { format!("Added “{}” · {when}", t.title) });
                            Ok(())
                        }
                        Ok(None) => Ok(()),
                        Err(e) => Err(e),
                    }
                }
                Action::Open(p) => open::that(&p).map_err(|e| anyhow::anyhow!("could not open {}: {e}", p.display())),
                Action::SetView(v) => {
                    self.view = v;
                    self.search.clear();
                    Ok(())
                }
                Action::EditGroup(id) => {
                    self.group_edit = Some(match id.as_deref().and_then(|id| self.store.groups.get(id)) {
                        Some(g) => GroupEdit { id: Some(g.id.clone()), name: g.name.clone(), color: g.color.clone() },
                        None => GroupEdit { id: None, name: String::new(), color: "blue".into() },
                    });
                    Ok(())
                }
                Action::SyncNow => {
                    self.request_sync();
                    Ok(())
                }
                Action::OpenEvent(id, occurrence) => {
                    if let Some(e) = self.store.event(&id).cloned() {
                        self.selected = None;
                        self.event_edit = Some(EventEdit::open(e, occurrence));
                    }
                    Ok(())
                }
                Action::NewEvent(start, all_day) => {
                    self.selected = None;
                    let group = self.settings.default_calendar.clone().filter(|g| self.store.group(Some(g)).is_some());
                    self.event_edit = Some(EventEdit::new_event(start, all_day, self.settings.event_minutes, self.settings.event_reminders.clone(), group));
                    Ok(())
                }
                Action::SaveEvent(scope) => self.save_event(scope),
                Action::SaveEventCopy(e) => {
                    self.event_edit = None;
                    self.show_toast(format!("Duplicated “{}”", e.title));
                    self.store.save_event(e)
                }
                Action::DeleteEvent(scope) => match self.event_edit.take() {
                    Some(ed) => match (ed.original, ed.occurrence, scope) {
                        (Some(o), Some(d), EditScope::This) if o.recurrence.is_some() => self.store.skip_occurrence(&o.id, d),
                        (Some(o), _, _) => self.store.delete_event(&o.id),
                        _ => Ok(()),
                    },
                    None => Ok(()),
                },
                Action::QuickAddEvent(text, date) => {
                    let now = Local::now().naive_local();
                    let group = self.settings.default_calendar.clone().filter(|g| self.store.group(Some(g)).is_some());
                    match self.store.quick_add_event(&text, now, &self.settings.nlp, group, date) {
                        Ok(Some(mut e)) => {
                            if e.reminders.is_empty() && !self.settings.event_reminders.is_empty() {
                                e.reminders = self.settings.event_reminders.clone();
                                let _ = self.store.save_event(e.clone());
                            }
                            let when = if e.all_day() { "all day".to_string() } else { crate::list::fmt_time(e.start().time()) };
                            self.show_toast(format!("Added “{}” · {} {when}", e.title, crate::list::friendly_date(e.start().date(), today())));
                            Ok(())
                        }
                        Ok(None) => Ok(()),
                        Err(e) => Err(e),
                    }
                }
                Action::ImportIcs => match rfd::FileDialog::new().add_filter("iCalendar", &["ics", "ical", "ifb", "icalendar"]).pick_file() {
                    Some(path) => std::fs::read_to_string(&path).map_err(anyhow::Error::from).and_then(|text| {
                        let (added, updated) = self.store.import_ics(&text)?;
                        self.show_toast(format!("Imported {added} new, {updated} updated event(s)"));
                        Ok(())
                    }),
                    None => Ok(()),
                },
                Action::ExportIcs => match rfd::FileDialog::new().set_file_name("pact-calendar.ics").add_filter("iCalendar", &["ics"]).save_file() {
                    Some(path) => {
                        let n = self.store.live_events().count();
                        std::fs::write(&path, self.store.export_ics()).map_err(anyhow::Error::from).map(|_| self.show_toast(format!("Exported {n} event(s)")))
                    }
                    None => Ok(()),
                },
            };
            match res {
                Ok(()) => self.mark_dirty(),
                Err(e) => self.show_toast(format!("⚠ {e:#}")),
            }
            ctx.request_repaint();
        }
    }

    /// Background work: sync results, debounced/periodic sync, reminders.
    fn tick(&mut self, ctx: &egui::Context) {
        match self.sync.poll() {
            Some(Ok(())) => self.store.reload(),
            Some(Err(e)) => self.show_toast(format!("Sync failed: {}", e.lines().next().unwrap_or(""))),
            None => {}
        }
        if self.settings.sync_on_change && self.dirty_since.is_some_and(|t| t.elapsed() > Duration::from_secs(5)) {
            self.request_sync();
        }
        let every = self.settings.auto_sync_minutes;
        if every > 0 && self.last_auto_sync.elapsed() > Duration::from_secs(every as u64 * 60) {
            self.request_sync();
        }

        if self.last_reminder_check.is_none_or(|t| t.elapsed() > Duration::from_secs(10)) {
            self.last_reminder_check = Some(Instant::now());
            let now = Local::now().naive_local();
            let anchor = NaiveTime::from_hms_opt(self.settings.nlp.morning_hour.min(23), 0, 0).unwrap();
            let deadlines = self.settings.deadline_reminders.then(|| self.settings.deadline_config());
            for f in self.reminders.check(&self.store, now, anchor, deadlines.as_ref()) {
                if f.alarm {
                    let sound = AlarmSound::ring(self.settings.alarm_ring_seconds);
                    ctx.send_viewport_cmd(egui::ViewportCommand::Minimized(false));
                    ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
                    ctx.send_viewport_cmd(egui::ViewportCommand::RequestUserAttention(egui::UserAttentionType::Critical));
                    if self.settings.notifications {
                        notify(&self.settings.app_name, &format!("⏰ {}", f.title), &f.body, true);
                    }
                    self.alarms.push(ActiveAlarm { fire: f, sound });
                } else if self.settings.notifications {
                    notify(&self.settings.app_name, &f.title, &f.body, false);
                }
            }
        }
    }

    fn sidebar(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        egui::SidePanel::left("nav")
            .resizable(false)
            .exact_width(236.0)
            .frame(egui::Frame::new().fill(c.surface).inner_margin(Margin::symmetric(12, 14)))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new("✔").size(22.0).color(c.primary));
                    ui.label(RichText::new(&self.settings.app_name).size(22.0).strong());
                });
                ui.add_space(8.0);
                ui.add(egui::TextEdit::singleline(&mut self.search).hint_text("🔍 Search").desired_width(f32::INFINITY));
                ui.add_space(8.0);

                let t = today();
                let live: Vec<&Task> = self.store.live_tasks().filter(|t| !t.completed).collect();
                let n_today = live.iter().filter(|x| x.date().is_some_and(|d| d <= t)).count();
                let n_inbox = live.iter().filter(|x| x.group_id.is_none() && x.parent_id.is_none()).count();
                let n_upcoming = live.iter().filter(|x| x.date().is_some_and(|d| d > t)).count();
                let n_all = live.iter().filter(|x| x.parent_id.is_none()).count();
                let items = [
                    (View::Inbox, "📥", "Inbox", n_inbox),
                    (View::Today, "☀", "Today", n_today),
                    (View::Upcoming, "📅", "Upcoming", n_upcoming),
                    (View::Calendar, "📆", "Calendar", 0),
                    (View::All, "☰", "All tasks", n_all),
                    (View::Completed, "✔", "Completed", 0),
                ];
                for (v, icon, label, n) in items {
                    if nav_item(ui, &c, self.view == v && self.search.is_empty(), icon, None, label, n).clicked() {
                        actions.push(Action::SetView(v));
                    }
                }

                ui.add_space(10.0);
                ui.horizontal(|ui| {
                    ui.label(RichText::new("Groups").color(c.muted).size(13.0).strong());
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.add(egui::Button::new("➕").frame(false)).on_hover_text("New group").clicked() {
                            actions.push(Action::EditGroup(None));
                        }
                    });
                });
                let groups: Vec<Group> = self.store.live_groups().into_iter().cloned().collect();
                egui::ScrollArea::vertical().id_salt("nav-groups").max_height(ui.available_height() - 90.0).show(ui, |ui| {
                    for g in groups {
                        let n = live.iter().filter(|x| x.group_id.as_deref() == Some(g.id.as_str()) && x.parent_id.is_none()).count();
                        let v = View::Group(g.id.clone());
                        let r = nav_item(ui, &c, self.view == v && self.search.is_empty(), "⏺", Some(c.named(&g.color)), &g.name, n);
                        if r.clicked() {
                            actions.push(Action::SetView(v));
                        }
                        if r.double_clicked() {
                            actions.push(Action::EditGroup(Some(g.id.clone())));
                        }
                        r.context_menu(|ui| {
                            if ui.button("✏ Edit group").clicked() {
                                actions.push(Action::EditGroup(Some(g.id.clone())));
                                ui.close_menu();
                            }
                            if ui.button("🗑 Delete group").clicked() {
                                actions.push(Action::DeleteGroup(g.id.clone()));
                                ui.close_menu();
                            }
                        });
                    }
                    let mut labels: Vec<String> = self.store.live_tasks().flat_map(|t| t.labels.iter().cloned()).collect();
                    labels.sort_by_key(|l| l.to_lowercase());
                    labels.dedup_by_key(|l| l.to_lowercase());
                    if !labels.is_empty() {
                        ui.add_space(10.0);
                        ui.label(RichText::new("Labels").color(c.muted).size(13.0).strong());
                        for l in labels {
                            let v = View::Label(l.clone());
                            if nav_item(ui, &c, self.view == v && self.search.is_empty(), "@", Some(c.muted), &l, 0).clicked() {
                                actions.push(Action::SetView(v));
                            }
                        }
                    }
                });

                ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                    ui.horizontal(|ui| {
                        if ui.add(egui::Button::new("⚙ Settings").frame(false)).clicked() {
                            self.show_settings = !self.show_settings;
                        }
                    });
                    let remote = !self.settings.git_remote.trim().is_empty();
                    let (icon, color) = if self.sync.busy {
                        ("🔄", c.primary)
                    } else if self.sync.error.is_some() {
                        ("⚠", c.red)
                    } else {
                        ("✔", if remote { c.green } else { c.muted })
                    };
                    let label = if remote { format!("{icon} Sync · {}", self.sync.status.trim()) } else { format!("{icon} Local git · {}", self.sync.status.trim()) };
                    let r = ui.add(egui::Button::new(RichText::new(label).color(color).size(12.5)).frame(false)).on_hover_text(self.sync.error.clone().unwrap_or_else(|| "Sync now".into()));
                    if r.clicked() {
                        actions.push(Action::SyncNow);
                    }
                    if self.recorder.is_some() {
                        ui.label(RichText::new("🎤 Recording…").color(c.red));
                    }
                });
            });
    }

    fn dialogs(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        if let Some(mut ge) = self.group_edit.take() {
            let mut keep = true;
            let title = if ge.id.is_some() { "Edit group" } else { "New group" };
            egui::Modal::new(egui::Id::new("group-edit")).show(ctx, |ui| {
                ui.set_width(340.0);
                ui.heading(title);
                ui.add_space(8.0);
                let r = ui.add(egui::TextEdit::singleline(&mut ge.name).hint_text("Name, e.g. Work").desired_width(f32::INFINITY));
                if ge.name.is_empty() {
                    r.request_focus();
                }
                ui.add_space(6.0);
                ui.label("Color");
                ui.horizontal_wrapped(|ui| {
                    for key in pact_core::model::GROUP_COLORS {
                        let col = c.named(key);
                        let (rect, resp) = ui.allocate_exact_size(Vec2::splat(30.0), egui::Sense::click());
                        ui.painter().circle_filled(rect.center(), 12.0, col);
                        if ge.color == key {
                            ui.painter().circle_stroke(rect.center(), 14.5, egui::Stroke::new(2.0_f32, c.fg));
                        }
                        if resp.on_hover_text(key).clicked() {
                            ge.color = key.to_string();
                        }
                    }
                });
                ui.horizontal(|ui| {
                    ui.label("Custom hex");
                    let mut hex = if ge.color.starts_with('#') { ge.color.clone() } else { String::new() };
                    if ui.add(egui::TextEdit::singleline(&mut hex).hint_text("#RRGGBB").desired_width(90.0)).changed() && hex.len() == 7 && hex.starts_with('#') {
                        ge.color = hex;
                    }
                });
                ui.label(RichText::new("Theme colors follow the active color scheme.").size(12.0).color(c.muted));
                ui.add_space(10.0);
                ui.horizontal(|ui| {
                    let enter = ui.input(|i| i.key_pressed(egui::Key::Enter));
                    if (style::filled_button(ui, &c, "Save").clicked() || enter) && !ge.name.trim().is_empty() {
                        let mut g = match ge.id.as_deref().and_then(|id| self.store.groups.get(id)) {
                            Some(g) => g.clone(),
                            None => Group::new("", ""),
                        };
                        g.name = ge.name.trim().to_string();
                        g.color = ge.color.clone();
                        actions.push(Action::SaveGroup(g));
                        keep = false;
                    }
                    if ui.button("Cancel").clicked() || ui.input(|i| i.key_pressed(egui::Key::Escape)) {
                        keep = false;
                    }
                    if let Some(id) = &ge.id {
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button(RichText::new("Delete").color(c.red)).clicked() {
                                actions.push(Action::DeleteGroup(id.clone()));
                                keep = false;
                            }
                        });
                    }
                });
            });
            if keep {
                self.group_edit = Some(ge);
            }
        }

        if let Some(mut ne) = self.note_edit.take() {
            let mut keep = true;
            egui::Modal::new(egui::Id::new("note-edit")).show(ctx, |ui| {
                ui.set_width(420.0);
                ui.heading("Text attachment");
                ui.add(egui::TextEdit::singleline(&mut ne.name).hint_text("Title").desired_width(f32::INFINITY));
                ui.add(egui::TextEdit::multiline(&mut ne.text).hint_text("Text…").desired_rows(8).desired_width(f32::INFINITY));
                ui.horizontal(|ui| {
                    if style::filled_button(ui, &c, "Attach").clicked() && !ne.text.trim().is_empty() {
                        actions.push(Action::AttachText(ne.task_id.clone(), ne.name.clone(), ne.text.clone()));
                        keep = false;
                    }
                    if ui.button("Cancel").clicked() {
                        keep = false;
                    }
                });
            });
            if keep {
                self.note_edit = Some(ne);
            }
        }

        // Ringing alarms for urgent tasks.
        if let Some(alarm) = self.alarms.first() {
            let id = alarm.fire.task_id.clone();
            let is_event = alarm.fire.is_event;
            let mut close = None;
            egui::Modal::new(egui::Id::new("alarm")).show(ctx, |ui| {
                ui.set_width(380.0);
                ui.vertical_centered(|ui| {
                    ui.label(RichText::new("⏰").size(48.0).color(c.red));
                    ui.label(RichText::new(&alarm.fire.title).size(22.0).strong());
                    ui.label(RichText::new(&alarm.fire.body).color(c.muted));
                    ui.add_space(12.0);
                    ui.horizontal(|ui| {
                        if style::filled_button(ui, &c, "Dismiss").clicked() {
                            close = Some(0);
                        }
                        if style::tonal_button(ui, &c, &format!("Snooze {} min", self.settings.snooze_minutes)).clicked() {
                            close = Some(1);
                        }
                        if !is_event && style::tonal_button(ui, &c, "✔ Complete").clicked() {
                            close = Some(2);
                        }
                    });
                });
            });
            if let Some(choice) = close {
                let a = self.alarms.remove(0);
                if let Some(s) = &a.sound {
                    s.stop();
                }
                match choice {
                    1 => self.reminders.snooze(a.fire.clone(), Local::now().naive_local() + CDuration::minutes(self.settings.snooze_minutes as i64)),
                    2 => actions.push(Action::Complete(id, true)),
                    _ => {}
                }
            }
        }

        if self.show_settings {
            self.settings_window(ctx, actions);
        }

        if let Some((msg, at)) = &self.toast {
            if at.elapsed() > Duration::from_secs(4) {
                self.toast = None;
            } else {
                egui::Area::new(egui::Id::new("toast")).anchor(egui::Align2::CENTER_BOTTOM, Vec2::new(0.0, -24.0)).order(egui::Order::Foreground).show(ctx, |ui| {
                    egui::Frame::new().fill(c.fg).corner_radius(CornerRadius::same(10)).inner_margin(Margin::symmetric(16, 10)).show(ui, |ui| {
                        ui.label(RichText::new(msg).color(c.bg));
                    });
                });
            }
        }
    }

    /// Saves the event editor's draft. For one occurrence of a series, `This` splits it
    /// off; `All` updates the series, shifting it by however far the occurrence moved.
    fn save_event(&mut self, scope: EditScope) -> Result<()> {
        let Some(ed) = self.event_edit.take() else { return Ok(()) };
        let mut draft = ed.draft;
        draft.title = draft.title.trim().to_string();
        match (ed.original, ed.occurrence) {
            (Some(orig), Some(occ)) if orig.recurrence.is_some() => {
                if scope == EditScope::This {
                    self.store.skip_occurrence(&orig.id, occ)?;
                    draft.id = Event::new("", occ, None).id;
                    draft.recurrence = None;
                    draft.exdates.clear();
                    draft.uid = None;
                    self.store.save_event(draft)
                } else {
                    let shift = draft.start().date() - occ;
                    let start = (orig.start().date() + shift).and_time(draft.start().time());
                    let len = draft.end() - draft.start();
                    let all_day = draft.all_day();
                    draft.set_span(start, start + len, all_day);
                    draft.id = orig.id;
                    draft.exdates = orig.exdates;
                    draft.uid = orig.uid;
                    self.store.save_event(draft)
                }
            }
            _ => self.store.save_event(draft),
        }
    }

    fn shortcuts(&mut self, ctx: &egui::Context) {
        let (esc, new) = ctx.input(|i| (i.key_pressed(egui::Key::Escape), i.modifiers.command && i.key_pressed(egui::Key::N)));
        if esc && self.group_edit.is_none() && self.note_edit.is_none() {
            self.selected = None;
        }
        if new {
            ctx.memory_mut(|m| m.request_focus(egui::Id::new("quick-add")));
        }
    }

    fn handle_screenshot(&mut self, ctx: &egui::Context) {
        let Some((path, frames)) = &mut self.screenshot else { return };
        *frames += 1;
        if *frames == 8 {
            ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot(Default::default()));
        }
        let shot = ctx.input(|i| i.raw.events.iter().find_map(|e| if let egui::Event::Screenshot { image, .. } = e { Some(image.clone()) } else { None }));
        if let Some(img) = shot {
            let [w, h] = img.size;
            let bytes: Vec<u8> = img.pixels.iter().flat_map(|p| p.to_array()).collect();
            if let Some(buf) = image::RgbaImage::from_raw(w as u32, h as u32, bytes) {
                let _ = buf.save(&*path);
            }
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
        ctx.request_repaint();
    }
}

impl eframe::App for PactApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if self.style_dirty {
            style::apply(ctx, &self.colors, self.settings.ui_scale);
            self.style_dirty = false;
        }
        if self.title_applied != self.settings.app_name {
            self.title_applied = self.settings.app_name.clone();
            ctx.send_viewport_cmd(egui::ViewportCommand::Title(self.title_applied.clone()));
        }
        self.tick(ctx);
        self.shortcuts(ctx);

        let mut actions = vec![];
        self.sidebar(ctx, &mut actions);
        if self.event_edit.is_some() {
            self.event_panel(ctx, &mut actions);
        } else {
            self.detail_panel(ctx, &mut actions);
        }
        self.central(ctx, &mut actions);
        self.dialogs(ctx, &mut actions);
        self.apply(ctx, actions);
        self.handle_screenshot(ctx);

        ctx.request_repaint_after(if self.recorder.is_some() { Duration::from_millis(250) } else { Duration::from_secs(5) });
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        if self.dirty_since.is_some() && !self.settings.git_remote.trim().is_empty() {
            // Best effort final sync so edits reach other devices.
            let _ = pact_core::sync::sync(&self.repo_root, &self.sync_config(), "desktop");
        }
    }
}

/// Navigation drawer item with a pill-shaped selection indicator.
fn nav_item(ui: &mut egui::Ui, c: &Colors, selected: bool, icon: &str, icon_color: Option<Color32>, label: &str, count: usize) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 36.0), egui::Sense::click());
    let p = ui.painter();
    if selected {
        p.rect_filled(rect, CornerRadius::same(18), c.tonal);
    } else if resp.hovered() {
        p.rect_filled(rect, CornerRadius::same(18), style::mix(c.surface, c.surface2, 0.7));
    }
    let fg = if selected { c.fg } else { style::mix(c.fg, c.muted, 0.3) };
    p.text(rect.left_center() + Vec2::new(16.0, 0.0), egui::Align2::LEFT_CENTER, icon, egui::FontId::proportional(15.0), icon_color.unwrap_or(fg));
    let label_rect = egui::Rect::from_min_max(rect.min + Vec2::new(42.0, 0.0), rect.max - Vec2::new(36.0, 0.0));
    p.with_clip_rect(label_rect).text(label_rect.left_center(), egui::Align2::LEFT_CENTER, label, egui::FontId::proportional(15.0), fg);
    if count > 0 {
        p.text(rect.right_center() - Vec2::new(14.0, 0.0), egui::Align2::RIGHT_CENTER, count.to_string(), egui::FontId::proportional(12.5), c.muted);
    }
    resp.on_hover_cursor(egui::CursorIcon::PointingHand)
}

pub fn join_nums(v: &[i64]) -> String {
    v.iter().map(|n| n.to_string()).collect::<Vec<_>>().join(", ")
}

pub fn parse_nums(s: &str) -> Vec<i64> {
    let mut v: Vec<i64> = s.split([',', ' ']).filter_map(|x| x.trim().parse().ok()).filter(|n| *n >= 0).collect();
    v.sort();
    v.dedup();
    v
}
