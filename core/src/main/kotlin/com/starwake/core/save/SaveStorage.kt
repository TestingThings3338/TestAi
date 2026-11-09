package com.starwake.core.save

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Where save files live (Platform implements it; DECISIONS 23). Names are plain file names such as
 * "profile.json" ([isValidName]); no directories.
 *
 * [writeSave] replaces a file ATOMICALLY (a reader sees the old or the new content, never a mix)
 * and keeps the version it replaces as "<name>.bak" ([backupName]), readable with [readSave] like
 * any other file. It may finish on another thread; the caller does not touch [data] afterwards.
 *
 * A storage that finishes writes on another thread ([AsyncSaveStorage]) returns true from
 * [writeSave] as soon as the write is queued, so true only means "accepted"; the outcome comes
 * later through [finishedWrites] and [writeState], which [SaveManager] polls. A synchronous storage
 * keeps their defaults: its [writeSave] result is final.
 */
interface SaveStorage {
    /** The file's bytes, or null when it does not exist or cannot be read. */
    fun readSave(name: String): ByteArray?

    /**
     * Replaces the file (atomic, previous version kept as .bak); false when nothing can be stored.
     * True from an asynchronous storage means queued: see [writeState].
     */
    fun writeSave(name: String, data: ByteArray): Boolean

    /**
     * How many queued writes have finished so far, stored or failed (asynchronous storages). It only
     * grows and is a plain volatile read, so the game thread polls it every frame and asks
     * [writeState] only when it moved. A synchronous storage: always 0.
     */
    val finishedWrites: Int get() = 0

    /**
     * The newest write of [name]: [WRITE_PENDING] while it is queued or running, [WRITE_FAILED] when
     * it finished without storing the data, else [WRITE_DONE] (also when there was none). A newer
     * write that is stored supersedes an older failure (each write is a whole file). A synchronous
     * storage: always [WRITE_DONE], its failures are [writeSave]'s false.
     */
    fun writeState(name: String): Int = WRITE_DONE

    companion object {
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        /** [writeState]: stored (or never written). */
        const val WRITE_DONE = 0

        /** [writeState]: queued or being written; the outcome is not known yet. */
        const val WRITE_PENDING = 1

        /** [writeState]: finished, and the data was not stored. */
        const val WRITE_FAILED = 2

        /** Safe save names: letters, digits, '.', '_' and '-', no paths, no ".tmp". */
        fun isValidName(name: String): Boolean = NAME.matches(name) && !name.endsWith(".tmp")

        fun backupName(name: String): String = "$name.bak"
    }
}

/**
 * Save files in a directory (the Android app's files dir, the harness's save dir). [writeSave]:
 * the data goes to "<name>.tmp" and is synced, the current file is copied to "<name>.bak" (through
 * its own temp file), then the temp file is renamed over the file. At every moment the file and its
 * backup are complete. Synchronous; wrap in [AsyncSaveStorage] to keep it off the game thread.
 */
class FileSaveStorage(val dir: File) : SaveStorage {
    override fun readSave(name: String): ByteArray? {
        if (!SaveStorage.isValidName(name)) return null
        return try {
            val f = File(dir, name)
            if (f.isFile) f.readBytes() else null
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override fun writeSave(name: String, data: ByteArray): Boolean {
        if (!SaveStorage.isValidName(name)) return false
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            writeSynced(tmp, data)
            if (target.isFile) {
                val bakTmp = File(dir, "${SaveStorage.backupName(name)}.tmp")
                writeSynced(bakTmp, target.readBytes())
                move(bakTmp, File(dir, SaveStorage.backupName(name)))
            }
            move(tmp, target)
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun writeSynced(f: File, data: ByteArray) {
        FileOutputStream(f).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
    }

    /** rename(2): atomically replaces [to] on the platforms the game runs on. */
    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Save files in memory with the same backup rule as [FileSaveStorage]: the tests' storage, and the
 * harness's when it is given no save directory (hermetic runs). [put] plants raw bytes (corrupt
 * files in tests).
 */
class MemorySaveStorage : SaveStorage {
    private val files = HashMap<String, ByteArray>()

    /** Successful [writeSave] calls. */
    var writes = 0
        private set

    /** When false, [writeSave] refuses (an unwritable device). */
    var writable = true

    @Synchronized
    override fun readSave(name: String): ByteArray? = files[name]?.copyOf()

    @Synchronized
    override fun writeSave(name: String, data: ByteArray): Boolean {
        if (!writable || !SaveStorage.isValidName(name)) return false
        files[name]?.let { files[SaveStorage.backupName(name)] = it }
        files[name] = data.copyOf()
        writes++
        return true
    }

    /** Stores [data] as [name] directly, without touching the backup. */
    @Synchronized
    fun put(name: String, data: ByteArray?) {
        if (data == null) files.remove(name) else files[name] = data.copyOf()
    }

    @Synchronized
    fun names(): Set<String> = files.keys.toSortedSet()
}

/**
 * Runs another storage's writes on one background thread, in order (no coalescing: two writes of
 * the same file rotate the backup twice, which [SaveManager] relies on when it repairs a save).
 * [readSave] waits for queued writes first, so a read always sees the newest data. The thread
 * starts with the first write and is a daemon.
 *
 * [writeSave] answers true once the write is queued; whether the target then stored it is
 * reported per file through [writeState] and [finishedWrites], so the save manager can write a
 * failed file again and show the saves as offline.
 */
class AsyncSaveStorage(private val target: SaveStorage, private val threadName: String = "save-writer") : SaveStorage {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<Pair<String, ByteArray>>()
    private var busy = false
    private var thread: Thread? = null

    /** Per file name (under [lock]): writes queued and not finished, and whether the newest finished one failed. */
    private class FileState {
        var pending = 0
        var failed = false
    }

    private val states = HashMap<String, FileState>()

    /** Writes the target refused or that threw (diagnostics). */
    @Volatile
    var failures = 0
        private set

    @Volatile
    private var finished = 0

    override val finishedWrites: Int get() = finished

    override fun writeSave(name: String, data: ByteArray): Boolean {
        if (!SaveStorage.isValidName(name)) return false
        lock.withLock {
            queue.addLast(name to data)
            states.getOrPut(name) { FileState() }.pending++
            if (thread == null) {
                thread = Thread({ run() }, threadName).apply {
                    isDaemon = true
                    start()
                }
            }
            changed.signalAll()
        }
        return true
    }

    override fun writeState(name: String): Int = lock.withLock {
        val s = states[name]
        when {
            s == null -> SaveStorage.WRITE_DONE
            s.pending > 0 -> SaveStorage.WRITE_PENDING
            s.failed -> SaveStorage.WRITE_FAILED
            else -> SaveStorage.WRITE_DONE
        }
    }

    override fun readSave(name: String): ByteArray? {
        flush()
        return target.readSave(name)
    }

    /** Waits until every queued write is done (at most [timeoutMillis]); true when the queue is empty. */
    fun flush(timeoutMillis: Long = 5000L): Boolean {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        lock.withLock {
            while (queue.isNotEmpty() || busy) {
                if (left <= 0L) return false
                left = changed.awaitNanos(left)
            }
        }
        return true
    }

    private fun run() {
        while (true) {
            val job: Pair<String, ByteArray>
            lock.withLock {
                while (queue.isEmpty()) changed.awaitUninterruptibly()
                job = queue.removeFirst()
                busy = true
            }
            val ok = try {
                target.writeSave(job.first, job.second)
            } catch (e: Exception) {
                false
            }
            lock.withLock {
                val state = states.getValue(job.first)
                state.pending--
                state.failed = !ok
                if (!ok) failures++
                finished++
                busy = false
                changed.signalAll()
            }
        }
    }
}
