package nintype.engine.ports

import nintype.engine.trie.Caps
import nintype.engine.trie.CompactTrie
import nintype.engine.trie.LangEnv
import nintype.engine.trie.TrieDump
import nintype.engine.trie.TrieWord
import nintype.engine.trie.WordModEnv
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.Values

/** Vector adapters for re/vectors/gen_trie_learn.py (learned-word marking chain + isw tables). */

private val learnPackOnly: CompactTrie by lazy { TrieDump.loadEnglish(java.io.File("../re/out/trie/english")) }
private val learnBaseNodes: ByteArray by lazy {
    val t = learnPackOnly.copy(); LangEnv.applyMoopadDefaults(t); t.nodes.dumpBytes()
}

private fun lBytes(j: nintype.engine.vectors.JsonValue) = Values.parseBytes(j.asObject()["v"]!!.asString())

private fun lMasked(b: ByteArray, o: Int): ByteArray {
    val r = b.copyOfRange(o, o + 24)
    val ln = r[0xc].toInt() and 0xff
    for (i in 4 + minOf(ln, 8) until 0x10) if (i != 0xc) r[i] = 0
    return r
}

private fun w32(o: java.io.ByteArrayOutputStream, v: Int) { o.write(v); o.write(v ushr 8); o.write(v ushr 16); o.write(v ushr 24) }

internal fun registerTrieLearnPorts() {
    PortRegistry.register("shing_iswupper(int)") { c -> CaseResult(ret = Leaf.bool(Caps.shingIswupper(c.args[0].asInt()))) }
    PortRegistry.register("shing_iswlower(int)") { c -> CaseResult(ret = Leaf.bool(Caps.shingIswlower(c.args[0].asInt()))) }
    PortRegistry.register("Emkey::TrieWord::getScoremult(Emkey::Moopad*)") { c ->
        CaseResult(ret = Leaf.f32(WordModEnv.trieWordScoremult(TrieWord(lBytes(c.inputs["tw"]!!)), c.args[0].asInt())))
    }
    PortRegistry.register("Emkey::TrieWord::isLearnedFCAL(Emkey::Moopad*)") { c ->
        CaseResult(ret = Leaf.bool(WordModEnv.trieWordIsLearnedFCAL(TrieWord(lBytes(c.inputs["tw"]!!)), c.args[0].asInt())))
    }
    PortRegistry.register("Emkey::LangEnv::learnNewWord(std::string const&)") { c ->
        val t = learnPackOnly.copy()
        val env = LangEnv.applyMoopadDefaults(t)
        for (op in c.inputs["ops"]!!.asObject()["\$vec"]!!.asArray()) {
            val o = op.asObject()
            val w = lBytes(o["w"]!!)
            when (Leaf.fromJson(o["op"]!!).asInt()) {
                3 -> env.learnNewWord(w)
                4 -> env.markWordLearned(w)
                else -> env.modifyWordLC(w, Leaf.fromJson(o["f"]!!).asFloat())
            }
        }
        learnOutputs(t, env, c)
    }
}

/** The learnNewWord vectors' outputs for a trie and its word-mod env: probe words, changed nodes,
 *  pool sizes, the MooWord store and the save-set files. Also used by the reload test. */
internal fun learnOutputs(t: CompactTrie, env: WordModEnv, c: nintype.engine.vectors.VectorCase): CaseResult {
    val words = java.io.ByteArrayOutputStream()
    for (pj in c.inputs["probe"]!!.asObject()["\$vec"]!!.asArray()) {
        val p = lBytes(pj)
        w32(words, p.size); words.write(p)
        val id = env.maybeGetRawWord(p)
        if (id < 0) words.write(0) else {
            words.write(1); w32(words, id)
            val b = t.trieWords.data(id).copyOfRange(t.trieWords.off(id), t.trieWords.off(id) + 16)
            CompactTrie.setLe32(b, 4, CompactTrie.le32(b, 4) and 0x01fffff0)
            words.write(b)
        }
    }
    val now = t.nodes.dumpBytes()
    val base = learnBaseNodes
    val changed = java.io.ByteArrayOutputStream()
    var o = 0
    while (o < now.size) {
        val m = lMasked(now, o)
        if (o >= base.size || !m.contentEquals(lMasked(base, o))) { w32(changed, o / 24); changed.write(m) }
        o += 24
    }
    val sizes = java.io.ByteArrayOutputStream()
    w32(sizes, t.nodes.size); w32(sizes, t.trieWords.size); w32(sizes, t.strings.used.sum())
    val st = java.io.ByteArrayOutputStream()
    w32(st, env.saveSets.size)
    for (set in env.saveSets) {
        w32(st, set.size)
        for ((word, mw) in set) {
            w32(st, word.size); st.write(word)
            w32(st, mw.flags); w32(st, mw.defaultScore.toRawBits()); st.write(if (mw.useDefault) 1 else 0)
            w32(st, mw.scores.size); for ((k, v) in mw.scores) { w32(st, k); st.write(v) }
            w32(st, mw.exiled.size); for (k in mw.exiled) w32(st, k)
            w32(st, mw.setIndex)
        }
    }
    val files = env.saveFiles().toSortedMap().map { (n, d) ->
        Values.struct("name" to Values.bytesLeaf(n.toByteArray()), "data" to Values.bytesLeaf(d)) }
    return CaseResult(out = mapOf("words" to Values.bytesLeaf(words.toByteArray()), "nodes" to Values.bytesLeaf(changed.toByteArray()),
        "sizes" to Values.bytesLeaf(sizes.toByteArray()), "store" to Values.bytesLeaf(st.toByteArray()), "files" to Values.vec(files)))
}


/** The reload half of the persistence check: the binary's wordsaveset files (the case's expected
 *  out.files) reloaded into a trie fresh from the pack (LangEnv::reloadCustomWords, reflecting
 *  every word as LearnedStore does), giving the same outputs as the live sequence, and binarizing
 *  back to the same files. */
internal fun learnReloadCase(c: nintype.engine.vectors.VectorCase): CaseResult {
    val t = learnPackOnly.copy()
    val env = WordModEnv(t)
    val files = c.outputs["files"]!!.asObject()["\$vec"]!!.asArray().map { it.asObject() }
        .associate { String(lBytes(it["name"]!!)) to lBytes(it["data"]!!) }
    env.reloadCustomWords(files, reflectAll = true)
    return learnOutputs(t, env, c)
}
