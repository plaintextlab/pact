//! Runs git sync on a background thread so the UI never blocks.

use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::time::Instant;

use pact_core::sync::{self, SyncConfig};

pub struct SyncWorker {
    tx: Sender<(PathBuf, SyncConfig)>,
    rx: Receiver<Result<String, String>>,
    pub busy: bool,
    pub last_ok: Option<Instant>,
    pub status: String,
    pub error: Option<String>,
}

impl SyncWorker {
    pub fn new() -> Self {
        let (tx, job_rx) = channel::<(PathBuf, SyncConfig)>();
        let (res_tx, rx) = channel();
        let device = std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).unwrap_or_else(|_| "desktop".into());
        std::thread::Builder::new()
            .name("pact-sync".into())
            .spawn(move || {
                for (root, cfg) in job_rx {
                    let r = sync::sync(&root, &cfg, &device).map(|r| r.to_string()).map_err(|e| format!("{e:#}"));
                    if res_tx.send(r).is_err() {
                        break;
                    }
                }
            })
            .expect("spawn sync thread");
        Self { tx, rx, busy: false, last_ok: None, status: "not synced yet".into(), error: None }
    }

    pub fn request(&mut self, root: PathBuf, cfg: SyncConfig) {
        if !self.busy && self.tx.send((root, cfg)).is_ok() {
            self.busy = true;
            self.status = "syncing…".into();
        }
    }

    /// The result of a sync that just finished, if any.
    pub fn poll(&mut self) -> Option<Result<(), String>> {
        let r = self.rx.try_recv().ok()?;
        self.busy = false;
        match r {
            Ok(msg) => {
                self.error = None;
                self.last_ok = Some(Instant::now());
                self.status = msg;
                Some(Ok(()))
            }
            Err(e) => {
                self.status = "failed".into();
                self.error = Some(e.clone());
                Some(Err(e))
            }
        }
    }
}
