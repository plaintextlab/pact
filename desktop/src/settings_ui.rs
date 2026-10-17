use eframe::egui::{self, RichText, Vec2};
use pact_core::themes::THEMES;

use crate::app::{join_nums, parse_nums, Action, PactApp};
use crate::style::{self, hex};

const WEEKDAYS: [&str; 7] = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

impl PactApp {
    pub fn settings_window(&mut self, ctx: &egui::Context, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let mut open = true;
        let mut s = self.settings.clone();
        egui::Window::new("Settings").open(&mut open).default_width(560.0).default_height(640.0).collapsible(false).show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| {
                style::section_title(ui, &c, "APPEARANCE");
                egui::Grid::new("appearance").num_columns(2).spacing([16.0, 8.0]).show(ui, |ui| {
                    ui.label("App name");
                    ui.add(egui::TextEdit::singleline(&mut s.app_name).hint_text("Pact, ToDo, Tasks…"));
                    ui.end_row();
                    ui.label("Color scheme");
                    let current = THEMES.iter().find(|t| t.id == s.theme).map(|t| t.name.clone()).unwrap_or_default();
                    egui::ComboBox::from_id_salt("theme").selected_text(current).show_ui(ui, |ui| {
                        for t in THEMES.iter() {
                            ui.horizontal(|ui| {
                                let pal = if s.dark { &t.dark } else { &t.light };
                                for col in [&pal.bg, &pal.primary, &pal.red, &pal.green, &pal.blue] {
                                    let (r, _) = ui.allocate_exact_size(Vec2::splat(12.0), egui::Sense::hover());
                                    ui.painter().circle_filled(r.center(), 6.0, hex(col));
                                }
                                ui.selectable_value(&mut s.theme, t.id.clone(), &t.name);
                            });
                        }
                    });
                    ui.end_row();
                    ui.label("Mode");
                    ui.horizontal(|ui| {
                        ui.selectable_value(&mut s.dark, true, "🌙 Dark");
                        ui.selectable_value(&mut s.dark, false, "☀ Light");
                    });
                    ui.end_row();
                    ui.label("Clock");
                    ui.horizontal(|ui| {
                        ui.selectable_value(&mut s.clock_24h, false, "12-hour (2:30 PM)");
                        ui.selectable_value(&mut s.clock_24h, true, "24-hour (14:30)");
                    });
                    ui.end_row();
                    ui.label("Zoom");
                    ui.add(egui::Slider::new(&mut s.ui_scale, 0.75..=1.75).step_by(0.05));
                    ui.end_row();
                });

                style::section_title(ui, &c, "DATES & NATURAL LANGUAGE");
                egui::Grid::new("dates").num_columns(2).spacing([16.0, 8.0]).show(ui, |ui| {
                    ui.label("“next week” means");
                    egui::ComboBox::from_id_salt("nwd").selected_text(WEEKDAYS[(s.nlp.next_week_day.clamp(1, 7) - 1) as usize]).show_ui(ui, |ui| {
                        for (i, d) in WEEKDAYS.iter().enumerate() {
                            ui.selectable_value(&mut s.nlp.next_week_day, i as u32 + 1, *d);
                        }
                    });
                    ui.end_row();
                    ui.label("“next month / year” means");
                    ui.horizontal(|ui| {
                        ui.selectable_value(&mut s.nlp.next_period_same_day, false, "the 1st");
                        ui.selectable_value(&mut s.nlp.next_period_same_day, true, "same day");
                    });
                    ui.end_row();
                    ui.label("Numeric dates");
                    ui.horizontal(|ui| {
                        ui.selectable_value(&mut s.nlp.month_first, false, "day/month");
                        ui.selectable_value(&mut s.nlp.month_first, true, "month/day");
                    });
                    ui.end_row();
                    ui.label("Week starts on");
                    ui.horizontal(|ui| {
                        ui.selectable_value(&mut s.nlp.week_start, 1, "Monday");
                        ui.selectable_value(&mut s.nlp.week_start, 7, "Sunday");
                    });
                    ui.end_row();
                    for (label, v) in [("Morning", &mut s.nlp.morning_hour), ("Afternoon", &mut s.nlp.afternoon_hour), ("Evening", &mut s.nlp.evening_hour), ("Night / tonight", &mut s.nlp.night_hour), ("End of day (eod)", &mut s.nlp.end_of_day_hour)] {
                        ui.label(label);
                        ui.add(egui::DragValue::new(v).range(0..=23).suffix(":00"));
                        ui.end_row();
                    }
                });

                style::section_title(ui, &c, "CALENDAR");
                egui::Grid::new("calendar").num_columns(2).spacing([16.0, 8.0]).show(ui, |ui| {
                    ui.label("Show tasks in the calendar");
                    ui.checkbox(&mut s.calendar_show_tasks, "");
                    ui.end_row();
                    ui.label("New events last");
                    ui.horizontal(|ui| {
                        for (m, l) in [(30, "30 min"), (60, "1 h"), (90, "1.5 h"), (120, "2 h")] {
                            ui.selectable_value(&mut s.event_minutes, m, l);
                        }
                    });
                    ui.end_row();
                    ui.label("Default calendar");
                    let current = s.default_calendar.as_deref().and_then(|id| self.store.group(Some(id))).map(|g| g.name.clone()).unwrap_or_else(|| "None".into());
                    egui::ComboBox::from_id_salt("default-cal").selected_text(current).show_ui(ui, |ui| {
                        ui.selectable_value(&mut s.default_calendar, None, "None");
                        for g in self.store.live_groups() {
                            ui.selectable_value(&mut s.default_calendar, Some(g.id.clone()), &g.name);
                        }
                    });
                    ui.end_row();
                    ui.label("Reminders for new events (min)");
                    if ui.add(egui::TextEdit::singleline(&mut self.event_reminders_text).hint_text("10, 60")).changed() {
                        s.event_reminders = parse_nums(&self.event_reminders_text);
                    }
                    ui.end_row();
                    ui.label(".ics files");
                    ui.horizontal(|ui| {
                        if ui.button("Import…").clicked() {
                            actions.push(Action::ImportIcs);
                        }
                        if ui.button("Export…").clicked() {
                            actions.push(Action::ExportIcs);
                        }
                    });
                    ui.end_row();
                });

                style::section_title(ui, &c, "REMINDERS");
                egui::Grid::new("reminders").num_columns(2).spacing([16.0, 8.0]).show(ui, |ui| {
                    ui.label("Desktop notifications");
                    ui.checkbox(&mut s.notifications, "");
                    ui.end_row();
                    ui.label("Reminder choices (min)");
                    if ui.add(egui::TextEdit::singleline(&mut self.presets_text).hint_text("5, 10, 15, 30, 60")).changed() {
                        s.reminder_presets = parse_nums(&self.presets_text);
                    }
                    ui.end_row();
                    ui.label("Default for timed tasks (min)");
                    if ui.add(egui::TextEdit::singleline(&mut self.default_reminders_text).hint_text("e.g. 15 — empty for none")).changed() {
                        s.default_reminders = parse_nums(&self.default_reminders_text);
                    }
                    ui.end_row();
                    ui.label("Alarm rings for");
                    ui.add(egui::DragValue::new(&mut s.alarm_ring_seconds).range(5..=600).suffix(" s"));
                    ui.end_row();
                    ui.label("Snooze");
                    ui.add(egui::DragValue::new(&mut s.snooze_minutes).range(1..=120).suffix(" min"));
                    ui.end_row();
                });

                style::section_title(ui, &c, "SYNC (GIT)");
                ui.label(RichText::new("Your tasks live in a folder of a git repository, which can be shared with other apps (e.g. an \"appsync\" repo). Pact only commits its own folder. Add a remote (GitHub, GitLab, Gitea, any SSH/HTTPS git server) to sync with your phone and other computers. Authentication uses your normal git setup: SSH keys or a credential helper.").size(12.5).color(c.muted));
                egui::Grid::new("sync").num_columns(2).spacing([16.0, 8.0]).show(ui, |ui| {
                    ui.label("Repository folder");
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(self.repo_root().display().to_string()).monospace().size(12.0));
                        if ui.small_button("Change…").clicked() {
                            if let Some(dir) = rfd::FileDialog::new().pick_folder() {
                                s.data_dir = Some(dir);
                            }
                        }
                        if ui.small_button("Open").clicked() {
                            actions.push(Action::Open(self.repo_root()));
                        }
                    });
                    ui.end_row();
                    ui.label("Pact folder in repo");
                    ui.horizontal(|ui| {
                        ui.add(egui::TextEdit::singleline(&mut self.folder_text).hint_text("pact (empty = repo root)").desired_width(160.0));
                        let clean = self.folder_text.trim().trim_matches('/').to_string();
                        if clean != s.git_folder && ui.button("Apply").clicked() {
                            s.git_folder = clean;
                        }
                    });
                    ui.end_row();
                    ui.label("Remote URL");
                    ui.add(egui::TextEdit::singleline(&mut s.git_remote).hint_text("git@github.com:you/pact-data.git").desired_width(320.0));
                    ui.end_row();
                    ui.label("Branch");
                    ui.add(egui::TextEdit::singleline(&mut s.git_branch).desired_width(120.0));
                    ui.end_row();
                    ui.label("Sync every");
                    ui.add(egui::DragValue::new(&mut s.auto_sync_minutes).range(0..=1440).suffix(" min (0 = off)"));
                    ui.end_row();
                    ui.label("Sync after edits");
                    ui.checkbox(&mut s.sync_on_change, "");
                    ui.end_row();
                });
                ui.horizontal(|ui| {
                    if style::filled_button(ui, &c, "Sync now").clicked() {
                        actions.push(Action::SyncNow);
                    }
                    ui.label(RichText::new(&self.sync.status).color(c.muted));
                });
                if let Some(e) = &self.sync.error {
                    ui.label(RichText::new(e).color(c.red).size(12.0));
                }

                style::section_title(ui, &c, "QUICK ADD CHEAT SHEET");
                for (ex, what) in [
                    ("tomorrow 10am · tonight · this evening", "dates & times of day"),
                    ("next week · next month · next year", "configurable above"),
                    ("in 2 days · in 3 hours · in 5 business days", "relative"),
                    ("fri · next tue · 15 march · 20/11 · the 5th", "absolute"),
                    ("every day · every weekday · every mon, wed", "repeating"),
                    ("every 2 weeks · every 15th · every last day of the month", "repeating"),
                    ("#Work  @label  p1", "group, label, priority"),
                    ("remind me 15 min before · !30m", "reminders"),
                    ("!! · !urgent · alarm 10 min before", "urgent alarm"),
                ] {
                    ui.horizontal_wrapped(|ui| {
                        ui.label(RichText::new(ex).monospace().size(12.5).color(c.primary));
                        ui.label(RichText::new(what).size(12.5).color(c.muted));
                    });
                }
            });
        });
        if s != self.settings {
            if s.reminder_presets != self.settings.reminder_presets && self.presets_text.trim().is_empty() {
                self.presets_text = join_nums(&s.reminder_presets);
            }
            self.settings = s;
            self.save_settings();
        }
        self.show_settings = open;
    }
}
