//! Fills a data folder with demo tasks: `cargo run -p pact-core --example seed -- <dir>`.

use chrono::Local;
use pact_core::nlp::NlpConfig;
use pact_core::store::{QuickAddDefaults, Store};

fn main() -> anyhow::Result<()> {
    let dir = std::env::args().nth(1).expect("usage: seed <data dir>");
    let mut s = Store::open(&dir)?;
    let now = Local::now().naive_local();
    let cfg = NlpConfig::default();
    let add = |s: &mut Store, text: &str, parent: Option<&str>| -> anyhow::Result<String> {
        let d = QuickAddDefaults { parent_id: parent.map(String::from), ..Default::default() };
        Ok(s.quick_add(text, now, &cfg, d)?.expect("title").id)
    };
    let launch = add(&mut s, "Ship v1.0 release #Work p1 tomorrow at 4pm remind me 30 min before", None)?;
    add(&mut s, "Write changelog @docs today", Some(&launch))?;
    let qa = add(&mut s, "QA pass on Android", Some(&launch))?;
    add(&mut s, "Test widget on Pixel", Some(&qa))?;
    add(&mut s, "Team standup every weekday at 9:30 #Work", None)?;
    add(&mut s, "Dentist appointment friday 3pm !! alarm 1 hour before #Personal", None)?;
    add(&mut s, "Pay rent every 1st #Personal p2", None)?;
    add(&mut s, "Read chapter 4 of Rust book #Study this evening", None)?;
    add(&mut s, "Flashcards every day #Study @spaced", None)?;
    add(&mut s, "Call mom in 2 days", None)?;
    add(&mut s, "Buy groceries today p3 @errands", None)?;
    add(&mut s, "Renew passport next month p2", None)?;
    add(&mut s, "Plan weekend hike this weekend #Personal", None)?;
    add(&mut s, "Submit expense report in 3 business days #Work", None)?;
    let id = add(&mut s, "Water the plants", None)?;
    let mut t = s.task(&id).unwrap().clone();
    t.set_date(Some(now.date() - chrono::Duration::days(1)));
    s.save_task(t)?;
    s.attach_text(&launch, "Release notes draft", "- New calendar view\n- Git sync\n- Natural language dates")?;
    let mut t = s.task(&launch).unwrap().clone();
    t.pinned = true;
    t.notes = "Tag the release, build installers and publish.".into();
    s.save_task(t)?;
    println!("seeded {} tasks into {dir}", s.live_tasks().count());
    Ok(())
}
