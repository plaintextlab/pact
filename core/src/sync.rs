//! Git sync through the system `git` binary, so the user's existing SSH keys and
//! credential helpers just work. Conflicting JSON objects are resolved per file:
//! the side with the larger `updated` wins.
//!
//! The repository may be shared with other apps: Pact only stages and commits its
//! own folder (`SyncConfig::folder`) and never resolves conflicts outside it.

use std::path::Path;
use std::process::{Command, Output};

use anyhow::{bail, Context, Result};
use serde_json::Value;

#[derive(Clone, Debug, Default)]
pub struct SyncConfig {
    pub remote: String,
    pub branch: String,
    /// Pact's folder inside the repository ("" = the whole repository).
    pub folder: String,
}

impl SyncConfig {
    fn pathspec(&self) -> String {
        let f = self.folder.trim().trim_matches('/');
        if f.is_empty() { ".".into() } else { f.to_string() }
    }

    fn owns(&self, path: &str) -> bool {
        let spec = self.pathspec();
        spec == "." || path == spec || path.starts_with(&format!("{spec}/"))
    }
}

#[derive(Clone, Debug, Default)]
pub struct SyncReport {
    pub committed: bool,
    pub pulled: bool,
    pub pushed: bool,
    pub conflicts_resolved: usize,
}

impl std::fmt::Display for SyncReport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mut parts = vec![];
        if self.committed {
            parts.push("committed".to_string());
        }
        if self.pulled {
            parts.push("pulled".to_string());
        }
        if self.pushed {
            parts.push("pushed".to_string());
        }
        if self.conflicts_resolved > 0 {
            parts.push(format!("{} conflict(s) resolved", self.conflicts_resolved));
        }
        if parts.is_empty() {
            write!(f, "up to date")
        } else {
            write!(f, "{}", parts.join(", "))
        }
    }
}

fn git_cmd(root: &Path) -> Command {
    let mut c = Command::new("git");
    c.current_dir(root).args(["-c", "core.quotepath=off", "-c", "core.autocrlf=false"]);
    c.env("GIT_TERMINAL_PROMPT", "0");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        c.creation_flags(CREATE_NO_WINDOW);
    }
    c
}

fn run(root: &Path, args: &[&str]) -> Result<Output> {
    git_cmd(root).args(args).output().context("could not run git — is it installed and on PATH?")
}

fn ok(root: &Path, args: &[&str]) -> Result<String> {
    let out = run(root, args)?;
    if !out.status.success() {
        bail!("git {} failed: {}", args.join(" "), String::from_utf8_lossy(&out.stderr).trim());
    }
    Ok(String::from_utf8_lossy(&out.stdout).to_string())
}

fn show_blob(root: &Path, spec: &str) -> Option<Vec<u8>> {
    let out = run(root, &["show", spec]).ok()?;
    out.status.success().then_some(out.stdout)
}

fn updated_of(bytes: &[u8]) -> i64 {
    serde_json::from_slice::<Value>(bytes).ok().and_then(|v| v.get("updated").and_then(Value::as_i64)).unwrap_or(0)
}

/// Makes sure `root` is a repository with the configured remote.
pub fn ensure_repo(root: &Path, cfg: &SyncConfig) -> Result<()> {
    if !root.join(".git").exists() {
        ok(root, &["init", "-q"])?;
        ok(root, &["symbolic-ref", "HEAD", &format!("refs/heads/{}", cfg.branch)])?;
    }
    if ok(root, &["config", "user.email"]).map(|s| s.trim().is_empty()).unwrap_or(true) {
        ok(root, &["config", "user.email", "pact@localhost"])?;
        ok(root, &["config", "user.name", "Pact"])?;
    }
    let remote = cfg.remote.trim();
    if !remote.is_empty() {
        let current = ok(root, &["remote", "get-url", "origin"]).unwrap_or_default();
        if current.trim().is_empty() {
            ok(root, &["remote", "add", "origin", remote])?;
        } else if current.trim() != remote {
            ok(root, &["remote", "set-url", "origin", remote])?;
        }
    }
    Ok(())
}

/// Commits Pact's folder only, leaving other apps' changes alone.
fn commit_folder(root: &Path, cfg: &SyncConfig, msg: &str) -> Result<bool> {
    let spec = cfg.pathspec();
    ok(root, &["add", "-A", "--", &spec])?;
    if ok(root, &["status", "--porcelain", "--", &spec])?.trim().is_empty() {
        return Ok(false);
    }
    ok(root, &["commit", "-q", "-m", msg, "--", &spec])?;
    Ok(true)
}

/// Resolves every unmerged path in Pact's folder by keeping the newer object.
/// Conflicts elsewhere belong to other apps, so the merge is aborted instead.
fn resolve_conflicts(root: &Path, cfg: &SyncConfig) -> Result<usize> {
    let files = ok(root, &["diff", "--name-only", "--diff-filter=U"])?;
    let paths: Vec<&str> = files.lines().map(str::trim).filter(|p| !p.is_empty()).collect();
    if let Some(other) = paths.iter().find(|p| !cfg.owns(p)) {
        ok(root, &["merge", "--abort"]).ok();
        bail!("merge conflict outside Pact's folder ({other}); resolve it with git, then sync again");
    }
    let mut n = 0;
    for path in paths {
        let ours = show_blob(root, &format!(":2:{path}"));
        let theirs = show_blob(root, &format!(":3:{path}"));
        let winner = match (ours, theirs) {
            (Some(o), Some(t)) => {
                if path.ends_with(".json") && updated_of(&t) > updated_of(&o) { t } else { o }
            }
            (Some(o), None) => o,
            (None, Some(t)) => t,
            (None, None) => {
                ok(root, &["rm", "-q", "--cached", path]).ok();
                continue;
            }
        };
        let abs = root.join(path);
        if let Some(dir) = abs.parent() {
            std::fs::create_dir_all(dir)?;
        }
        std::fs::write(&abs, winner)?;
        ok(root, &["add", path])?;
        n += 1;
    }
    Ok(n)
}

/// Commit local changes, merge the remote branch, push. Safe to call often.
pub fn sync(root: &Path, cfg: &SyncConfig, device: &str) -> Result<SyncReport> {
    ensure_repo(root, cfg)?;
    let mut rep = SyncReport::default();
    let stamp = chrono::Local::now().format("%Y-%m-%d %H:%M");
    rep.committed = commit_folder(root, cfg, &format!("pact: {device} {stamp}"))?;
    if cfg.remote.trim().is_empty() {
        return Ok(rep);
    }
    let branch = cfg.branch.as_str();
    ok(root, &["fetch", "-q", "origin"])?;
    let remote_ref = format!("origin/{branch}");
    let has_remote = run(root, &["rev-parse", "--verify", "-q", &remote_ref])?.status.success();
    let has_local = run(root, &["rev-parse", "--verify", "-q", "HEAD"])?.status.success();

    if has_remote && !has_local {
        ok(root, &["reset", "-q", "--hard", &remote_ref])?;
        rep.pulled = true;
        return Ok(rep);
    }
    if has_remote {
        let behind = ok(root, &["rev-list", "--count", &format!("HEAD..{remote_ref}")])?;
        if behind.trim() != "0" {
            let merge = run(root, &["merge", "--no-edit", "-q", "--allow-unrelated-histories", &remote_ref])?;
            if !merge.status.success() {
                let unmerged = ok(root, &["diff", "--name-only", "--diff-filter=U"])?;
                if unmerged.trim().is_empty() {
                    // Refused outright, e.g. uncommitted changes of another app in the way.
                    bail!("git merge failed: {}", String::from_utf8_lossy(&merge.stderr).trim());
                }
                rep.conflicts_resolved = resolve_conflicts(root, cfg)?;
                ok(root, &["commit", "-q", "--no-edit"])?;
            }
            rep.pulled = true;
        }
    }
    let ahead = if has_remote { ok(root, &["rev-list", "--count", &format!("{remote_ref}..HEAD")])? } else { "1".into() };
    if ahead.trim() != "0" {
        ok(root, &["push", "-q", "-u", "origin", &format!("HEAD:refs/heads/{branch}")])?;
        rep.pushed = true;
    }
    Ok(rep)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Task;
    use crate::store::{data_dir, Store};

    fn tmp(name: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("pact-sync-{name}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&p).unwrap();
        p
    }

    #[test]
    fn two_devices_converge_in_shared_repo() {
        if Command::new("git").arg("--version").output().is_err() {
            return;
        }
        let remote = tmp("remote");
        ok(&remote, &["init", "-q", "--bare"]).unwrap();
        let cfg = SyncConfig { remote: remote.to_string_lossy().replace('\\', "/"), branch: "main".into(), folder: "pact".into() };

        // Another app already keeps its data in the shared repo.
        let other = tmp("other");
        ensure_repo(&other, &cfg).unwrap();
        std::fs::create_dir_all(other.join("notes")).unwrap();
        std::fs::write(other.join("notes/a.txt"), "other app").unwrap();
        ok(&other, &["add", "-A"]).unwrap();
        ok(&other, &["commit", "-q", "-m", "other app"]).unwrap();
        ok(&other, &["push", "-q", "origin", "HEAD:refs/heads/main"]).unwrap();

        let a_repo = tmp("a");
        let mut a = Store::open(data_dir(&a_repo, "pact")).unwrap();
        let t = Task::new("shared");
        a.save_task(t.clone()).unwrap();
        // An uncommitted file of some other app must not be committed by Pact.
        std::fs::write(a_repo.join("scratch.txt"), "not pact").unwrap();
        sync(&a_repo, &cfg, "a").unwrap();
        assert!(a_repo.join("notes/a.txt").exists(), "other app's data is pulled");
        let tracked = ok(&a_repo, &["ls-files"]).unwrap();
        assert!(!tracked.contains("scratch.txt"));
        assert!(tracked.lines().all(|l| l.starts_with("pact/") || l.starts_with("notes/")));

        let b_repo = tmp("b");
        let mut b = Store::open(data_dir(&b_repo, "pact")).unwrap();
        sync(&b_repo, &cfg, "b").unwrap();
        b.reload();
        assert!(b.task(&t.id).is_some());

        // Both edit the same task; B edits last and should win.
        let mut ta = a.task(&t.id).unwrap().clone();
        ta.title = "from a".into();
        a.save_task(ta).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(5));
        let mut tb = b.task(&t.id).unwrap().clone();
        tb.title = "from b".into();
        b.save_task(tb).unwrap();

        sync(&a_repo, &cfg, "a").unwrap();
        let rep = sync(&b_repo, &cfg, "b").unwrap();
        assert_eq!(rep.conflicts_resolved, 1);
        sync(&a_repo, &cfg, "a").unwrap();
        a.reload();
        b.reload();
        assert_eq!(a.task(&t.id).unwrap().title, "from b");
        assert_eq!(b.task(&t.id).unwrap().title, "from b");
        for d in [remote, other, a_repo, b_repo] {
            let _ = std::fs::remove_dir_all(d);
        }
    }
}
