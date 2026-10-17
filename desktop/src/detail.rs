//! Right-hand task editor.

use chrono::{Local, NaiveTime, Timelike};
use eframe::egui::{self, CornerRadius, Margin, RichText};
use pact_core::model::{AttachmentKind, Task};
use pact_core::nlp;
use pact_core::recur;
use pact_core::settings::format_offset;
use pact_core::store::QuickAddDefaults;

use crate::app::{today, Action, NoteEdit, PactApp};
use crate::list::{friendly_due, RowOpts};
use crate::style;

impl PactApp {
    pub fn detail_panel(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let Some(id) = self.selected.clone() else { return };
        let Some(orig) = self.store.task(&id).cloned() else {
            self.selected = None;
            return;
        };
        let c = self.colors.clone();
        let mut t = orig.clone();

        egui::SidePanel::right("detail")
            .resizable(true)
            .default_width(400.0)
            .min_width(320.0)
            .frame(egui::Frame::new().fill(c.surface).inner_margin(Margin::same(18)))
            .show(ctx, |ui| {
                egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        let label = if t.completed { "↺ Reopen" } else { "✔ Complete" };
                        if style::tonal_button(ui, &c, label).clicked() {
                            actions.push(Action::Complete(t.id.clone(), !t.completed));
                        }
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.add(egui::Button::new(RichText::new("✖").size(16.0)).frame(false)).on_hover_text("Close (Esc)").clicked() {
                                actions.push(Action::Select(None));
                            }
                        });
                    });
                    if let Some(parent) = t.parent_id.as_deref().and_then(|p| self.store.task(p)) {
                        if ui.link(RichText::new(format!("⬅ {}", parent.title)).size(13.0)).clicked() {
                            actions.push(Action::Select(Some(parent.id.clone())));
                        }
                    }
                    ui.add_space(4.0);
                    ui.add(egui::TextEdit::multiline(&mut t.title).font(egui::FontId::proportional(21.0)).desired_rows(1).frame(false).desired_width(f32::INFINITY));
                    ui.add(egui::TextEdit::multiline(&mut t.notes).hint_text("Description").desired_rows(3).desired_width(f32::INFINITY));

                    self.due_section(ui, &mut t);
                    self.deadline_section(ui, &mut t);
                    self.organize_section(ui, &mut t);
                    self.reminder_section(ui, &mut t);
                    self.subtask_section(ui, &t, actions);
                    self.attachment_section(ui, &t, actions);

                    ui.add_space(16.0);
                    ui.separator();
                    let fmt = |ms: i64| chrono::DateTime::from_timestamp_millis(ms).map(|d| d.with_timezone(&Local).format("%-d %b %Y %H:%M").to_string()).unwrap_or_default();
                    ui.label(RichText::new(format!("Created {} · Updated {}", fmt(orig.created), fmt(orig.updated))).size(11.5).color(c.muted));
                    if ui.button(RichText::new("🗑 Delete task").color(c.red)).clicked() {
                        actions.push(Action::Delete(t.id.clone()));
                    }
                });
            });

        if t != orig {
            actions.push(Action::Save(t));
        }
    }

    fn deadline_section(&mut self, ui: &mut egui::Ui, t: &mut Task) {
        let c = self.colors.clone();
        style::section_title(ui, &c, "DEADLINE");
        ui.horizontal_wrapped(|ui| {
            match t.deadline() {
                Some(mut d) => {
                    let mut time = t.deadline_clock();
                    let mut changed = ui.add(egui_extras::DatePickerButton::new(&mut d).id_salt("deadline").calendar_week(false)).changed();
                    let mut has_time = time.is_some();
                    if ui.checkbox(&mut has_time, "at").changed() {
                        time = has_time.then(|| NaiveTime::from_hms_opt(17, 0, 0).unwrap());
                        changed = true;
                    }
                    if let Some(tm) = &mut time {
                        changed |= crate::events::time_edit(ui, tm);
                    }
                    if changed {
                        t.set_deadline(Some(d), time);
                    }
                    ui.label(RichText::new(crate::list::friendly_deadline(t, today())).color(c.muted));
                    if ui.button("Clear").clicked() {
                        t.set_deadline(None, None);
                    }
                }
                None => {
                    for (label, days) in [("In a week", 7), ("In 2 weeks", 14), ("In a month", 30)] {
                        if style::tonal_button(ui, &c, label).clicked() {
                            t.set_deadline(Some(today() + chrono::Duration::days(days)), None);
                        }
                    }
                    ui.label(RichText::new("or type “deadline dec 15”").color(c.muted).size(12.0));
                }
            }
        });
        if t.deadline().is_some() {
            let s = &self.settings;
            let every = if s.deadline_final_hours == 1 { "hour".to_string() } else { format!("{} hours", s.deadline_final_hours) };
            let text = if s.deadline_reminders {
                format!("Reminders: weekly from 4 weeks before, daily in the last week, every {every} on the last day ({}:00–{}:00), then daily for 3 days if missed.", s.nlp.morning_hour, s.nlp.night_hour)
            } else {
                "Deadline reminders are off in Settings.".to_string()
            };
            ui.label(RichText::new(text).size(12.0).color(c.muted));
        }
    }

    fn due_section(&mut self, ui: &mut egui::Ui, t: &mut Task) {
        let c = self.colors.clone();
        style::section_title(ui, &c, "DUE");
        let t0 = today();
        ui.horizontal_wrapped(|ui| {
            let mut date = t.date().unwrap_or(t0);
            let had = t.date().is_some();
            if had {
                if ui.add(egui_extras::DatePickerButton::new(&mut date).id_salt("due-date").calendar_week(false)).changed() {
                    t.set_date(Some(date));
                }
            }
            for (label, d) in [("Today", t0), ("Tomorrow", t0.succ_opt().unwrap())] {
                if style::tonal_button(ui, &c, label).clicked() {
                    let tm = t.due_time.clone();
                    t.set_date(Some(d));
                    t.due_time = tm;
                }
            }
            if !had && style::tonal_button(ui, &c, "Pick date").clicked() {
                t.set_date(Some(t0));
            }
            if had && ui.button("Clear").clicked() {
                t.set_date(None);
                t.recurrence = None;
            }
        });

        if t.date().is_some() {
            ui.horizontal(|ui| {
                let mut has_time = t.due_time.is_some();
                if ui.checkbox(&mut has_time, "At time").changed() {
                    t.set_time(has_time.then(|| NaiveTime::from_hms_opt(9, 0, 0).unwrap()));
                }
                if let Some(tm) = t.time() {
                    let (mut h, mut m) = (tm.hour(), tm.minute());
                    let mut changed = false;
                    if crate::list::clock_24h() {
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
                        t.set_time(NaiveTime::from_hms_opt(h, m, 0));
                    }
                }
            });
        }
        ui.horizontal(|ui| {
            ui.label(RichText::new("🔁").color(c.muted));
            match &t.recurrence {
                Some(r) => {
                    ui.label(recur::describe(r));
                    if ui.small_button("✖").on_hover_text("Stop repeating").clicked() {
                        t.recurrence = None;
                    }
                }
                None => {
                    ui.label(RichText::new("Does not repeat").color(c.muted));
                }
            }
        });

        // Natural-language reschedule: "next fri 3pm", "every monday", "in 3 business days".
        let r = ui.add(egui::TextEdit::singleline(&mut self.inputs.reschedule).hint_text("Type a date: next fri 3pm, every monday…").desired_width(f32::INFINITY));
        if !self.inputs.reschedule.trim().is_empty() {
            let p = nlp::parse(&self.inputs.reschedule, Local::now().naive_local(), &self.settings.nlp);
            ui.horizontal_wrapped(|ui| crate::list::preview_chips(ui, &c, &nlp::Parsed { title: String::new(), ..p.clone() }, None));
            if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                if p.date.is_some() {
                    t.set_date(p.date);
                    t.set_time(p.time);
                }
                if p.recurrence.is_some() {
                    t.recurrence = p.recurrence;
                }
                if let Some(pr) = p.priority {
                    t.priority = pr;
                }
                for m in p.reminders {
                    if !t.reminders.contains(&m) {
                        t.reminders.push(m);
                    }
                }
                t.urgent |= p.urgent;
                self.inputs.reschedule.clear();
            }
        }
    }

    fn organize_section(&mut self, ui: &mut egui::Ui, t: &mut Task) {
        let c = self.colors.clone();
        style::section_title(ui, &c, "PRIORITY & GROUP");
        ui.horizontal(|ui| {
            for p in 1..=4u8 {
                let col = c.priority(p);
                let text = RichText::new(if p == 4 { "P4 none".to_string() } else { format!("⚑ P{p}") }).color(col);
                if ui.add(egui::Button::new(text).selected(t.priority == p).corner_radius(CornerRadius::same(16))).clicked() {
                    t.priority = p;
                }
            }
        });
        let groups = self.store.live_groups().into_iter().cloned().collect::<Vec<_>>();
        let current = self.store.group(t.group_id.as_deref()).map(|g| g.name.clone()).unwrap_or_else(|| "📥 Inbox".into());
        egui::ComboBox::from_id_salt("group").selected_text(current).width(220.0).show_ui(ui, |ui| {
            ui.selectable_value(&mut t.group_id, None, "📥 Inbox");
            for g in &groups {
                ui.selectable_value(&mut t.group_id, Some(g.id.clone()), RichText::new(format!("⏺ {}", g.name)).color(c.named(&g.color)));
            }
        });

        ui.horizontal_wrapped(|ui| {
            let mut remove = None;
            for (i, l) in t.labels.iter().enumerate() {
                if style::chip(ui, format!("@{l}  ✖"), c.blue, c.tonal).interact(egui::Sense::click()).clicked() {
                    remove = Some(i);
                }
            }
            if let Some(i) = remove {
                t.labels.remove(i);
            }
            let r = ui.add(egui::TextEdit::singleline(&mut self.inputs.label).hint_text("+ label").desired_width(90.0));
            if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                let l = self.inputs.label.trim().trim_start_matches('@').to_string();
                if !l.is_empty() && !t.labels.contains(&l) {
                    t.labels.push(l);
                }
                self.inputs.label.clear();
            }
        });
    }

    fn reminder_section(&mut self, ui: &mut egui::Ui, t: &mut Task) {
        let c = self.colors.clone();
        style::section_title(ui, &c, "REMINDERS & ALARM");
        ui.checkbox(&mut t.urgent, "⏰ Urgent — ring an alarm when due and at each reminder");
        let pin_before = t.pinned;
        ui.checkbox(&mut t.pinned, "📌 Pin as a persistent notification");
        if t.pinned && !pin_before && self.settings.notifications {
            crate::reminders::notify(&self.settings.app_name, &format!("📌 {}", t.title), &friendly_due(t, today()), true);
        }
        if t.date().is_none() && !t.reminders.is_empty() {
            ui.label(RichText::new("Reminders need a due date.").color(c.orange).size(12.5));
        } else if t.due_time.is_none() && !t.reminders.is_empty() {
            ui.label(RichText::new(format!("All-day task: reminders count back from {}:00.", self.settings.nlp.morning_hour)).color(c.muted).size(12.0));
        }
        ui.horizontal_wrapped(|ui| {
            t.reminders.sort();
            let mut remove = None;
            for (i, m) in t.reminders.iter().enumerate() {
                if style::chip(ui, format!("🔔 {} before  ✖", format_offset(*m)), c.fg, c.tonal).interact(egui::Sense::click()).clicked() {
                    remove = Some(i);
                }
            }
            if let Some(i) = remove {
                t.reminders.remove(i);
            }
        });
        ui.horizontal(|ui| {
            egui::ComboBox::from_id_salt("add-reminder").selected_text("+ Add reminder").width(150.0).show_ui(ui, |ui| {
                for m in self.settings.reminder_presets.clone() {
                    if !t.reminders.contains(&m) && ui.selectable_label(false, format!("{} before", format_offset(m))).clicked() {
                        t.reminders.push(m);
                    }
                }
            });
            ui.add(egui::DragValue::new(&mut self.inputs.custom_reminder).range(0..=10080).suffix(" min"));
            if ui.button("Add").clicked() && !t.reminders.contains(&self.inputs.custom_reminder) {
                t.reminders.push(self.inputs.custom_reminder);
            }
        });
    }

    fn subtask_section(&mut self, ui: &mut egui::Ui, t: &Task, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let children: Vec<Task> = self.store.children(Some(&t.id)).into_iter().cloned().collect();
        let done = children.iter().filter(|x| x.completed).count();
        style::section_title(ui, &c, &if children.is_empty() { "SUBTASKS".to_string() } else { format!("SUBTASKS · {done}/{}", children.len()) });
        for ch in &children {
            self.task_row(ui, ch, RowOpts { depth: 0, tree: true, show_group: false, show_parent: false }, actions);
        }
        let r = ui.add(egui::TextEdit::singleline(&mut self.inputs.subtask).hint_text("+ Add subtask (dates work here too)").desired_width(f32::INFINITY));
        if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) && !self.inputs.subtask.trim().is_empty() {
            let defaults = QuickAddDefaults { parent_id: Some(t.id.clone()), group_id: t.group_id.clone(), date: None, reminders: self.settings.default_reminders.clone() };
            actions.push(Action::QuickAdd(std::mem::take(&mut self.inputs.subtask), defaults));
            r.request_focus();
        }
    }

    fn attachment_section(&mut self, ui: &mut egui::Ui, t: &Task, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        style::section_title(ui, &c, "ATTACHMENTS");
        for a in &t.attachments {
            egui::Frame::new().fill(c.bg).corner_radius(CornerRadius::same(12)).inner_margin(Margin::same(8)).show(ui, |ui| {
                ui.set_width(ui.available_width());
                let icon = match a.kind {
                    AttachmentKind::Image => "🖼",
                    AttachmentKind::Audio => "🎤",
                    AttachmentKind::Text => "📝",
                    AttachmentKind::File => "📄",
                };
                let path = self.store.attachment_path(a);
                ui.horizontal(|ui| {
                    ui.label(icon);
                    let name = ui.add(egui::Label::new(RichText::new(&a.name).strong()).truncate().sense(egui::Sense::click()));
                    if let Some(p) = &path {
                        if name.on_hover_text("Open").clicked() {
                            actions.push(Action::Open(p.clone()));
                        }
                    }
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.small_button("✖").on_hover_text("Remove").clicked() {
                            actions.push(Action::RemoveAttachment(t.id.clone(), a.id.clone()));
                        }
                        if let Some(p) = &path {
                            let label = if a.kind == AttachmentKind::Audio { "▶ Play" } else { "Open" };
                            if ui.small_button(label).clicked() {
                                actions.push(Action::Open(p.clone()));
                            }
                        }
                    });
                });
                match (&a.kind, &path) {
                    (AttachmentKind::Image, Some(p)) => {
                        let uri = format!("file://{}", p.to_string_lossy().replace('\\', "/"));
                        ui.add(egui::Image::new(uri).max_height(180.0).max_width(ui.available_width()).corner_radius(CornerRadius::same(8)));
                    }
                    (AttachmentKind::Text, _) => {
                        ui.label(a.text.clone().unwrap_or_default());
                    }
                    _ => {}
                }
            });
            ui.add_space(4.0);
        }
        ui.horizontal_wrapped(|ui| {
            if style::tonal_button(ui, &c, "📄 File").clicked() {
                actions.push(Action::PickAttachment(t.id.clone(), None));
            }
            if style::tonal_button(ui, &c, "🖼 Image").clicked() {
                actions.push(Action::PickAttachment(t.id.clone(), Some(AttachmentKind::Image)));
            }
            if style::tonal_button(ui, &c, "📝 Text").clicked() {
                self.note_edit = Some(NoteEdit { task_id: t.id.clone(), name: String::new(), text: String::new() });
            }
            match &self.recorder {
                Some((rec_task, rec)) if rec_task == &t.id => {
                    let secs = rec.started.elapsed().as_secs();
                    let b = egui::Button::new(RichText::new(format!("⏹ Stop  {}:{:02}", secs / 60, secs % 60)).color(c.on_primary)).fill(c.red).corner_radius(CornerRadius::same(20));
                    if ui.add(b).clicked() {
                        actions.push(Action::StopRecording);
                    }
                }
                Some(_) => {
                    ui.add_enabled(false, egui::Button::new("🎤 Recording elsewhere"));
                }
                None => {
                    if style::tonal_button(ui, &c, "🎤 Voice").clicked() {
                        actions.push(Action::StartRecording(t.id.clone()));
                    }
                }
            }
        });
        ui.add_space(2.0);
        ui.label(RichText::new("Files are copied into the synced data folder.").size(11.5).color(c.muted));
    }
}
