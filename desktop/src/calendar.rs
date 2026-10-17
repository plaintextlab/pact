//! Calendar: month, week, day and agenda views of events, dated tasks and
//! recurring-task projections.

use chrono::{Datelike, Duration, NaiveDate, NaiveDateTime, NaiveTime, Timelike};
use eframe::egui::{self, Align2, Color32, CornerRadius, FontId, Pos2, Rect, RichText, Sense, Stroke, StrokeKind, Vec2};
use pact_core::recur;
use pact_core::store::sort_tasks;

use crate::app::{today, Action, PactApp};
use crate::list::{fmt_time, friendly_date, RowOpts};
use crate::style;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CalMode {
    Month,
    Week,
    Day,
    Agenda,
}

impl CalMode {
    pub fn from_setting(s: &str) -> Self {
        match s {
            "week" => Self::Week,
            "day" => Self::Day,
            "agenda" => Self::Agenda,
            _ => Self::Month,
        }
    }

    pub fn setting(self) -> &'static str {
        match self {
            Self::Month => "month",
            Self::Week => "week",
            Self::Day => "day",
            Self::Agenda => "agenda",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum EntryKind {
    /// Event id and occurrence date.
    Event(String, NaiveDate),
    Task(String),
}

#[derive(Clone, Debug)]
pub struct Entry {
    pub kind: EntryKind,
    pub title: String,
    pub start: NaiveDateTime,
    pub end: NaiveDateTime,
    pub all_day: bool,
    pub color: Color32,
    pub location: String,
    pub repeats: bool,
    /// A future instance of a recurring task (not a stored task).
    pub projected: bool,
    pub done: bool,
}

impl Entry {
    fn last_day(&self) -> NaiveDate {
        if !self.all_day && self.end.time() == NaiveTime::MIN && self.end.date() > self.start.date() {
            self.end.date() - Duration::days(1)
        } else {
            self.end.date()
        }
    }

    pub fn covers(&self, d: NaiveDate) -> bool {
        self.start.date() <= d && self.last_day() >= d
    }

    fn time_text(&self) -> String {
        match (&self.kind, self.all_day) {
            (EntryKind::Task(_), true) => "Task".into(),
            (EntryKind::Task(_), false) => format!("Task · {}", fmt_time(self.start.time())),
            (_, true) if self.last_day() != self.start.date() => format!("{} – {}", self.start.format("%-d %b"), self.last_day().format("%-d %b")),
            (_, true) => "All day".into(),
            _ => format!("{} – {}", fmt_time(self.start.time()), fmt_time(self.end.time())),
        }
    }

    fn action(&self) -> Action {
        match &self.kind {
            EntryKind::Event(id, d) => Action::OpenEvent(id.clone(), Some(*d)),
            EntryKind::Task(id) => Action::Select(Some(id.clone())),
        }
    }
}

fn start_of_week(d: NaiveDate, week_start: u32) -> NaiveDate {
    d - Duration::days(((d.weekday().number_from_monday() + 7 - week_start) % 7) as i64)
}

const HOUR_H: f32 = 46.0;
const GUTTER: f32 = 48.0;

impl PactApp {
    fn week_start(&self) -> u32 {
        if self.settings.nlp.week_start == 7 { 7 } else { 1 }
    }

    /// The day range the current mode shows.
    fn cal_range(&self) -> (NaiveDate, NaiveDate) {
        match self.cal_mode {
            CalMode::Month => {
                let s = start_of_week(self.cal_month, self.week_start());
                (s, s + Duration::days(41))
            }
            CalMode::Week => {
                let s = start_of_week(self.cal_day, self.week_start());
                (s, s + Duration::days(6))
            }
            CalMode::Day => (self.cal_day, self.cal_day),
            CalMode::Agenda => (self.cal_day, self.cal_day + Duration::days(60)),
        }
    }

    pub fn event_color(&self, e: &pact_core::event::Event) -> Color32 {
        match (&e.color, self.store.group(e.group_id.as_deref())) {
            (Some(c), _) => self.colors.named(c),
            (None, Some(g)) => self.colors.named(&g.color),
            _ => self.colors.primary,
        }
    }

    pub fn cal_entries(&self, from: NaiveDate, to: NaiveDate) -> Vec<Entry> {
        let mut out: Vec<Entry> = self
            .store
            .agenda(from, to)
            .into_iter()
            .map(|(e, o)| Entry {
                kind: EntryKind::Event(e.id.clone(), o.date()),
                title: e.title.clone(),
                start: o.start,
                end: o.end,
                all_day: o.all_day,
                color: self.event_color(e),
                location: e.location.clone(),
                repeats: e.recurrence.is_some(),
                projected: false,
                done: false,
            })
            .collect();
        if self.settings.calendar_show_tasks {
            // Deadlines as their own red markers.
            for t in self.store.live_tasks().filter(|t| self.settings.show_completed || !t.completed) {
                let Some(d) = t.deadline().filter(|d| *d >= from && *d <= to) else { continue };
                let start = d.and_time(t.deadline_clock().unwrap_or(NaiveTime::MIN));
                out.push(Entry {
                    kind: EntryKind::Task(t.id.clone()),
                    title: format!("⚑ {}", t.title),
                    start,
                    end: start,
                    all_day: t.deadline_clock().is_none(),
                    color: self.colors.red,
                    location: String::new(),
                    repeats: false,
                    projected: false,
                    done: t.completed,
                });
            }
            let mut tasks: Vec<_> = self.store.live_tasks().filter(|t| t.date().is_some() && (self.settings.show_completed || !t.completed)).collect();
            sort_tasks(&mut tasks);
            for t in tasks {
                let color = self.store.group(t.group_id.as_deref()).map(|g| self.colors.named(&g.color)).unwrap_or(self.colors.muted);
                let entry = |d: NaiveDate, projected: bool| {
                    let start = d.and_time(t.time().unwrap_or(NaiveTime::MIN));
                    Entry {
                        kind: EntryKind::Task(t.id.clone()),
                        title: t.title.clone(),
                        start,
                        end: if t.time().is_some() { start + Duration::minutes(30) } else { start },
                        all_day: t.time().is_none(),
                        color,
                        location: String::new(),
                        repeats: t.recurrence.is_some(),
                        projected,
                        done: t.completed,
                    }
                };
                let d = t.date().unwrap();
                if d >= from && d <= to {
                    out.push(entry(d, false));
                }
                if let (Some(r), false) = (&t.recurrence, t.completed) {
                    let mut x = recur::next_after(r, d);
                    for _ in 0..400 {
                        if x > to || r.until_date().is_some_and(|u| x > u) {
                            break;
                        }
                        if x >= from {
                            out.push(entry(x, true));
                        }
                        x = recur::next_after(r, x);
                    }
                }
            }
        }
        out.sort_by(|a, b| (!a.all_day, a.start, &a.title).cmp(&(!b.all_day, b.start, &b.title)));
        out
    }

    fn cal_move(&mut self, step: i64) {
        match self.cal_mode {
            CalMode::Month => {
                self.cal_month = recur::add_months(self.cal_month, step, 1);
                self.cal_day = recur::ymd_clamped(self.cal_month.year(), self.cal_month.month(), self.cal_day.day());
            }
            CalMode::Week => self.cal_day += Duration::weeks(step),
            CalMode::Day => self.cal_day += Duration::days(step),
            CalMode::Agenda => self.cal_day += Duration::days(30 * step),
        }
        if self.cal_mode != CalMode::Month {
            self.cal_month = self.cal_day.with_day(1).unwrap();
        }
    }

    pub fn calendar_view(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let (from, to) = self.cal_range();
        let entries = self.cal_entries(from, to);

        ui.horizontal(|ui| {
            let title = match self.cal_mode {
                CalMode::Month => self.cal_month.format("%B %Y").to_string(),
                CalMode::Week => format!("{} – {}", from.format("%-d %b"), to.format("%-d %b %Y")),
                CalMode::Day => self.cal_day.format("%A, %-d %B %Y").to_string(),
                CalMode::Agenda => format!("From {}", self.cal_day.format("%a %-d %b")),
            };
            ui.heading(RichText::new(title).strong());
            ui.add_space(12.0);
            if ui.button("‹").on_hover_text("Previous").clicked() {
                self.cal_move(-1);
            }
            if style::tonal_button(ui, &c, "Today").clicked() {
                self.cal_day = today();
                self.cal_month = today().with_day(1).unwrap();
            }
            if ui.button("›").on_hover_text("Next").clicked() {
                self.cal_move(1);
            }
        });
        ui.horizontal_wrapped(|ui| {
            for (m, label) in [(CalMode::Month, "Month"), (CalMode::Week, "Week"), (CalMode::Day, "Day"), (CalMode::Agenda, "Agenda")] {
                if ui.selectable_label(self.cal_mode == m, label).clicked() && self.cal_mode != m {
                    self.cal_mode = m;
                    self.settings.calendar_view = m.setting().into();
                    self.save_settings();
                }
            }
            ui.add_space(16.0);
            if style::filled_button(ui, &c, "+ Event").clicked() {
                let start = self.cal_day.and_hms_opt((chrono::Local::now().hour() + 1).min(23), 0, 0).unwrap();
                actions.push(Action::NewEvent(start, false));
            }
            if ui.button("Import .ics").clicked() {
                actions.push(Action::ImportIcs);
            }
            if ui.button("Export .ics").clicked() {
                actions.push(Action::ExportIcs);
            }
        });
        ui.add_space(6.0);

        match self.cal_mode {
            CalMode::Month => self.month_view(ui, &entries, from, actions),
            CalMode::Week | CalMode::Day => {
                let days: Vec<NaiveDate> = (0..=(to - from).num_days()).map(|i| from + Duration::days(i)).collect();
                self.time_grid(ui, &days, &entries, actions);
            }
            CalMode::Agenda => self.agenda_view(ui, &entries, from, to, actions),
        }
    }

    fn month_view(&mut self, ui: &mut egui::Ui, entries: &[Entry], grid_start: NaiveDate, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let t0 = today();
        let first = self.cal_month;
        let gap = 6.0;
        let cell_w = ((ui.available_width() - gap * 6.0) / 7.0).max(60.0);
        let cell_h = ((ui.available_height() * 0.6 - 30.0) / 6.0).clamp(64.0, 120.0);

        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = gap;
            for i in 0..7 {
                let wd = grid_start + Duration::days(i);
                let (r, _) = ui.allocate_exact_size(Vec2::new(cell_w, 18.0), Sense::hover());
                ui.painter().text(r.center(), Align2::CENTER_CENTER, wd.format("%a").to_string(), FontId::proportional(12.5), c.muted);
            }
        });
        for week in 0..6 {
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = gap;
                for i in 0..7 {
                    let d = grid_start + Duration::days(week * 7 + i);
                    let (rect, resp) = ui.allocate_exact_size(Vec2::new(cell_w, cell_h), Sense::click());
                    let in_month = d.month() == first.month();
                    let selected = d == self.cal_day;
                    let p = ui.painter_at(rect);
                    let bg = if selected { c.tonal } else if resp.hovered() { style::mix(c.surface, c.surface2, 0.6) } else { c.surface };
                    p.rect_filled(rect, CornerRadius::same(12), if in_month { bg } else { bg.gamma_multiply(0.45) });
                    if selected {
                        p.rect_stroke(rect, CornerRadius::same(12), Stroke::new(1.5_f32, c.primary), StrokeKind::Inside);
                    }
                    let num_pos = rect.left_top() + Vec2::new(16.0, 14.0);
                    if d == t0 {
                        p.circle_filled(num_pos, 12.0, c.primary);
                    }
                    let num_color = if d == t0 { c.on_primary } else if in_month { c.fg } else { c.muted };
                    p.text(num_pos, Align2::CENTER_CENTER, d.day().to_string(), FontId::proportional(13.0), num_color);

                    // Events before tasks so a busy day still shows its appointments.
                    let mut list: Vec<&Entry> = entries.iter().filter(|e| e.covers(d)).collect();
                    list.sort_by_key(|e| matches!(e.kind, EntryKind::Task(_)));
                    let line_h = 17.0;
                    let max_lines = (((cell_h - 32.0) / line_h).floor() as usize).max(1);
                    for (j, e) in list.iter().take(max_lines).enumerate() {
                        let y = rect.top() + 29.0 + j as f32 * line_h;
                        let chip = Rect::from_min_size(Pos2::new(rect.left() + 4.0, y), Vec2::new(rect.width() - 8.0, line_h - 2.0));
                        let col = if e.projected { e.color.gamma_multiply(0.4) } else { e.color };
                        match e.kind {
                            // Events: filled chips. Tasks: a colored bar and plain text.
                            EntryKind::Event(..) => {
                                p.rect_filled(chip, CornerRadius::same(4), col);
                                // Narrow cells keep the title; wide ones also show the start time.
                                let text = if e.all_day || rect.width() < 140.0 { e.title.clone() } else { format!("{} {}", fmt_time(e.start.time()), e.title) };
                                p.with_clip_rect(chip).text(chip.left_center() + Vec2::new(4.0, 0.0), Align2::LEFT_CENTER, text, FontId::proportional(11.5), c.bg);
                            }
                            EntryKind::Task(_) => {
                                p.rect_filled(Rect::from_min_size(chip.min, Vec2::new(3.0, chip.height())), CornerRadius::same(2), col);
                                let fg = if e.projected || e.done { c.muted } else { c.fg };
                                let text = if e.projected { format!("↻ {}", e.title) } else { e.title.clone() };
                                p.with_clip_rect(chip).text(chip.left_center() + Vec2::new(7.0, 0.0), Align2::LEFT_CENTER, text, FontId::proportional(11.5), fg);
                            }
                        }
                    }
                    if list.len() > max_lines {
                        p.text(rect.right_top() + Vec2::new(-8.0, 8.0), Align2::RIGHT_TOP, format!("+{}", list.len() - max_lines), FontId::proportional(11.5), c.muted);
                    }
                    if resp.clicked() {
                        self.cal_day = d;
                        if !in_month {
                            self.cal_month = d.with_day(1).unwrap();
                        }
                    }
                    if resp.double_clicked() {
                        actions.push(Action::NewEvent(d.and_hms_opt(9, 0, 0).unwrap(), false));
                    }
                    resp.on_hover_text("Click to select · double-click to add an event");
                }
            });
        }

        ui.add_space(12.0);
        let day = self.cal_day;
        ui.label(RichText::new(format!("{} · {}", day.format("%A %-d %B"), friendly_date(day, t0))).strong().size(16.0));
        ui.add_space(4.0);
        let defaults = self.quick_add_defaults();
        self.quick_add_bar(ui, actions, defaults);
        ui.add_space(6.0);
        let day_entries: Vec<Entry> = entries.iter().filter(|e| e.covers(day)).cloned().collect();
        egui::ScrollArea::vertical().id_salt("cal-day").auto_shrink([false, false]).show(ui, |ui| {
            if day_entries.is_empty() {
                ui.label(RichText::new("Nothing scheduled. Type above to add an event or task for this day.").color(c.muted));
            }
            self.entry_rows(ui, &day_entries, actions);
        });
    }

    /// Events as cards; real tasks as full task rows.
    fn entry_rows(&mut self, ui: &mut egui::Ui, list: &[Entry], actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        for e in list {
            if let (EntryKind::Task(id), false) = (&e.kind, e.projected) {
                if let Some(t) = self.store.task(id).cloned() {
                    self.task_row(ui, &t, RowOpts { depth: 0, tree: false, show_group: true, show_parent: true }, actions);
                    continue;
                }
            }
            let resp = egui::Frame::new()
                .fill(c.surface)
                .corner_radius(CornerRadius::same(14))
                .inner_margin(egui::Margin::symmetric(12, 8))
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.horizontal(|ui| {
                        let (bar, _) = ui.allocate_exact_size(Vec2::new(5.0, 34.0), Sense::hover());
                        ui.painter().rect_filled(bar, CornerRadius::same(3), if e.projected { e.color.gamma_multiply(0.4) } else { e.color });
                        ui.vertical(|ui| {
                            let fg = if e.projected { c.muted } else { c.fg };
                            ui.label(RichText::new(&e.title).size(15.5).color(fg));
                            let mut meta = e.time_text();
                            if e.repeats {
                                meta.push_str("  🔁");
                            }
                            if !e.location.is_empty() {
                                meta.push_str(&format!("  ·  {}", e.location));
                            }
                            ui.label(RichText::new(meta).size(12.5).color(c.muted));
                        });
                    });
                })
                .response
                .interact(Sense::click());
            if resp.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                actions.push(e.action());
            }
            ui.add_space(4.0);
        }
    }

    fn agenda_view(&mut self, ui: &mut egui::Ui, entries: &[Entry], from: NaiveDate, to: NaiveDate, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let t0 = today();
        egui::ScrollArea::vertical().id_salt("agenda").auto_shrink([false, false]).show(ui, |ui| {
            let mut any = false;
            let mut d = from;
            while d <= to {
                // Multi-day entries show on their first visible day only.
                let list: Vec<Entry> = entries.iter().filter(|e| e.covers(d) && (e.start.date() == d || d == from)).cloned().collect();
                if !list.is_empty() {
                    any = true;
                    ui.add_space(10.0);
                    let color = if d == t0 { c.primary } else { c.fg };
                    ui.label(RichText::new(format!("{} · {}", d.format("%a %-d %b"), friendly_date(d, t0))).strong().color(color));
                    self.entry_rows(ui, &list, actions);
                }
                d += Duration::days(1);
            }
            if !any {
                ui.label(RichText::new("Nothing in the next two months.").color(c.muted));
            }
            ui.add_space(40.0);
        });
    }

    fn time_grid(&mut self, ui: &mut egui::Ui, days: &[NaiveDate], entries: &[Entry], actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let t0 = today();
        let n = days.len() as f32;
        let col_w = ((ui.available_width() - GUTTER - 14.0) / n).max(40.0);

        // Day headers; clicking one opens that day.
        ui.horizontal(|ui| {
            ui.add_space(GUTTER);
            ui.spacing_mut().item_spacing.x = 0.0;
            for d in days {
                let (r, resp) = ui.allocate_exact_size(Vec2::new(col_w, 40.0), Sense::click());
                let p = ui.painter();
                p.text(r.center_top() + Vec2::new(0.0, 8.0), Align2::CENTER_CENTER, d.format("%a").to_string(), FontId::proportional(12.0), c.muted);
                let num = r.center_top() + Vec2::new(0.0, 26.0);
                if *d == t0 {
                    p.circle_filled(num, 13.0, c.primary);
                }
                p.text(num, Align2::CENTER_CENTER, d.day().to_string(), FontId::proportional(15.0), if *d == t0 { c.on_primary } else { c.fg });
                if resp.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                    self.cal_day = *d;
                    self.cal_mode = CalMode::Day;
                }
            }
        });
        // All-day strip.
        let all_day: Vec<Vec<&Entry>> = days.iter().map(|d| entries.iter().filter(|e| e.all_day && e.covers(*d)).collect()).collect();
        let rows = all_day.iter().map(|l| l.len().min(3)).max().unwrap_or(0);
        if rows > 0 {
            ui.horizontal(|ui| {
                ui.add_space(GUTTER);
                ui.spacing_mut().item_spacing.x = 0.0;
                for list in &all_day {
                    let (r, _) = ui.allocate_exact_size(Vec2::new(col_w, rows as f32 * 20.0 + 4.0), Sense::hover());
                    for (j, e) in list.iter().take(3).enumerate() {
                        let chip = Rect::from_min_size(r.min + Vec2::new(2.0, j as f32 * 20.0), Vec2::new(col_w - 4.0, 18.0));
                        let resp = ui.interact(chip, ui.id().with(("allday", &e.title, j, r.min.x as i32)), Sense::click());
                        let p = ui.painter();
                        p.rect_filled(chip, CornerRadius::same(4), if e.projected { e.color.gamma_multiply(0.4) } else { e.color });
                        p.with_clip_rect(chip).text(chip.left_center() + Vec2::new(4.0, 0.0), Align2::LEFT_CENTER, &e.title, FontId::proportional(11.5), c.bg);
                        if resp.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                            actions.push(e.action());
                        }
                    }
                }
            });
        }

        egui::ScrollArea::vertical().id_salt(("grid", days[0])).auto_shrink([false, false]).show(ui, |ui| {
            let (rect, resp) = ui.allocate_exact_size(Vec2::new(GUTTER + col_w * n, HOUR_H * 24.0 + 20.0), Sense::click());
            let p = ui.painter_at(rect);
            let top = rect.top() + 10.0;
            for h in 0..=24 {
                let y = top + h as f32 * HOUR_H;
                p.line_segment([Pos2::new(rect.left() + GUTTER, y), Pos2::new(rect.right(), y)], Stroke::new(1.0_f32, c.surface2));
                if h > 0 && h < 24 {
                    let label = fmt_time(NaiveTime::from_hms_opt(h, 0, 0).unwrap()).replace(":00", "");
                    p.text(Pos2::new(rect.left() + GUTTER - 6.0, y), Align2::RIGHT_CENTER, label, FontId::proportional(11.0), c.muted);
                }
            }
            let mut hits: Vec<(Rect, Action)> = vec![];
            for (i, d) in days.iter().enumerate() {
                let x0 = rect.left() + GUTTER + i as f32 * col_w;
                p.line_segment([Pos2::new(x0, top), Pos2::new(x0, top + HOUR_H * 24.0)], Stroke::new(1.0_f32, c.surface2));
                for pl in layout_day(*d, entries) {
                    let w = col_w / pl.lanes as f32;
                    let r = Rect::from_min_max(
                        Pos2::new(x0 + w * pl.lane as f32 + 1.0, top + pl.top as f32 / 60.0 * HOUR_H + 1.0),
                        Pos2::new(x0 + w * (pl.lane + 1) as f32 - 1.0, top + pl.bottom as f32 / 60.0 * HOUR_H - 1.0),
                    );
                    let e = &entries[pl.index];
                    p.rect_filled(r, CornerRadius::same(6), if e.projected { e.color.gamma_multiply(0.4) } else { e.color.gamma_multiply(0.9) });
                    let clip = p.with_clip_rect(r.shrink(3.0));
                    clip.text(r.left_top() + Vec2::new(5.0, 3.0), Align2::LEFT_TOP, &e.title, FontId::proportional(12.0), c.bg);
                    if r.height() > 34.0 {
                        clip.text(r.left_top() + Vec2::new(5.0, 18.0), Align2::LEFT_TOP, fmt_time(e.start.time()), FontId::proportional(11.0), c.bg);
                    }
                    hits.push((r, e.action()));
                }
                if *d == t0 {
                    let now = chrono::Local::now().time();
                    let y = top + (now.num_seconds_from_midnight() as f32 / 3600.0) * HOUR_H;
                    p.line_segment([Pos2::new(x0, y), Pos2::new(x0 + col_w, y)], Stroke::new(2.0_f32, c.red));
                    p.circle_filled(Pos2::new(x0, y), 4.0, c.red);
                }
            }
            if let Some(pos) = resp.interact_pointer_pos() {
                if resp.clicked() {
                    match hits.iter().rev().find(|(r, _)| r.contains(pos)) {
                        Some((_, a)) => actions.push(a.clone()),
                        None if pos.x > rect.left() + GUTTER => {
                            let i = (((pos.x - rect.left() - GUTTER) / col_w) as usize).min(days.len() - 1);
                            let minutes = (((pos.y - top) / HOUR_H * 60.0) as i64 / 30 * 30).clamp(0, 23 * 60 + 30);
                            actions.push(Action::NewEvent(days[i].and_time(NaiveTime::MIN) + Duration::minutes(minutes), false));
                        }
                        None => {}
                    }
                }
            }
            if resp.hovered() {
                ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
            }
            // Start scrolled to the working day.
            let key = ui.id().with(("scrolled", days[0]));
            if !ui.ctx().data(|d| d.get_temp::<bool>(key).unwrap_or(false)) {
                ui.ctx().data_mut(|d| d.insert_temp(key, true));
                let h = if days.contains(&t0) { chrono::Local::now().hour().saturating_sub(2) } else { 7 };
                ui.scroll_to_rect(Rect::from_min_size(Pos2::new(rect.left(), top + h as f32 * HOUR_H), Vec2::new(1.0, rect.height() * 0.5)), Some(egui::Align::TOP));
            }
        });
    }
}

struct Placed {
    index: usize,
    top: i64,
    bottom: i64,
    lane: usize,
    lanes: usize,
}

/// Side-by-side lanes for overlapping timed entries, in minutes from midnight.
fn layout_day(day: NaiveDate, entries: &[Entry]) -> Vec<Placed> {
    let mut items: Vec<(usize, i64, i64)> = entries
        .iter()
        .enumerate()
        .filter(|(_, e)| !e.all_day && e.covers(day))
        .map(|(i, e)| {
            let top = if e.start.date() < day { 0 } else { e.start.time().num_seconds_from_midnight() as i64 / 60 };
            let bottom = if e.end.date() > day { 1440 } else { e.end.time().num_seconds_from_midnight() as i64 / 60 };
            (i, top, bottom.max(top + 20))
        })
        .collect();
    items.sort_by_key(|&(_, t, b)| (t, -b));
    let mut out = vec![];
    let mut cluster: Vec<(usize, i64, i64, usize)> = vec![];
    let mut cluster_end = -1;
    let mut lane_ends: Vec<i64> = vec![];
    let flush = |cluster: &mut Vec<(usize, i64, i64, usize)>, out: &mut Vec<Placed>| {
        let lanes = cluster.iter().map(|c| c.3).max().map_or(0, |m| m + 1);
        for (index, top, bottom, lane) in cluster.drain(..) {
            out.push(Placed { index, top, bottom, lane, lanes });
        }
    };
    for (i, t, b) in items {
        if t >= cluster_end {
            flush(&mut cluster, &mut out);
            lane_ends.clear();
        }
        let lane = match lane_ends.iter().position(|end| *end <= t) {
            Some(l) => {
                lane_ends[l] = b;
                l
            }
            None => {
                lane_ends.push(b);
                lane_ends.len() - 1
            }
        };
        cluster.push((i, t, b, lane));
        cluster_end = cluster_end.max(b);
    }
    flush(&mut cluster, &mut out);
    out
}
