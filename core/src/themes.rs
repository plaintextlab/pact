//! Color schemes, shared with Android via spec/themes.json.

use std::sync::LazyLock;

use serde::Deserialize;

#[derive(Clone, Debug, Deserialize)]
pub struct Palette {
    pub bg: String,
    pub bg1: String,
    pub bg2: String,
    pub fg: String,
    pub muted: String,
    pub primary: String,
    pub red: String,
    pub orange: String,
    pub yellow: String,
    pub green: String,
    pub aqua: String,
    pub blue: String,
    pub purple: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Theme {
    pub id: String,
    pub name: String,
    pub dark: Palette,
    pub light: Palette,
}

pub static THEMES: LazyLock<Vec<Theme>> = LazyLock::new(|| serde_json::from_str(include_str!("../../spec/themes.json")).expect("themes.json"));

pub fn theme(id: &str) -> &'static Theme {
    THEMES.iter().find(|t| t.id == id).unwrap_or(&THEMES[0])
}

impl Palette {
    /// Resolves a group color: a theme key (`blue`) or a `#RRGGBB` literal.
    pub fn named<'a>(&'a self, key: &'a str) -> &'a str {
        match key {
            "red" => &self.red,
            "orange" => &self.orange,
            "yellow" => &self.yellow,
            "green" => &self.green,
            "aqua" => &self.aqua,
            "blue" => &self.blue,
            "purple" => &self.purple,
            k if k.starts_with('#') => k,
            _ => &self.primary,
        }
    }
}

/// Parses `#RRGGBB` into RGB bytes.
pub fn hex_rgb(s: &str) -> [u8; 3] {
    let s = s.trim_start_matches('#');
    let p = |i: usize| s.get(i..i + 2).and_then(|h| u8::from_str_radix(h, 16).ok()).unwrap_or(128);
    [p(0), p(2), p(4)]
}
