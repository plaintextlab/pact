#![cfg_attr(all(not(debug_assertions), windows), windows_subsystem = "windows")]

mod app;
mod calendar;
mod detail;
mod list;
mod reminders;
mod settings_ui;
mod style;
mod sync_worker;
mod voice;

use eframe::egui;
use pact_core::settings::Settings;

fn app_icon() -> egui::IconData {
    let img = image::load_from_memory(include_bytes!("../assets/icon.png")).expect("icon.png").into_rgba8();
    let (width, height) = img.dimensions();
    egui::IconData { rgba: img.into_raw(), width, height }
}

fn main() -> eframe::Result {
    let dirs = directories::ProjectDirs::from("dev", "pact", "Pact").expect("no home directory");
    let settings_path = std::env::var_os("PACT_SETTINGS").map(Into::into).unwrap_or_else(|| dirs.config_dir().join("settings.json"));
    let default_data_dir = dirs.data_dir().join("data");
    let settings = Settings::load(&settings_path);

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title(&settings.app_name)
            .with_app_id("pact")
            .with_inner_size([1200.0, 780.0])
            .with_min_inner_size([760.0, 500.0])
            .with_icon(app_icon()),
        ..Default::default()
    };
    eframe::run_native(
        "pact",
        options,
        Box::new(move |cc| {
            egui_extras::install_image_loaders(&cc.egui_ctx);
            Ok(Box::new(app::PactApp::new(cc, settings, settings_path, default_data_dir)?))
        }),
    )
}
