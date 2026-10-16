//! Central panel: quick add, task lists and task rows.

use chrono::{Datelike, Duration, Local, NaiveDate};
use eframe::egui::{self, Align2, Color32, CornerRadius, FontId, Margin, RichText, Sense, Stroke, Vec2};
use pact_core::model::Task;
use pact_core::nlp;
use pact_core::recur;
use pact_core::settings::format_offset;
use pact_core::store::{sort_tasks, QuickAddDefaults};

use crate::app::{today, Action, PactApp, View};
use crate::style::{self, Colors};

static CLOCK_24H: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

/// Set from Settings::clock_24h.
pub fn set_clock_24h(v: bool) {
    CLOCK_24H.store(v, std::sync::atomic::Ordering::Relaxed);
}

pub fn clock_24h() -> bool {
    CLOCK_24H.load(std::sync::atomic::Ordering::Relaxed)
}

pub fn fmt_time(t: chrono::NaiveTime) -> String {
    pact_core::settings::format_time(t, clock_24h())
}

/// "Today 10:00", "Tomorrow", "Wed", "Mon 2 Nov", "3 Mar 2027".
pub fn friendly_date(d: NaiveDate, today: NaiveDate) -> String {
    let diff = (d - today).num_days();
    match diff {
        0 => "Today".into(),
        1 => "Tomorrow".into(),
        -1 => "Yesterday".into(),
        2..=6 => d.format("%A").to_string(),
        _ if d.year() == today.year() => d.format("%a %-d %b").to_string(),
        _ => d.format("%-d %b %Y").to_string(),
    }
}

pub fn friendly_due(t: &Task, today: NaiveDate) -> String {
    match (t.date(), t.time()) {
        (Some(d), Some(tm)) => format!("{} {}", friendly_date(d, today), fmt_time(tm)),
        (Some(d), None) => friendly_date(d, today),
        _ => String::new(),
    }
}

#[derive(Clone, Copy)]
pub struct RowOpts {
    pub depth: usize,
    /// Render subtasks underneath (tree views).
    pub tree: bool,
    pub show_group: bool,
    pub show_parent: bool,
}

impl PactApp {
    fn visible(&self, t: &Task) -> bool {
        self.settings.show_completed || !t.completed
    }

    pub fn central(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        egui::CentralPanel::default().frame(egui::Frame::new().fill(c.bg).inner_margin(Margin { left: 28, right: 28, top: 20, bottom: 8 })).show(ctx, |ui| {
            if self.view == View::Calendar && self.search.is_empty() {
                self.calendar_view(ui, actions);
                return;
            }
            let (title, accent) = self.view_title();
            ui.horizontal(|ui| {
                if let Some(col) = accent {
                    ui.label(RichText::new("⏺").color(col).size(22.0));
                }
                ui.heading(RichText::new(title).strong());
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let label = if self.settings.show_completed { "Hide completed" } else { "Show completed" };
                    if ui.add(egui::Button::new(RichText::new(label).size(12.5).color(c.muted)).frame(false)).clicked() {
                        self.settings.show_completed = !self.settings.show_completed;
                        self.save_settings();
                    }
                });
            });
            ui.add_space(6.0);
            let defaults = self.quick_add_defaults();
            self.quick_add_bar(ui, actions, defaults);
            ui.add_space(8.0);
            self.pinned_strip(ui, actions);
            egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                self.list_body(ui, actions);
                ui.add_space(40.0);
            });
        });
    }

    fn view_title(&self) -> (String, Option<Color32>) {
        if !self.search.is_empty() {
            return (format!("Search: {}", self.search), None);
        }
        match &self.view {
            View::Inbox => ("Inbox".into(), None),
            View::Today => (format!("Today · {}", today().format("%a %-d %b")), None),
            View::Upcoming => ("Upcoming".into(), None),
            View::Calendar => ("Calendar".into(), None),
            View::All => ("All tasks".into(), None),
            View::Completed => ("Completed".into(), None),
            View::Label(l) => (format!("@{l}"), None),
            View::Group(id) => match self.store.group(Some(id)) {
                Some(g) => (g.name.clone(), Some(self.colors.named(&g.color))),
                None => ("Group".into(), None),
            },
        }
    }

    pub fn quick_add_bar(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>, defaults: QuickAddDefaults) {
        let c = self.colors.clone();
        egui::Frame::new().fill(c.surface).corner_radius(CornerRadius::same(28)).inner_margin(Margin::symmetric(16, 8)).show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(RichText::new("➕").color(c.primary).size(18.0));
                let edit = egui::TextEdit::singleline(&mut self.quick_add)
                    .id(egui::Id::new("quick-add"))
                    .hint_text("Add a task — “Meeting tomorrow at 10am #Work p1 remind me 15 min before”")
                    .frame(false)
                    .font(FontId::proportional(16.0))
                    .desired_width(ui.available_width() - 90.0);
                let r = ui.add(edit);
                let submit = r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                if (style::filled_button(ui, &c, "Add").clicked() || submit) && !self.quick_add.trim().is_empty() {
                    actions.push(Action::QuickAdd(std::mem::take(&mut self.quick_add), defaults.clone()));
                    r.request_focus();
                }
            });
            if !self.quick_add.trim().is_empty() {
                let p = nlp::parse(&self.quick_add, Local::now().naive_local(), &self.settings.nlp);
                ui.horizontal_wrapped(|ui| {
                    ui.add_space(28.0);
                    preview_chips(ui, &c, &p, defaults.date);
                });
            }
        });
    }

    /// Pinned tasks stay visible at the top, like a pinned notification.
    fn pinned_strip(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let pinned: Vec<Task> = self.store.live_tasks().filter(|t| t.pinned && !t.completed).cloned().collect();
        if pinned.is_empty() {
            return;
        }
        ui.horizontal_wrapped(|ui| {
            for t in pinned {
                let text = format!("📌 {}  {}", t.title, friendly_due(&t, today()));
                let r = ui.add(egui::Button::new(RichText::new(text.trim()).size(13.0)).fill(c.tonal).corner_radius(CornerRadius::same(16)));
                if r.clicked() {
                    actions.push(Action::Select(Some(t.id.clone())));
                }
            }
        });
        ui.add_space(6.0);
    }

    fn list_body(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let t0 = today();
        let flat = RowOpts { depth: 0, tree: false, show_group: true, show_parent: true };
        let tree = RowOpts { depth: 0, tree: true, show_group: false, show_parent: false };

        if !self.search.is_empty() {
            let q = self.search.to_lowercase();
            let mut v: Vec<&Task> = self.store.live_tasks().filter(|t| t.title.to_lowercase().contains(&q) || t.notes.to_lowercase().contains(&q) || t.labels.iter().any(|l| l.to_lowercase().contains(&q))).collect();
            sort_tasks(&mut v);
            let v: Vec<Task> = v.into_iter().cloned().collect();
            self.rows(ui, &v, flat, actions, "No matching tasks.");
            return;
        }

        match self.view.clone() {
            View::Inbox => {
                let v = self.top_level(|t| t.group_id.is_none());
                self.rows(ui, &v, tree, actions, "Inbox zero. Add a task above.");
            }
            View::Group(id) => {
                let v = self.top_level(|t| t.group_id.as_deref() == Some(id.as_str()));
                self.rows(ui, &v, tree, actions, "No tasks in this group yet.");
            }
            View::All => {
                let v = self.top_level(|_| true);
                self.rows(ui, &v, RowOpts { show_group: true, ..tree }, actions, "Nothing here yet.");
            }
            View::Label(l) => {
                let v = self.dated(|t| t.labels.iter().any(|x| x.eq_ignore_ascii_case(&l)));
                self.rows(ui, &v, flat, actions, "No tasks with this label.");
            }
            View::Today => {
                let overdue = self.dated(|t| !t.completed && t.date().is_some_and(|d| d < t0));
                if !overdue.is_empty() {
                    section(ui, &self.colors, "Overdue", Some(self.colors.red));
                    self.rows(ui, &overdue, flat, actions, "");
                    section(ui, &self.colors, "Today", None);
                }
                let today_tasks = self.dated(|t| t.date() == Some(t0));
                let empty = if overdue.is_empty() { "Nothing due today. Enjoy! 🎉" } else { "" };
                self.rows(ui, &today_tasks, flat, actions, empty);
            }
            View::Upcoming => {
                let overdue = self.dated(|t| !t.completed && t.date().is_some_and(|d| d < t0));
                if !overdue.is_empty() {
                    section(ui, &self.colors, "Overdue", Some(self.colors.red));
                    self.rows(ui, &overdue, flat, actions, "");
                }
                for i in 0..14 {
                    let d = t0 + Duration::days(i);
                    let v = self.dated(|t| t.date() == Some(d));
                    let label = if i < 2 { format!("{} · {}", d.format("%a %-d %b"), friendly_date(d, t0)) } else { d.format("%A %-d %b").to_string() };
                    section(ui, &self.colors, &label, if i == 0 { Some(self.colors.green) } else { None });
                    self.rows(ui, &v, flat, actions, "");
                    if v.is_empty() {
                        self.day_add_hint(ui, d);
                    }
                }
                let later = self.dated(|t| t.date().is_some_and(|x| x >= t0 + Duration::days(14)));
                if !later.is_empty() {
                    section(ui, &self.colors, "Later", None);
                    self.rows(ui, &later, flat, actions, "");
                }
            }
            View::Completed => {
                let mut v: Vec<Task> = self.store.live_tasks().filter(|t| t.completed).cloned().collect();
                v.sort_by_key(|t| std::cmp::Reverse(t.completed_at.unwrap_or(0)));
                self.rows(ui, &v, flat, actions, "No completed tasks yet.");
            }
            View::Calendar => {}
        }
    }

    fn day_add_hint(&mut self, ui: &mut egui::Ui, _d: NaiveDate) {
        ui.label(RichText::new("  —").color(self.colors.surface2));
    }

    fn top_level(&self, f: impl Fn(&Task) -> bool) -> Vec<Task> {
        self.store.children(None).into_iter().filter(|t| f(t) && self.visible(t)).cloned().collect()
    }

    fn dated(&self, f: impl Fn(&Task) -> bool) -> Vec<Task> {
        let mut v: Vec<&Task> = self.store.live_tasks().filter(|t| f(t) && self.visible(t)).collect();
        sort_tasks(&mut v);
        v.into_iter().cloned().collect()
    }

    pub fn rows(&mut self, ui: &mut egui::Ui, tasks: &[Task], opts: RowOpts, actions: &mut Vec<Action>, empty: &str) {
        if tasks.is_empty() && !empty.is_empty() {
            ui.add_space(30.0);
            ui.vertical_centered(|ui| ui.label(RichText::new(empty).color(self.colors.muted).size(16.0)));
        }
        for t in tasks {
            self.task_row(ui, t, opts, actions);
        }
    }

    pub fn task_row(&mut self, ui: &mut egui::Ui, t: &Task, opts: RowOpts, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let t0 = today();
        let children: Vec<Task> = self.store.children(Some(&t.id)).into_iter().cloned().collect();
        let done_children = children.iter().filter(|x| x.completed).count();
        let collapsed = self.collapsed.contains(&t.id);
        let selected = self.selected.as_deref() == Some(t.id.as_str());

        let fill = if selected { c.tonal } else { c.surface };
        let frame = egui::Frame::new().fill(fill).corner_radius(CornerRadius::same(14)).inner_margin(Margin { left: 8 + (opts.depth as i8).min(8) * 22, right: 12, top: 8, bottom: 8 });
        frame
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    // Expander
                    if opts.tree && !children.is_empty() {
                        let icon = if collapsed { "⏵" } else { "⏷" };
                        if ui.add(egui::Button::new(RichText::new(icon).color(c.muted)).frame(false).min_size(Vec2::new(16.0, 22.0))).clicked() {
                            actions.push(Action::ToggleExpand(t.id.clone()));
                        }
                    } else {
                        ui.add_space(20.0);
                    }
                    // Round checkbox colored by priority
                    let (rect, cb) = ui.allocate_exact_size(Vec2::splat(24.0), Sense::click());
                    let col = c.priority(t.priority);
                    let p = ui.painter();
                    if t.completed {
                        p.circle_filled(rect.center(), 10.0, col);
                        p.text(rect.center(), Align2::CENTER_CENTER, "✔", FontId::proportional(12.0), c.bg);
                    } else {
                        p.circle(rect.center(), 10.0, col.gamma_multiply(if cb.hovered() { 0.35 } else { 0.12 }), Stroke::new(2.0_f32, col));
                        if cb.hovered() {
                            p.text(rect.center(), Align2::CENTER_CENTER, "✔", FontId::proportional(11.0), col);
                        }
                    }
                    if cb.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                        actions.push(Action::Complete(t.id.clone(), !t.completed));
                    }

                    ui.vertical(|ui| {
                        let mut title = RichText::new(&t.title).size(15.5);
                        if t.completed {
                            title = title.strikethrough().color(c.muted);
                        }
                        let r = ui.add(egui::Label::new(title).sense(Sense::click()).truncate());
                        if r.clicked() {
                            actions.push(Action::Select(if selected { None } else { Some(t.id.clone()) }));
                        }
                        self.row_menu(&r, t, actions);
                        ui.horizontal_wrapped(|ui| {
                            ui.spacing_mut().item_spacing.x = 10.0;
                            self.meta(ui, t, opts, t0, &children, done_children);
                        });
                    });
                });
            });
        ui.add_space(4.0);

        if opts.tree && !collapsed {
            let shown: Vec<&Task> = children.iter().filter(|x| self.visible(x)).collect();
            for ch in shown {
                self.task_row(ui, ch, RowOpts { depth: opts.depth + 1, ..opts }, actions);
            }
        }
    }

    fn meta(&self, ui: &mut egui::Ui, t: &Task, opts: RowOpts, t0: NaiveDate, children: &[Task], done_children: usize) {
        let c = &self.colors;
        let small = |s: String, col: Color32| RichText::new(s).size(12.5).color(col);
        if let Some(d) = t.date() {
            let col = if t.is_overdue(Local::now().naive_local()) {
                c.red
            } else if d == t0 {
                c.green
            } else if (d - t0).num_days() < 7 {
                c.purple()
            } else {
                c.muted
            };
            ui.label(small(format!("📅 {}", friendly_due(t, t0)), col));
        }
        if let Some(r) = &t.recurrence {
            ui.label(small(format!("🔁 {}", recur::describe(r)), c.muted));
        }
        if t.urgent {
            ui.label(small("⏰ alarm".into(), c.red));
        }
        if !t.reminders.is_empty() {
            let mut r = t.reminders.clone();
            r.sort();
            ui.label(small(format!("🔔 {}", format_offset(r[0])), c.muted));
        }
        if t.pinned {
            ui.label(small("📌".into(), c.primary));
        }
        if !children.is_empty() {
            ui.label(small(format!("☑ {done_children}/{}", children.len()), c.muted));
        }
        if !t.attachments.is_empty() {
            ui.label(small(format!("📎 {}", t.attachments.len()), c.muted));
        }
        if !t.notes.trim().is_empty() {
            ui.label(small("📝".into(), c.muted));
        }
        for l in &t.labels {
            ui.label(small(format!("@{l}"), c.blue));
        }
        if opts.show_parent {
            if let Some(parent) = t.parent_id.as_deref().and_then(|p| self.store.task(p)) {
                ui.label(small(format!("↪ {}", parent.title), c.muted));
            }
        }
        if opts.show_group {
            if let Some(g) = self.store.group(t.group_id.as_deref()) {
                ui.label(small(format!("⏺ {}", g.name), c.named(&g.color)));
            }
        }
    }

    fn row_menu(&self, r: &egui::Response, t: &Task, actions: &mut Vec<Action>) {
        r.context_menu(|ui| {
            let t0 = today();
            let set_date = |d: Option<NaiveDate>| {
                let mut x = t.clone();
                x.set_date(d);
                if d.is_some() {
                    x.due_time = t.due_time.clone();
                }
                Action::Save(x)
            };
            let label = if t.completed { "↺ Mark not done" } else { "✔ Complete" };
            if ui.button(label).clicked() {
                actions.push(Action::Complete(t.id.clone(), !t.completed));
                ui.close_menu();
            }
            ui.separator();
            let next_week = t0 + Duration::days(7 - t0.weekday().num_days_from_monday() as i64 + self.settings.nlp.next_week_day as i64 - 1);
            for (label, d) in [("☀ Today", Some(t0)), ("🌅 Tomorrow", Some(t0 + Duration::days(1))), ("📅 Next week", Some(next_week)), ("✖ No date", None)] {
                if ui.button(label).clicked() {
                    actions.push(set_date(d));
                    ui.close_menu();
                }
            }
            ui.separator();
            ui.horizontal(|ui| {
                for p in 1..=4u8 {
                    let col = self.colors.priority(p);
                    if ui.add(egui::Button::new(RichText::new(format!("P{p}")).color(col)).selected(t.priority == p)).clicked() {
                        let mut x = t.clone();
                        x.priority = p;
                        actions.push(Action::Save(x));
                        ui.close_menu();
                    }
                }
            });
            ui.menu_button("Move to group", |ui| {
                if ui.button("📥 Inbox").clicked() {
                    let mut x = t.clone();
                    x.group_id = None;
                    actions.push(Action::Save(x));
                    ui.close_menu();
                }
                for g in self.store.live_groups() {
                    if ui.button(RichText::new(format!("⏺ {}", g.name)).color(self.colors.named(&g.color))).clicked() {
                        let mut x = t.clone();
                        x.group_id = Some(g.id.clone());
                        actions.push(Action::Save(x));
                        ui.close_menu();
                    }
                }
            });
            ui.separator();
            let mut x = t.clone();
            if ui.button(if t.pinned { "📌 Unpin notification" } else { "📌 Pin as notification" }).clicked() {
                x.pinned = !t.pinned;
                actions.push(Action::Save(x.clone()));
                if x.pinned {
                    crate::reminders::notify(&self.settings.app_name, &format!("📌 {}", t.title), &friendly_due(t, t0), true);
                }
                ui.close_menu();
            }
            if ui.button(if t.urgent { "⏰ Turn alarm off" } else { "⏰ Urgent (ring alarm)" }).clicked() {
                let mut x = t.clone();
                x.urgent = !t.urgent;
                actions.push(Action::Save(x));
                ui.close_menu();
            }
            if ui.button("➕ Add subtask").clicked() {
                actions.push(Action::Select(Some(t.id.clone())));
                ui.close_menu();
            }
            ui.separator();
            if ui.button(RichText::new("🗑 Delete").color(self.colors.red)).clicked() {
                actions.push(Action::Delete(t.id.clone()));
                ui.close_menu();
            }
        });
    }
}

fn section(ui: &mut egui::Ui, c: &Colors, label: &str, color: Option<Color32>) {
    ui.add_space(10.0);
    ui.label(RichText::new(label).strong().size(14.0).color(color.unwrap_or(c.fg)));
    ui.add_space(2.0);
}

/// Shows what the quick-add parser understood.
pub fn preview_chips(ui: &mut egui::Ui, c: &Colors, p: &nlp::Parsed, default_date: Option<NaiveDate>) {
    let t0 = today();
    let bg = c.tonal;
    if let Some(d) = p.date.or(default_date) {
        let mut s = format!("📅 {}", friendly_date(d, t0));
        if d.year() != t0.year() || (d - t0).num_days() > 6 {
            s = format!("📅 {}", d.format("%a %-d %b %Y"));
        }
        if let Some(tm) = p.time {
            s.push_str(&format!(" {}", fmt_time(tm)));
        }
        style::chip(ui, s, c.fg, bg);
    } else if let Some(tm) = p.time {
        style::chip(ui, format!("🕑 {}", fmt_time(tm)), c.fg, bg);
    }
    if let Some(r) = &p.recurrence {
        style::chip(ui, format!("🔁 {}", recur::describe(r)), c.fg, bg);
    }
    if let Some(g) = &p.group {
        style::chip(ui, format!("# {g}"), c.fg, bg);
    }
    if let Some(pr) = p.priority {
        style::chip(ui, format!("P{pr}"), c.priority(pr), bg);
    }
    for l in &p.labels {
        style::chip(ui, format!("@{l}"), c.fg, bg);
    }
    for m in &p.reminders {
        style::chip(ui, format!("🔔 {} before", format_offset(*m)), c.fg, bg);
    }
    if p.urgent {
        style::chip(ui, "⏰ alarm", c.red, bg);
    }
    if !p.title.is_empty() {
        ui.label(RichText::new(format!("→ “{}”", p.title)).color(c.muted).size(12.5));
    }
}

impl Colors {
    pub fn purple(&self) -> Color32 {
        style::hex(&self.palette.purple)
    }
}
