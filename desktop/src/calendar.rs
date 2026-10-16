//! Month calendar with a task list for the picked day.

use std::collections::HashMap;

use chrono::{Datelike, Duration, NaiveDate};
use eframe::egui::{self, Align2, Color32, CornerRadius, FontId, RichText, Sense, Stroke, StrokeKind, Vec2};
use pact_core::model::Task;
use pact_core::recur;
use pact_core::store::sort_tasks;

use crate::app::{today, Action, PactApp};
use crate::list::{friendly_date, RowOpts};
use crate::style;

struct Entry {
    title: String,
    color: Color32,
    /// A future occurrence of a recurring task (not a stored task).
    projected: bool,
    completed: bool,
}

impl PactApp {
    fn week_start(&self) -> u32 {
        if self.settings.nlp.week_start == 7 { 7 } else { 1 }
    }

    fn entries(&self, from: NaiveDate, to: NaiveDate) -> HashMap<NaiveDate, Vec<Entry>> {
        let mut map: HashMap<NaiveDate, Vec<Entry>> = HashMap::new();
        let mut tasks: Vec<&Task> = self.store.live_tasks().filter(|t| t.date().is_some()).collect();
        sort_tasks(&mut tasks);
        for t in tasks {
            if t.completed && !self.settings.show_completed {
                continue;
            }
            let d = t.date().unwrap();
            let color = self.store.group(t.group_id.as_deref()).map(|g| self.colors.named(&g.color)).unwrap_or(self.colors.primary);
            if d >= from && d <= to {
                map.entry(d).or_default().push(Entry { title: t.title.clone(), color, projected: false, completed: t.completed });
            }
            if let (Some(r), false) = (&t.recurrence, t.completed) {
                let mut x = recur::next_after(r, d);
                for _ in 0..400 {
                    if x > to {
                        break;
                    }
                    if x >= from {
                        map.entry(x).or_default().push(Entry { title: t.title.clone(), color, projected: true, completed: false });
                    }
                    x = recur::next_after(r, x);
                }
            }
        }
        map
    }

    pub fn calendar_view(&mut self, ui: &mut egui::Ui, actions: &mut Vec<Action>) {
        let c = self.colors.clone();
        let t0 = today();
        ui.horizontal(|ui| {
            ui.heading(RichText::new(self.cal_month.format("%B %Y").to_string()).strong());
            ui.add_space(12.0);
            if ui.button("‹").clicked() {
                self.cal_month = recur::add_months(self.cal_month, -1, 1);
            }
            if style::tonal_button(ui, &c, "Today").clicked() {
                self.cal_month = t0.with_day(1).unwrap();
                self.cal_day = t0;
            }
            if ui.button("›").clicked() {
                self.cal_month = recur::add_months(self.cal_month, 1, 1);
            }
        });
        ui.add_space(8.0);

        let ws = self.week_start();
        let first = self.cal_month;
        let offset = (first.weekday().number_from_monday() + 7 - ws) % 7;
        let grid_start = first - Duration::days(offset as i64);
        let grid_end = grid_start + Duration::days(41);
        let entries = self.entries(grid_start, grid_end);

        let gap = 6.0;
        let cell_w = ((ui.available_width() - gap * 6.0) / 7.0).max(60.0);
        let cell_h = ((ui.available_height() * 0.62 - 30.0) / 6.0).clamp(64.0, 120.0);

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

                    if let Some(list) = entries.get(&d) {
                        let line_h = 16.0;
                        let max_lines = (((cell_h - 32.0) / line_h).floor() as usize).max(1);
                        for (j, e) in list.iter().take(max_lines).enumerate() {
                            let y = rect.top() + 30.0 + j as f32 * line_h;
                            let bar = egui::Rect::from_min_size(egui::pos2(rect.left() + 6.0, y), Vec2::new(3.0, line_h - 3.0));
                            p.rect_filled(bar, CornerRadius::same(2), if e.projected { e.color.gamma_multiply(0.45) } else { e.color });
                            let col = if e.projected || e.completed { c.muted } else { c.fg };
                            let text = if e.projected { format!("↻ {}", e.title) } else { e.title.clone() };
                            p.text(egui::pos2(rect.left() + 13.0, y), Align2::LEFT_TOP, text, FontId::proportional(12.0), col);
                        }
                        if list.len() > max_lines {
                            p.text(rect.right_top() + Vec2::new(-8.0, 8.0), Align2::RIGHT_TOP, format!("+{}", list.len() - max_lines), FontId::proportional(11.5), c.muted);
                        }
                    }
                    if resp.clicked() {
                        self.cal_day = d;
                        if !in_month {
                            self.cal_month = d.with_day(1).unwrap();
                        }
                    }
                    if resp.double_clicked() {
                        ui.memory_mut(|m| m.request_focus(egui::Id::new("quick-add")));
                    }
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
        egui::ScrollArea::vertical().id_salt("cal-day").auto_shrink([false, false]).show(ui, |ui| {
            let mut v: Vec<&Task> = self.store.live_tasks().filter(|t| t.date() == Some(day) && (self.settings.show_completed || !t.completed)).collect();
            sort_tasks(&mut v);
            let v: Vec<Task> = v.into_iter().cloned().collect();
            self.rows(ui, &v, RowOpts { depth: 0, tree: false, show_group: true, show_parent: true }, actions, "Nothing scheduled. Type above to add a task for this day.");
            if let Some(list) = self.entries(day, day).get(&day) {
                for e in list.iter().filter(|e| e.projected) {
                    ui.label(RichText::new(format!("↻ {} (repeats)", e.title)).color(c.muted));
                }
            }
        });
    }
}
