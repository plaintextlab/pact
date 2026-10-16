//! Material 3–style look for egui, colored from the active Pact theme.

use eframe::egui::{self, Color32, CornerRadius, FontId, Margin, Stroke, TextStyle, Vec2};
use pact_core::settings::Settings;
use pact_core::themes::{self, hex_rgb, Palette};

pub fn hex(s: &str) -> Color32 {
    let [r, g, b] = hex_rgb(s);
    Color32::from_rgb(r, g, b)
}

pub fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    let l = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round() as u8;
    Color32::from_rgb(l(a.r(), b.r()), l(a.g(), b.g()), l(a.b(), b.b()))
}

#[derive(Clone)]
pub struct Colors {
    pub palette: Palette,
    pub bg: Color32,
    pub surface: Color32,
    pub surface2: Color32,
    pub fg: Color32,
    pub muted: Color32,
    pub primary: Color32,
    pub on_primary: Color32,
    /// Tonal container (Material "secondary container").
    pub tonal: Color32,
    pub red: Color32,
    pub orange: Color32,
    pub green: Color32,
    pub blue: Color32,
    pub dark: bool,
}

impl Colors {
    pub fn from_settings(s: &Settings) -> Self {
        let theme = themes::theme(&s.theme);
        let p = if s.dark { theme.dark.clone() } else { theme.light.clone() };
        let bg = hex(&p.bg);
        let primary = hex(&p.primary);
        Self {
            bg,
            surface: hex(&p.bg1),
            surface2: hex(&p.bg2),
            fg: hex(&p.fg),
            muted: hex(&p.muted),
            primary,
            on_primary: bg,
            tonal: mix(bg, primary, if s.dark { 0.22 } else { 0.18 }),
            red: hex(&p.red),
            orange: hex(&p.orange),
            green: hex(&p.green),
            blue: hex(&p.blue),
            dark: s.dark,
            palette: p,
        }
    }

    /// Group color: theme key or `#RRGGBB`.
    pub fn named(&self, key: &str) -> Color32 {
        hex(self.palette.named(key))
    }

    pub fn priority(&self, p: u8) -> Color32 {
        match p {
            1 => self.red,
            2 => self.orange,
            3 => self.blue,
            _ => self.muted,
        }
    }
}

pub fn apply(ctx: &egui::Context, c: &Colors, scale: f32) {
    let mut v = if c.dark { egui::Visuals::dark() } else { egui::Visuals::light() };
    v.override_text_color = Some(c.fg);
    v.panel_fill = c.bg;
    v.window_fill = c.surface;
    v.extreme_bg_color = c.surface2;
    v.faint_bg_color = c.surface;
    v.code_bg_color = c.surface2;
    v.hyperlink_color = c.primary;
    v.warn_fg_color = c.orange;
    v.error_fg_color = c.red;
    v.selection.bg_fill = mix(c.surface2, c.primary, 0.45);
    v.selection.stroke = Stroke::new(1.0_f32, c.fg);
    v.window_corner_radius = CornerRadius::same(20);
    v.menu_corner_radius = CornerRadius::same(12);
    v.window_stroke = Stroke::new(1.0_f32, c.surface2);
    v.window_highlight_topmost = false;

    let r = CornerRadius::same(10);
    let w = &mut v.widgets;
    w.noninteractive.bg_fill = c.surface;
    w.noninteractive.weak_bg_fill = c.surface;
    w.noninteractive.bg_stroke = Stroke::new(1.0_f32, c.surface2);
    w.noninteractive.fg_stroke = Stroke::new(1.0_f32, c.fg);
    w.noninteractive.corner_radius = r;
    w.inactive.bg_fill = c.surface2;
    w.inactive.weak_bg_fill = c.surface2;
    w.inactive.bg_stroke = Stroke::NONE;
    w.inactive.fg_stroke = Stroke::new(1.0_f32, c.fg);
    w.inactive.corner_radius = r;
    w.hovered.bg_fill = mix(c.surface2, c.primary, 0.2);
    w.hovered.weak_bg_fill = mix(c.surface2, c.primary, 0.2);
    w.hovered.bg_stroke = Stroke::new(1.0_f32, mix(c.surface2, c.primary, 0.5));
    w.hovered.fg_stroke = Stroke::new(1.5_f32, c.fg);
    w.hovered.corner_radius = r;
    w.active.bg_fill = mix(c.surface2, c.primary, 0.35);
    w.active.weak_bg_fill = mix(c.surface2, c.primary, 0.35);
    w.active.bg_stroke = Stroke::new(1.0_f32, c.primary);
    w.active.fg_stroke = Stroke::new(2.0_f32, c.fg);
    w.active.corner_radius = r;
    w.open = w.active;
    ctx.set_visuals(v);

    ctx.style_mut(|s| {
        s.text_styles.insert(TextStyle::Heading, FontId::proportional(24.0));
        s.text_styles.insert(TextStyle::Body, FontId::proportional(15.0));
        s.text_styles.insert(TextStyle::Button, FontId::proportional(15.0));
        s.text_styles.insert(TextStyle::Small, FontId::proportional(12.5));
        s.text_styles.insert(TextStyle::Monospace, FontId::monospace(14.0));
        s.spacing.item_spacing = Vec2::new(8.0, 6.0);
        s.spacing.button_padding = Vec2::new(12.0, 6.0);
        s.spacing.interact_size.y = 28.0;
        s.spacing.window_margin = Margin::same(18);
        s.spacing.menu_margin = Margin::same(8);
        s.spacing.combo_width = 160.0;
    });
    ctx.set_zoom_factor(scale.clamp(0.6, 2.5));
}

/// Primary filled button (Material "filled button" / FAB).
pub fn filled_button(ui: &mut egui::Ui, c: &Colors, text: &str) -> egui::Response {
    ui.add(egui::Button::new(egui::RichText::new(text).color(c.on_primary).strong()).fill(c.primary).corner_radius(CornerRadius::same(20)).min_size(Vec2::new(0.0, 34.0)))
}

/// Tonal button.
pub fn tonal_button(ui: &mut egui::Ui, c: &Colors, text: &str) -> egui::Response {
    ui.add(egui::Button::new(egui::RichText::new(text).color(c.fg)).fill(c.tonal).corner_radius(CornerRadius::same(20)).min_size(Vec2::new(0.0, 30.0)))
}

/// Small rounded chip; returns the response so callers can make it clickable.
pub fn chip(ui: &mut egui::Ui, text: impl Into<String>, fg: Color32, bg: Color32) -> egui::Response {
    egui::Frame::new()
        .fill(bg)
        .corner_radius(CornerRadius::same(8))
        .inner_margin(Margin::symmetric(7, 2))
        .show(ui, |ui| ui.label(egui::RichText::new(text.into()).color(fg).size(12.5)))
        .response
}

pub fn section_title(ui: &mut egui::Ui, c: &Colors, text: &str) {
    ui.add_space(6.0);
    ui.label(egui::RichText::new(text).color(c.primary).size(13.0).strong());
}
