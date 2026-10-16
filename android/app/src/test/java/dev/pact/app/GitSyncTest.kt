package dev.pact.app

import dev.pact.app.core.Task
import dev.pact.app.data.Settings
import dev.pact.app.data.Store
import dev.pact.app.sync.GitSync
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Same scenario as the Rust sync test: shared repo, another app's data, conflicting edits. */
class GitSyncTest {
    private fun tmp(name: String): File = Files.createTempDirectory("pact-$name").toFile()

    @Test
    fun devicesConvergeInSharedRepo() {
        val remote = tmp("remote")
        Git.init().setBare(true).setDirectory(remote).setInitialBranch("main").call().close()
        val s = Settings(gitRemote = remote.toURI().toString(), gitBranch = "main", gitFolder = "pact")

        // Another app already keeps data in the shared repo.
        val other = tmp("other")
        Git.cloneRepository().setURI(s.gitRemote).setDirectory(other).call().use { git ->
            File(other, "notes").mkdirs()
            File(other, "notes/a.txt").writeText("other app")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("other app").setAuthor("o", "o@x").setCommitter("o", "o@x").call()
            git.push().add("HEAD:refs/heads/main").call()
        }

        // Device A has data in the old root layout and migrates it.
        val aRepo = tmp("a")
        val old = Store(aRepo)
        val t = Task(title = "shared")
        old.saveTask(t)
        Store.migrateIntoFolder(aRepo, "pact")
        val a = Store(Store.dataDir(aRepo, "pact"))
        assertNotNull(a.task(t.id))
        File(aRepo, "scratch.txt").writeText("not pact")
        GitSync.sync(aRepo, s, "a")
        assertTrue("other app's data is pulled", File(aRepo, "notes/a.txt").exists())
        Git.open(aRepo).use { git ->
            val tracked = git.repository.readDirCache().let { dc -> (0 until dc.entryCount).map { dc.getEntry(it).pathString } }
            assertFalse(tracked.contains("scratch.txt"))
            assertTrue(tracked.all { it.startsWith("pact/") || it.startsWith("notes/") })
        }

        val bRepo = tmp("b")
        val b = Store(Store.dataDir(bRepo, "pact"))
        GitSync.sync(bRepo, s, "b")
        b.reload()
        assertNotNull(b.task(t.id))

        // Both edit the same task; B edits last and wins.
        a.saveTask(a.task(t.id)!!.copy(title = "from a"))
        Thread.sleep(5)
        b.saveTask(b.task(t.id)!!.copy(title = "from b"))
        GitSync.sync(aRepo, s, "a")
        val msg = GitSync.sync(bRepo, s, "b")
        assertTrue(msg, msg.contains("1 conflict"))
        GitSync.sync(aRepo, s, "a")
        a.reload()
        b.reload()
        assertEquals("from b", a.task(t.id)!!.title)
        assertEquals("from b", b.task(t.id)!!.title)
    }
}
