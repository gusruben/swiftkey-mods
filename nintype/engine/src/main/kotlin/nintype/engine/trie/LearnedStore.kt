package nintype.engine.trie

import java.io.File

/**
 * Persistence of what the engine learns while the user types, in the original's own file formats
 * and layout (Shing::Flector binary, see [FlecWriter]), under a root directory that stands for the
 * app's files/appdata:
 *
 *   emkey/pendingwords/<mexify(word)>  pending words      (WordLearningModule KVStore; PendingWord::save on every usage change)
 *   emkey/wordsaveset<i>              custom / learned words (MooWordSaveSet i; Saver, the frame after a change)
 *   emkey/weirdcases                  learned casings     (WeirdCaseManager; Saver)
 *   emkey/badexplicit                 deleted short words (BadExplicitManager; Saver)
 *   emkeyautoc/<mexify(stem)>          autocomplete history (ACStem::saveIt, when its trigger fires)
 *
 * The original writes each file at those moments; here [save] writes every file whose bytes
 * changed since the last save or load (each through a temp file and a rename, as Flector::save
 * does), so calling it after each commit or on pause gives the same directory. Nothing here
 * throws: an unreadable directory or file is logged and that part starts fresh.
 *
 * Not persisted (as of 2026-10-20): the chain predictor (ChainEnv: the mmapped chainraw ring and
 * NCBucket pools, keyed by chain.cryptex) is not wired into the engine yet, and the Coreccion
 * store only fills from the backspace paths, which are not ported.
 */
class LearnedStore(val root: File, val log: (String) -> Unit = { defaultLog(it) }) {
    companion object {
        /** Where a store made without its own [log] writes. Messages can name learned words. */
        @JvmField @Volatile
        var defaultLog: (String) -> Unit = { System.err.println("nintype learned: $it") }
    }

    private val emkey = File(root, "emkey")
    private val pendingDir = File(emkey, "pendingwords")
    private val acDir = File(root, "emkeyautoc")
    /** relative path -> bytes last written or read, so [save] only touches what changed. */
    private val written = HashMap<String, ByteArray>()

    private fun readDir(dir: File, rel: String): Map<String, ByteArray> = try {
        val out = java.util.TreeMap<String, ByteArray>()
        for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile || f.name.endsWith(".temp")) continue
            val b = f.readBytes()
            out[f.name] = b
            written["$rel/${f.name}"] = b
        }
        out
    } catch (x: Exception) {
        log("reading $dir failed (${x.message}); starting fresh"); emptyMap()
    }

    private fun readFile(f: File, rel: String): ByteArray? = try {
        if (f.isFile) f.readBytes().also { written[rel] = it } else null
    } catch (x: Exception) {
        log("reading $f failed (${x.message}); starting fresh"); null
    }

    /**
     * The custom-word half, which has to be in place before the rest of the engine uses the trie:
     * when save sets exist, a copy of [packTrie] gets them back (LangEnv::reloadCustomWords) and
     * that copy and its env replace the fresh install's (pack + Moopad default word mods); null when
     * there is nothing saved or it could not be used.
     */
    fun loadWordMods(packTrie: CompactTrie): Pair<CompactTrie, WordModEnv>? {
        val files = java.util.TreeMap<String, ByteArray>()
        for (f in emkey.listFiles().orEmpty()) if (f.isFile && f.name.startsWith("wordsaveset")) readFile(f, "emkey/${f.name}")?.let { files[f.name] = it }
        if (!files.containsKey("wordsaveset0")) return null
        return try {
            val t = packTrie.copy()
            val env = WordModEnv(t)
            env.reloadCustomWords(files, log, reflectAll = true)
            if (env.saveSets.all { it.isEmpty() }) { log("saved word sets are empty; using the defaults"); null } else Pair(t, env)
        } catch (x: Exception) {
            log("reloading custom words failed (${x.message}); using the defaults"); null
        }
    }

    /** Everything else: pending words, autocomplete stems, the casing managers. */
    fun loadRest(wlm: WordLearningModule, ac: AutoCompLearn, wc: WeirdCaseManager, be: BadExplicitManager) {
        try { wlm.load(readDir(pendingDir, "emkey/pendingwords"), log) } catch (x: Exception) {
            log("pending words failed to load (${x.message}); starting fresh"); wlm.pending.clear(); wlm.expiry.clear(); wlm.disk.clear()
        }
        try { ac.load(readDir(acDir, "emkeyautoc")) } catch (x: Exception) {
            log("autocomplete stems failed to load (${x.message}); starting fresh"); ac.load(emptyMap())
        }
        readFile(File(emkey, "weirdcases"), "emkey/weirdcases")?.let { d ->
            try { wc.load(d) } catch (x: Exception) { log("weirdcases unreadable (${x.message}); starting fresh"); wc.map.clear() }
        }
        readFile(File(emkey, "badexplicit"), "emkey/badexplicit")?.let { d ->
            try { be.load(d) } catch (x: Exception) { log("badexplicit unreadable (${x.message}); starting fresh"); be.map.clear() }
        }
    }

    /** Writes every learned-state file whose content changed, and removes the pending-word and stem
     *  files the engine deleted. Returns false (after logging) if anything could not be written. */
    fun save(env: WordModEnv, wlm: WordLearningModule, ac: AutoCompLearn, wc: WeirdCaseManager, be: BadExplicitManager): Boolean {
        var ok = true
        fun put(rel: String, data: ByteArray): Boolean {
            if (written[rel]?.contentEquals(data) == true) return true
            return try {
                val f = File(root, rel)
                f.parentFile.mkdirs()
                val tmp = File(f.parentFile, f.name + ".temp")
                tmp.writeBytes(data)
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("rename to $f failed") }
                written[rel] = data
                true
            } catch (x: Exception) { ok = false; log("writing $rel failed (${x.message})"); false }
        }
        fun syncDir(rel: String, files: Map<String, ByteArray>) {
            for ((n, d) in files) put("$rel/$n", d)
            val prefix = "$rel/"
            for (k in written.keys.filter { it.startsWith(prefix) && it.substring(prefix.length) !in files }) {
                try { File(root, k).delete(); written.remove(k) } catch (x: Exception) { ok = false; log("deleting $k failed (${x.message})") }
            }
        }
        try {
            for ((n, d) in env.saveFiles()) put("emkey/$n", d)
            syncDir("emkey/pendingwords", wlm.disk)
            ac.flush()
            syncDir("emkeyautoc", ac.disk)
            if (wc.dirty && put("emkey/weirdcases", wc.binarize())) wc.dirty = false
            if (be.dirty && put("emkey/badexplicit", be.binarize())) be.dirty = false
        } catch (x: Exception) {
            ok = false; log("saving failed (${x.message})")
        }
        return ok
    }
}
