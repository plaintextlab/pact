package dev.pact.app.sync

import dev.pact.app.data.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Commit, merge the remote branch, push — same algorithm as core/src/sync.rs.
 * Conflicting objects are resolved per file: the side with the larger `updated` wins.
 * The repository may be shared with other apps: only Pact's folder is committed,
 * and conflicts outside it abort the merge.
 * Android supports HTTPS remotes with a username + personal access token.
 */
object GitSync {
    fun sync(root: File, s: Settings, device: String = android.os.Build.MODEL ?: "android"): String {
        val branch = s.gitBranch.ifBlank { "main" }
        val folder = s.gitFolder.trim().trim('/')
        val spec = folder.ifEmpty { "." }
        fun owns(path: String) = folder.isEmpty() || path == folder || path.startsWith("$folder/")
        val git = if (File(root, ".git").exists()) Git.open(root) else Git.init().setDirectory(root).setInitialBranch(branch).call()
        git.use {
            val repo = git.repository
            val cfg = repo.config
            if (cfg.getString("user", null, "email").isNullOrBlank()) {
                cfg.setString("user", null, "name", "Pact")
                cfg.setString("user", null, "email", "pact@android")
            }
            val remote = s.gitRemote.trim()
            if (remote.isNotEmpty()) {
                cfg.setString("remote", "origin", "url", remote)
                cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            }
            cfg.save()

            val parts = mutableListOf<String>()
            git.add().addFilepattern(spec).call()
            git.add().addFilepattern(spec).setUpdate(true).call()
            // Only Pact's folder was staged, so the commit can't pick up other apps' files.
            val st = git.status().let { if (folder.isEmpty()) it else it.addPath(folder) }.call()
            if (st.added.isNotEmpty() || st.changed.isNotEmpty() || st.removed.isNotEmpty()) {
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                git.commit().setMessage("pact: $device $stamp").call()
                parts += "committed"
            }
            if (remote.isEmpty()) return parts.joinToString(", ").ifEmpty { "up to date" }

            val creds = UsernamePasswordCredentialsProvider(s.gitUsername.ifBlank { "git" }, s.gitToken)
            git.fetch().setRemote("origin").setCredentialsProvider(creds).call()
            val remoteRef = repo.exactRef("refs/remotes/origin/$branch")
            val head = repo.resolve("HEAD")

            if (remoteRef != null && head == null) {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteRef.objectId.name).call()
                return "pulled"
            }
            if (remoteRef != null && !isAncestor(repo, remoteRef.objectId, head!!)) {
                val result = git.merge().include(remoteRef).setCommit(true).setMessage("pact: merge").call()
                if (result.mergeStatus == MergeResult.MergeStatus.CONFLICTING) {
                    result.conflicts.keys.firstOrNull { !owns(it) }?.let { other ->
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(head.name).call()
                        error("merge conflict outside Pact's folder ($other)")
                    }
                    val n = resolveConflicts(git, head, remoteRef.objectId, result.conflicts.keys)
                    git.commit().setMessage("pact: merge ($n conflict(s) resolved)").call()
                    parts += "$n conflict(s) resolved"
                } else if (!result.mergeStatus.isSuccessful) {
                    error("merge failed: ${result.mergeStatus}")
                }
                parts += "pulled"
            }
            val newHead = repo.resolve("HEAD")
            if (remoteRef == null || !isAncestor(repo, newHead, remoteRef.objectId)) {
                git.push().setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/$branch")).setCredentialsProvider(creds).call()
                    .forEach { r -> r.remoteUpdates.forEach { u -> if (u.status.name !in setOf("OK", "UP_TO_DATE")) error("push ${u.status}: ${u.message ?: ""}") } }
                parts += "pushed"
            }
            return parts.joinToString(", ").ifEmpty { "up to date" }
        }
    }

    /** True when [ancestor] is reachable from [tip]. */
    private fun isAncestor(repo: Repository, ancestor: ObjectId, tip: ObjectId): Boolean = RevWalk(repo).use { rw ->
        rw.isMergedInto(rw.parseCommit(ancestor), rw.parseCommit(tip))
    }

    private fun readAt(repo: Repository, commit: ObjectId, path: String): ByteArray? = RevWalk(repo).use { rw ->
        TreeWalk.forPath(repo, path, rw.parseCommit(commit).tree)?.use { tw -> repo.open(tw.getObjectId(0)).bytes }
    }

    private fun updatedOf(bytes: ByteArray): Long = runCatching {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject["updated"]?.jsonPrimitive?.longOrNull
    }.getOrNull() ?: 0L

    private fun resolveConflicts(git: Git, ours: ObjectId, theirs: ObjectId, paths: Set<String>): Int {
        val repo = git.repository
        for (path in paths) {
            val o = readAt(repo, ours, path)
            val t = readAt(repo, theirs, path)
            val winner = when {
                o != null && t != null -> if (path.endsWith(".json") && updatedOf(t) > updatedOf(o)) t else o
                else -> o ?: t
            }
            val file = File(repo.workTree, path)
            if (winner == null) {
                file.delete()
                git.rm().addFilepattern(path).call()
            } else {
                file.parentFile?.mkdirs()
                file.writeBytes(winner)
                git.add().addFilepattern(path).call()
            }
        }
        return paths.size
    }
}
