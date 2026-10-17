//! Right-hand event editor. Unlike tasks (saved as you type), events are edited as a
//! draft and saved explicitly, because recurring events ask "this one or all?".

use chrono::{Datelike, Duration, Local, NaiveDate, NaiveDateTime, NaiveTime, Timelike};
use eframe::egui::{self, CornerRadius, Margin, RichText, Vec2};
use pact_core::event::Event;
use pact_core::model::{Freq, Recurrence, GROUP_COLORS};
use pact_core::nlp;
use pact_core::recur;
use pact_core::settings::format_offset;

use crate::app::{Action, PactApp};
use crate::list::{clock_24h, fmt_time};
use crate::style;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EditScope {
    This,
    All,
}

pub struct EventEdit {
    pub original: Option<Event>,
    /// Occurrence date when editing one instance of a series.
    pub occurrence: Option<NaiveDate>,
    pub draft: Event,
    pub quick: String,
    /// "save" or "delete" while asking which occurrences to change.
    pub ask_scope: Option<&'static str>,
    pub custom_minutes: i64,
}

impl EventEdit {
    pub fn open(original: Event, occurrence: Option<NaiveDate>) -> Self {
        let draft = match occurrence.and_then(|d| original.occurrences(d, d).into_iter().find(|o| o.date() == d)) {
            Some(o) => {
                let mut e = original.clone();
                e.set_span(o.start, o.end, o.all_day);
                e
            }
            None => original.clone(),
        };
        Self { original: Some(original), occurrence, draft, quick: String::new(), ask_scope: None, custom_minutes: 30 }
    }

    pub fn new_event(start: NaiveDateTime, all_day: bool, minutes: u32, reminders: Vec<i64>, group: Option<String>) -> Self {
        let mut e = Event::new("", start.date(), (!all_day).then_some(start.time()));
        if !all_day {
            e.set_span(start, start + Duration::minutes(minutes as i64), false);
        }
        e.reminders = reminders;
        e.group_id = group;
        Self { original: None, occurrence: None, draft: e, quick: String::new(), ask_scope: None, custom_minutes: 30 }
    }

    fn recurring_instance(&self) -> bool {
        self.original.as_ref().is_some_and(|o| o.recurrence.is_some()) && self.occurrence.is_some()
    }
}

/// Hour/minute editor honoring the 12/24-hour setting.
fn time_edit(ui: &mut egui::Ui, t: &mut NaiveTime) -> bool {
    let (mut h, mut m) = (t.hour(), t.minute());
    let mut changed = false;
    if clock_24h() {
        changed |= ui.add(egui::DragValue::new(&mut h).range(0..=23).custom_formatter(|v, _| format!("{v:02}"))).changed();
        ui.label(":");
        changed |= ui.add(egui::DragValue::new(&mut m).range(0..=59).speed(0.5).custom_formatter(|v, _| format!("{v:02}"))).changed();
    } else {
        let mut h12 = if h % 12 == 0 { 12 } else { h % 12 };
        let mut pm = h >= 12;
        changed |= ui.add(egui::DragValue::new(&mut h12).range(1..=12)).changed();
        ui.label(":");
        changed |= ui.add(egui::DragValue::new(&mut m).range(0..=59).speed(0.5).custom_formatter(|v, _| format!("{v:02}"))).changed();
        changed |= ui.selectable_value(&mut pm, false, "AM").changed();
        changed |= ui.selectable_value(&mut pm, true, "PM").changed();
        h = h12 % 12 + if pm { 12 } else { 0 };
    }
    if changed {
        *t = NaiveTime::from_hms_opt(h, m, 0).unwrap_or(*t);
    }
    changed
}

fn presets(d: NaiveDate) -> Vec<(String, Option<Recurrence>)> {
    vec![
        ("Does not repeat".into(), None),
        ("Every day".into(), Some(Recurrence::default())),
        ("Every weekday (Mon–Fri)".into(), Some(Recurrence { workdays: true, ..Default::default() })),
        (format!("Every week on {}", d.format("%A")), Some(Recurrence { freq: Freq::Weekly, weekdays: vec![d.weekday().number_from_monday()], ..Default::default() })),
        (format!("Every month on the {}", recur::ordinal(d.day())), Some(Recurrence { freq: Freq::Monthly, month_day: Some(d.day()), ..Default::default() })),
        (format!("Every year on {}", d.format("%b %-d")), Some(Recurrence { freq: Freq::Yearly, month: Some(d.month()), month_day: Some(d.day()), ..Default::default() })),
    ]
}

impl PactApp {
    pub fn event_panel(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let Some(mut ed) = self.event_edit.take() else { return };
        let c = self.colors.clone();
        let mut close = false;

        egui::SidePanel::right("event")
            .resizable(true)
            .default_width(420.0)
            .min_width(340.0)
            .frame(egui::Frame::new().fill(c.surface).inner_margin(Margin::same(18)))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new(if ed.original.is_some() { "Edit event" } else { "New event" }).size(18.0).strong());
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.add(egui::Button::new(RichText::new("✖").size(16.0)).frame(false)).on_hover_text("Close").clicked() {
                            close = true;
                        }
                        let can_save = !ed.draft.title.trim().is_empty();
                        if ui.add_enabled(can_save, egui::Button::new(RichText::new("Save").color(c.on_primary).strong()).fill(c.primary).corner_radius(CornerRadius::same(16))).clicked() {
                            if ed.recurring_instance() {
                                ed.ask_scope = Some("save");
                            } else {
                                actions.push(Action::SaveEvent(EditScope::All));
                            }
                        }
                    });
                });
                egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| self.event_form(ui, &mut ed, actions));
            });

        if let Some(what) = ed.ask_scope {
            egui::Modal::new(egui::Id::new("event-scope")).show(ctx, |ui| {
                ui.set_width(360.0);
                ui.heading(if what == "save" { "Save recurring event" } else { "Delete recurring event" });
                ui.label("Apply to this event only, or to every event in the series?");
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    let mut pick = |scope| {
                        actions.push(if what == "save" { Action::SaveEvent(scope) } else { Action::DeleteEvent(scope) });
                    };
                    if style::tonal_button(ui, &c, "This event").clicked() {
                        pick(EditScope::This);
                        ed.ask_scope = None;
                    }
                    if style::tonal_button(ui, &c, "All events").clicked() {
                        pick(EditScope::All);
                        ed.ask_scope = None;
                    }
                    if ui.button("Cancel").clicked() {
                        ed.ask_scope = None;
                    }
                });
            });
        }
        if !close {
            self.event_edit = Some(ed);
        }
    }

    fn event_form(&mut self, ui: &mut egui::Ui, ed: &mut EventEdit, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let recurring = ed.recurring_instance();
        let e = &mut ed.draft;

        if ed.original.is_none() {
            let r = ui.add(egui::TextEdit::singleline(&mut ed.quick).hint_text("Quick: Lunch tomorrow 1-2pm #Work").desired_width(f32::INFINITY));
            if !ed.quick.trim().is_empty() {
                let q = nlp::parse_event(&ed.quick, Local::now().naive_local(), &self.settings.nlp);
                let when = match (q.start_time, q.end_time) {
                    (Some(s), Some(t)) => format!("{} {}–{}", q.date.format("%a %-d %b"), fmt_time(s), fmt_time(t)),
                    _ => format!("{} · all day", q.date.format("%a %-d %b")),
                };
                let rec = q.recurrence.as_ref().map(|r| format!(" · {}", recur::describe(r))).unwrap_or_default();
                ui.label(RichText::new(format!("→ “{}” · {when}{rec}", q.title)).size(12.0).color(c.muted));
                if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                    match (q.start_time, q.end_time) {
                        (Some(s), Some(t)) => e.set_span(q.date.and_time(s), q.end_date.and_time(t), false),
                        _ => e.set_span(q.date.and_time(NaiveTime::MIN), q.end_date.and_time(NaiveTime::MIN), true),
                    }
                    if !q.title.is_empty() {
                        e.title = q.title;
                    }
                    if q.recurrence.is_some() {
                        e.recurrence = q.recurrence;
                    }
                    if !q.reminders.is_empty() {
                        e.reminders = q.reminders;
                    }
                    e.urgent |= q.urgent;
                    if let Some(g) = q.group {
                        if let Ok(id) = self.store.group_id_for_name(&g) {
                            e.group_id = Some(id);
                        }
                    }
                    ed.quick.clear();
                }
            }
            ui.add_space(4.0);
        }
        ui.add(egui::TextEdit::singleline(&mut e.title).hint_text("Title").font(egui::FontId::proportional(20.0)).desired_width(f32::INFINITY));

        style::section_title(ui, &c, "WHEN");
        let mut all_day = e.all_day();
        if ui.checkbox(&mut all_day, "All day").changed() {
            let (s, en) = (e.start(), e.end());
            if all_day {
                e.set_span(s.date().and_time(NaiveTime::MIN), en.date().and_time(NaiveTime::MIN), true);
            } else {
                let s9 = s.date().and_hms_opt(9, 0, 0).unwrap();
                e.set_span(s9, s9 + Duration::minutes(self.settings.event_minutes as i64), false);
            }
        }
        for (label, is_start) in [("Starts", true), ("Ends", false)] {
            ui.horizontal(|ui| {
                ui.label(RichText::new(label).color(c.muted));
                let cur = if is_start { e.start() } else { e.end() };
                let mut d = cur.date();
                let mut t = cur.time();
                let mut changed = ui.add(egui_extras::DatePickerButton::new(&mut d).id_salt(if is_start { "ev-start" } else { "ev-end" }).calendar_week(false)).changed();
                if !e.all_day() {
                    changed |= time_edit(ui, &mut t);
                }
                if changed {
                    let new = d.and_time(t);
                    if is_start {
                        // Moving the start keeps the length.
                        let len = e.end() - e.start();
                        e.set_span(new, new + len, e.all_day());
                    } else {
                        e.set_span(e.start(), new.max(e.start()), e.all_day());
                    }
                }
            });
        }

        style::section_title(ui, &c, "REPEAT");
        let start_date = e.start().date();
        let current = e.recurrence.as_ref().map(recur::describe).unwrap_or_else(|| "Does not repeat".into());
        egui::ComboBox::from_id_salt("ev-repeat").selected_text(current).width(260.0).show_ui(ui, |ui| {
            for (label, r) in presets(start_date) {
                if ui.selectable_label(false, label).clicked() {
                    let (until, count) = e.recurrence.as_ref().map(|x| (x.until.clone(), x.count)).unwrap_or_default();
                    e.recurrence = r.map(|r| Recurrence { until, count, ..r });
                }
            }
        });
        if let Some(r) = &mut e.recurrence {
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new("Every").color(c.muted));
                ui.add(egui::DragValue::new(&mut r.interval).range(1..=999));
                for (f, l) in [(Freq::Daily, "days"), (Freq::Weekly, "weeks"), (Freq::Monthly, "months"), (Freq::Yearly, "years")] {
                    if ui.selectable_label(r.freq == f && !r.workdays, l).clicked() {
                        r.freq = f;
                        r.workdays = false;
                        r.weekdays = if f == Freq::Weekly { vec![start_date.weekday().number_from_monday()] } else { vec![] };
                        r.month_day = matches!(f, Freq::Monthly | Freq::Yearly).then_some(start_date.day());
                        r.month = (f == Freq::Yearly).then_some(start_date.month());
                    }
                }
            });
            if r.freq == Freq::Weekly {
                ui.horizontal(|ui| {
                    for (i, l) in ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"].iter().enumerate() {
                        let d = i as u32 + 1;
                        let on = r.weekdays.contains(&d);
                        if ui.selectable_label(on, *l).clicked() {
                            if on && r.weekdays.len() > 1 {
                                r.weekdays.retain(|x| *x != d);
                            } else if !on {
                                r.weekdays.push(d);
                                r.weekdays.sort();
                            }
                        }
                    }
                });
            }
            ui.horizontal(|ui| {
                ui.label(RichText::new("Ends").color(c.muted));
                if ui.selectable_label(r.until.is_none() && r.count.is_none(), "Never").clicked() {
                    r.until = None;
                    r.count = None;
                }
                if ui.selectable_label(r.until.is_some(), "On date").clicked() && r.until.is_none() {
                    r.until = Some((start_date + Duration::days(90)).to_string());
                    r.count = None;
                }
                if ui.selectable_label(r.count.is_some(), "After").clicked() && r.count.is_none() {
                    r.count = Some(10);
                    r.until = None;
                }
                if let Some(u) = r.until_date() {
                    let mut d = u;
                    if ui.add(egui_extras::DatePickerButton::new(&mut d).id_salt("ev-until").calendar_week(false)).changed() {
                        r.until = Some(d.to_string());
                    }
                }
                if let Some(n) = &mut r.count {
                    ui.add(egui::DragValue::new(n).range(1..=999).suffix(" times"));
                }
            });
        }

        style::section_title(ui, &c, "CALENDAR");
        let groups = self.store.live_groups().into_iter().cloned().collect::<Vec<_>>();
        let current = self.store.group(e.group_id.as_deref()).map(|g| g.name.clone()).unwrap_or_else(|| "No calendar".into());
        egui::ComboBox::from_id_salt("ev-group").selected_text(current).width(220.0).show_ui(ui, |ui| {
            ui.selectable_value(&mut e.group_id, None, "No calendar");
            for g in &groups {
                ui.selectable_value(&mut e.group_id, Some(g.id.clone()), RichText::new(format!("⏺ {}", g.name)).color(c.named(&g.color)));
            }
        });
        ui.horizontal(|ui| {
            ui.label(RichText::new("Color").color(c.muted));
            if ui.selectable_label(e.color.is_none(), "Auto").clicked() {
                e.color = None;
            }
            for key in GROUP_COLORS {
                let (rect, resp) = ui.allocate_exact_size(Vec2::splat(22.0), egui::Sense::click());
                ui.painter().circle_filled(rect.center(), 9.0, c.named(key));
                if e.color.as_deref() == Some(key) {
                    ui.painter().circle_stroke(rect.center(), 11.0, egui::Stroke::new(2.0_f32, c.fg));
                }
                if resp.on_hover_text(key).clicked() {
                    e.color = Some(key.to_string());
                }
            }
        });

        style::section_title(ui, &c, "DETAILS");
        ui.add(egui::TextEdit::singleline(&mut e.location).hint_text("📍 Location").desired_width(f32::INFINITY));
        ui.add(egui::TextEdit::singleline(&mut e.url).hint_text("🔗 Link (video call, ticket…)").desired_width(f32::INFINITY));
        if !e.url.trim().is_empty() && ui.link("Open link").clicked() {
            let _ = open::that(e.url.trim());
        }
        ui.add(egui::TextEdit::multiline(&mut e.notes).hint_text("Notes").desired_rows(4).desired_width(f32::INFINITY));

        style::section_title(ui, &c, "REMINDERS");
        ui.horizontal_wrapped(|ui| {
            e.reminders.sort();
            let mut remove = None;
            for (i, m) in e.reminders.iter().enumerate() {
                let label = if *m == 0 { "At start  ✖".to_string() } else { format!("🔔 {} before  ✖", format_offset(*m)) };
                if style::chip(ui, label, c.fg, c.tonal).interact(egui::Sense::click()).clicked() {
                    remove = Some(i);
                }
            }
            if let Some(i) = remove {
                e.reminders.remove(i);
            }
        });
        ui.horizontal(|ui| {
            egui::ComboBox::from_id_salt("ev-rem").selected_text("+ Add reminder").width(150.0).show_ui(ui, |ui| {
                for m in [0, 5, 10, 15, 30, 60, 120, 1440, 10080] {
                    if !e.reminders.contains(&m) && ui.selectable_label(false, if m == 0 { "At start".into() } else { format!("{} before", format_offset(m)) }).clicked() {
                        e.reminders.push(m);
                    }
                }
            });
            ui.add(egui::DragValue::new(&mut ed.custom_minutes).range(0..=10080).suffix(" min"));
            if ui.button("Add").clicked() && !e.reminders.contains(&ed.custom_minutes) {
                e.reminders.push(ed.custom_minutes);
            }
        });
        ui.checkbox(&mut e.urgent, "⏰ Ring an alarm instead of a notification");

        if let Some(orig) = ed.original.clone() {
            ui.add_space(14.0);
            ui.separator();
            ui.horizontal(|ui| {
                if ui.button("Duplicate").clicked() {
                    let mut copy = e.clone();
                    copy.id = uuid_like();
                    copy.uid = None;
                    copy.exdates.clear();
                    actions.push(Action::SaveEventCopy(copy));
                }
                if ui.button(RichText::new("🗑 Delete").color(c.red)).clicked() {
                    if recurring {
                        ed.ask_scope = Some("delete");
                    } else {
                        actions.push(Action::DeleteEvent(EditScope::All));
                    }
                }
            });
            let fmt = |ms: i64| chrono::DateTime::from_timestamp_millis(ms).map(|d| d.with_timezone(&Local).format("%-d %b %Y %H:%M").to_string()).unwrap_or_default();
            ui.label(RichText::new(format!("Created {} · Updated {}", fmt(orig.created), fmt(orig.updated))).size(11.5).color(c.muted));
        }
    }
}

fn uuid_like() -> String {
    Event::new("", Local::now().date_naive(), None).id
}
