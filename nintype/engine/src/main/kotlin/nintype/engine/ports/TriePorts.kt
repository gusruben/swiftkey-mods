package nintype.engine.ports

import nintype.engine.trie.CompactTrie
import nintype.engine.trie.AutoComplete
import nintype.engine.trie.AutocompleteSearch
import nintype.engine.trie.AutocompleteTriePoint
import nintype.engine.trie.Caps
import nintype.engine.trie.CapsSettings
import nintype.engine.trie.CombinedTriePath
import nintype.engine.trie.Compat
import nintype.engine.trie.CompactCoverageData
import nintype.engine.trie.CompatSearch
import nintype.engine.trie.CompactTriePath
import nintype.engine.trie.PendingTrie
import nintype.engine.trie.PendingTrieNode
import nintype.engine.trie.TrieDump
import nintype.engine.trie.TrieQuery
import nintype.engine.trie.Keycode
import nintype.engine.trie.Sspc
import nintype.engine.trie.LangEnv
import nintype.engine.trie.TrieWord
import nintype.engine.vectors.CaseResult
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.PortRegistry
import nintype.engine.vectors.Values
import nintype.engine.vectors.VectorCase

/** Vector adapters for re/vectors/gen_trie_*.py. */

private fun regI(name: String, body: (Int) -> Int) =
    PortRegistry.register(name) { c -> CaseResult(ret = Leaf.i32(body(c.args[0].asInt()))) }

private fun regB(name: String, body: (Int) -> Boolean) =
    PortRegistry.register(name) { c -> CaseResult(ret = Leaf.bool(body(c.args[0].asInt()))) }

/** Runs [body] on a TrieWord view of the case's `this` image and returns the image for the
 *  out/unchanged checks. */
private fun regTW(name: String, body: (TrieWord, VectorCase) -> Leaf?) = PortRegistry.register(name) { c ->
    val img = MemImage.decode(c.inputs["this"]!!, "this")
    val b = img.readBytes(0, 16)
    val tw = TrieWord(b)
    val r = body(tw, c)
    if (!b.contentEquals(img.readBytes(0, 16))) img.writeBytes(0, b)
    CaseResult(ret = r, mem = mapOf("this" to img))
}

/** Zero the bytes gen_trie_build.py zeroes (undefined in the engine): node label bytes past the
 *  label length + padding 0xD..0xF, TrieWord +4 bits 0-3 and 25-31. */
private fun sanitizedNodes(b: ByteArray): ByteArray {
    val o = b.copyOf()
    var i = 0
    while (i < o.size) {
        val ln = o[i + 0xc].toInt() and 0xff
        for (k in 4 + minOf(ln, 8) until 0xc) o[i + k] = 0
        o[i + 0xd] = 0; o[i + 0xe] = 0; o[i + 0xf] = 0
        i += CompactTrie.NODE
    }
    return o
}

private fun sanitizedTrieWords(b: ByteArray): ByteArray {
    val o = b.copyOf()
    for (i in o.indices step 16) CompactTrie.setLe32(o, i + 4, CompactTrie.le32(o, i + 4) and 0x01fffff0)
    return o
}

private fun freeList(s: Sspc): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (p in s.pools) for (f in p.free) { out.write(f); out.write(f ushr 8); out.write(f ushr 16); out.write(f ushr 24) }
    return out.toByteArray()
}

private val englishPackOnly: CompactTrie by lazy { TrieDump.loadEnglish(java.io.File("../re/out/trie/english")) }
private val englishBase: Pair<CompactTrie, ByteArray> by lazy {
    val t = englishPackOnly.copy(); nintype.engine.trie.LangEnv.applyMoopadDefaults(t); t to t.nodes.dumpBytes()
}

private fun maskedNode(b: ByteArray, o: Int): ByteArray {
    val r = b.copyOfRange(o, o + 24)
    val ln = r[0xc].toInt() and 0xff
    for (i in 4 + minOf(ln, 8) until 0x10) if (i != 0xc) r[i] = 0
    return r
}

/** The English dictionary as the booted oracle has it (built once from the pack). */
private val english: CompactTrie by lazy { TrieDump.buildEnglish(java.io.File("../re/out/trie/english")) }

private fun bytesOf(j: nintype.engine.vectors.JsonValue) = Values.parseBytes(j.asObject()["v"]!!.asString())
private fun leafInt(j: nintype.engine.vectors.JsonValue) = Leaf.fromJson(j).asInt()
private fun idsOf(b: ByteArray) = (0 until b.size / 4).map { CompactTrie.le32(b, 4 * it) }
private fun idBytes(ids: List<Int>): ByteArray {
    val o = ByteArray(4 * ids.size)
    for ((i, v) in ids.withIndex()) CompactTrie.setLe32(o, 4 * i, v)
    return o
}

private fun pathOf(j: nintype.engine.vectors.JsonValue): CompactTriePath {
    val o = j.asObject()
    return CompactTriePath(english, ArrayList(idsOf(bytesOf(o["path"]!!))), leafInt(o["curid"]!!), leafInt(o["node"]!!), leafInt(o["pos"]!!))
}

private fun pathJson(p: CompactTriePath) = Values.struct("path" to Values.bytesLeaf(idBytes(p.path)),
    "curid" to Leaf.i32(p.curid).toJson(), "node" to Leaf.i32(p.node).toJson(), "pos" to Leaf.i32(p.pos).toJson())

/** A CompactTriePath method: returns (ret, extra outs); `this` is serialized back as out.this. */
private fun regPath(name: String, body: (CompactTriePath, VectorCase) -> Pair<Leaf?, Map<String, nintype.engine.vectors.JsonValue>>) =
    PortRegistry.register(name) { c ->
        val p = pathOf(c.inputs["this"]!!)
        val (r, extra) = body(p, c)
        CaseResult(ret = r, out = mapOf("this" to pathJson(p)) + extra)
    }

private fun setBytes(s: java.util.TreeSet<Int>) = Values.bytesLeaf(ByteArray(s.size).also { b -> s.forEachIndexed { i, v -> b[i] = v.toByte() } })

/** CombinedTriePath state: {main, pnode (1 = the pending root of in.pend), ppos, pdead, mdead}. */
private fun pendRoot(c: VectorCase): PendingTrieNode {
    val o = c.inputs["pend"]!!.asObject()
    check(leafInt(o["len"]!!) == 0)
    return PendingTrieNode().also { it.valued = leafInt(o["valued"]!!) != 0; it.value.shortest = Leaf.fromJson(o["minDist"]!!).asInt().toByte().toInt() }
}

/** Resolves CombinedTriePath "pnode" values: gen_trie_combined.py (1 = the empty pending root of
 *  in.pend) or gen_trie_combined_pending.py (pre-order index into the trie of in.pwords). */
private class PendCtx(c: VectorCase) {
    val order: List<PendingTrieNode>
    val indexed: Boolean = c.inputs.containsKey("pwords")
    init {
        if (indexed) {
            val root = PendingTrieNode.create(null, ByteArray(0), 1)
            PendingTrie.resync(root, pendingWords(c.inputs["pwords"]!!))
            order = pendingPreorder(root)
        } else order = listOf(pendRoot(c))
    }
    fun resolve(v: Int): PendingTrieNode? = if (indexed) (if (v < 0) null else order[v]) else (if (v != 0) order[0] else null)
    fun index(n: PendingTrieNode?): Int = if (indexed) (n?.let { x -> order.indexOfFirst { it === x } } ?: -1) else (if (n != null) 1 else 0)
}

private fun combOf(j: nintype.engine.vectors.JsonValue, ctx: PendCtx): CombinedTriePath {
    val o = j.asObject()
    return CombinedTriePath(pathOf(o["main"]!!), ctx.resolve(leafInt(o["pnode"]!!)), leafInt(o["ppos"]!!),
        leafInt(o["pdead"]!!) and 0xff != 0, leafInt(o["mdead"]!!) and 0xff != 0)
}

private fun combJson(p: CombinedTriePath, ctx: PendCtx) = Values.struct("main" to pathJson(p.main),
    "pnode" to Leaf.i32(ctx.index(p.pnode)).toJson(), "ppos" to Leaf.i32(p.ppos).toJson(),
    "pdead" to Leaf("u8", if (p.pendDead) 1L else 0L).toJson(), "mdead" to Leaf("u8", if (p.mainDead) 1L else 0L).toJson())

private fun regComb(name: String, body: (CombinedTriePath, VectorCase) -> Pair<Leaf?, Map<String, nintype.engine.vectors.JsonValue>>) =
    PortRegistry.register(name) { c ->
        val ctx = PendCtx(c)
        val p = combOf(c.inputs["this"]!!, ctx)
        val (r, extra) = body(p, c)
        CaseResult(ret = r, out = mapOf("this" to combJson(p, ctx)) + extra)
    }

/** Replays gen_trie_compat.py's recorded PathSearch answers; a question the original did not
 *  ask is an error (so the port must ask exactly the same questions). */
private class ReplaySearch(calls: List<List<Long>>, override var bestBound: Float) : CompatSearch {
    private val table = HashMap<List<Long>, Int>()
    init { for (c in calls) table[c.subList(0, c.size - 1)] = c.last().toInt() }
    private fun ask(vararg k: Long): Int = table[k.toList()] ?: error("unrecorded PathSearch query ${k.toList()}")
    private fun f(vararg k: Long) = Float.fromBits(ask(*k))
    override fun nthCharCoverage(n: Int, key: Int) = f(0, n.toLong(), key.toLong())
    override fun onlyHasASingleTouch() = ask(1) != 0
    override fun slowSlideCoverage(n: Int, key: Int) = f(2, n.toLong(), key.toLong())
    override fun checkTrieNodeLengthMultSatisfaction(len: Int, thr: Float) =
        ask(3, len.toLong(), thr.toRawBits().toLong() and 0xffffffffL) and 0xff != 0
    override fun checkCoverage(a: Int, b: Int, c: Int, key: Int) = f(4, a.toLong(), b.toLong(), c.toLong(), key.toLong())
    override fun checkTouchCoverageByLetters(word: ByteArray) = f(5, *LongArray(word.size) { (word[it].toInt() and 0xff).toLong() })
    override fun checkTrieNodeEnderMult(len: Int, key: Int) = f(6, len.toLong(), key.toLong() and 0xffffffffL)
}

/** The pending words of a case as resync gets them: std::map order (bytewise), id = index + 1. */
internal fun pendingWords(j: nintype.engine.vectors.JsonValue): List<Pair<ByteArray, Any>> {
    val ws = j.asObject()["\$vec"]!!.asArray().map { bytesOf(it) }
    return ws.withIndex().sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.value, b.value) }.map { it.value to (it.index + 1) as Any }
}

internal fun pendingPreorder(root: PendingTrieNode): List<PendingTrieNode> {
    val out = ArrayList<PendingTrieNode>()
    fun rec(n: PendingTrieNode) { out.add(n); n.children().forEach(::rec) }
    rec(root)
    return out
}

/** Mirror of re/vectors/trie_ext.pt_serialize. */
internal fun serializePending(root: PendingTrieNode): ByteArray {
    val order = pendingPreorder(root)
    val idx = java.util.IdentityHashMap<PendingTrieNode, Int>()
    order.forEachIndexed { i, n -> idx[n] = i }
    val o = java.io.ByteArrayOutputStream()
    fun w32(v: Int) { o.write(v); o.write(v ushr 8); o.write(v ushr 16); o.write(v ushr 24) }
    for (n in order) {
        o.write(n.len); o.write(n.label)
        o.write(if (n.valued) 1 else 0)
        w32(n.value.words.size); n.value.words.forEach { w32(it as Int) }
        w32(n.value.stamp)
        w32(n.value.parent?.let { idx[it]!! } ?: -1)
        w32(n.value.prefix); w32(n.value.labelLen)
        o.write(n.value.lastChar); o.write(n.value.shortest)
        w32(n.value.bound.toRawBits()); w32(n.value.sumCovW.toRawBits()); w32(n.value.sumW.toRawBits())
        w32(n.parent?.let { idx[it]!! } ?: -1)
        w32(n.vec.size); n.vec.forEach { w32(it?.let { c -> idx[c]!! } ?: -1) }
        w32(n.map.size); for ((ch, c) in n.map) { o.write(ch); w32(idx[c]!!) }
        o.write(n.longest); o.write(n.longest ushr 8)
    }
    return o.toByteArray()
}

private fun capsSettings(c: VectorCase): CapsSettings {
    val o = c.inputs["set"]!!.asObject()
    fun b(k: String) = Leaf.fromJson(o[k]!!).asBool()
    val u123 = b("usf123"); val u40 = b("usf40")
    return CapsSettings(x21ca0 = b("x21ca0"), x21cd4 = b("x21cd4"), x29c5c = b("x29c5c"),
        usf = { n -> when (n) { 0x123 -> u123; 0x40 -> u40; else -> false } })
}

private val englishMarkov: nintype.engine.trie.BuiltInMarkov by lazy {
    nintype.engine.trie.BuiltInMarkov.fromPack(nintype.dict.NinDict.load(java.io.File("../re/out/trie/english/english.nbipack").readBytes()))
}

private fun hexBytes(j: nintype.engine.vectors.JsonValue) = Values.parseBytes(j.asString())

/** performNeoMarkov on the case's alternatives: markov through the Kotlin MarkovScoring port
 *  (checked against the engine's recorded calls), the chain predictor replayed from the record. */
private fun neoMarkovCase(c: VectorCase): CaseResult {
    val st0 = c.inputs["state"]!!.asObject()
    fun xb(k: String) = st0[k]!!.asDouble().toLong() != 0L
    fun xi(k: String) = st0[k]!!.asDouble().toLong().toInt()
    fun xd(k: String) = Double.fromBits(java.lang.Long.parseUnsignedLong(st0[k]!!.asString().removePrefix("0x"), 16))
    val st = nintype.engine.trie.NeoMarkovState(
        disabled = xi("disabled"), timeNow = xd("timeNow"), timeRef = xd("timeRef"),
        xnl29644 = xb("xnl29644"), xnl296e4 = xb("xnl296e4"), xnl296ac = xb("xnl296ac"), int296e0 = xi("int296e0"),
        xnl3196c = xb("xnl3196c"), xnl317e0 = xb("xnl317e0"), chainWeight = Float.fromBits(xi("chainWeight")),
        markovWeight = Float.fromBits(xi("markovWeight")), chainGathered = xb("chainGathered"), xnl37d8 = xb("xnl37d8"),
        history = c.inputs["history"]!!.asArray().map { hexBytes(it) })
    val alts = c.inputs["alts"]!!.asArray().map { a ->
        val o = a.asObject()
        nintype.engine.trie.NeoAlternative(hexBytes(o["word"]!!), Float.fromBits(o["score"]!!.asDouble().toLong().toInt()), o["flags"]!!.asDouble().toLong().toInt())
    }.toMutableList()
    val mkRec = c.inputs["markovCalls"]!!.asArray().map { it.asObject() }
    val cpRec = c.inputs["chainCalls"]!!.asArray().map { it.asObject() }
    var mi = 0
    var ci = 0
    var gathered = false
    val hooks = object : nintype.engine.trie.NeoMarkovHooks {
        override fun markov(prev2: ByteArray, prev: ByteArray, word: ByteArray, score: Float, exponent: Float): Float {
            val r = mkRec.getOrNull(mi++) ?: error("extra markov call #$mi")
            check(hexBytes(r["prev2"]!!).contentEquals(prev2) && hexBytes(r["prev"]!!).contentEquals(prev) && hexBytes(r["word"]!!).contentEquals(word)) {
                "markov call #$mi args (${String(prev2)}, ${String(prev)}, ${String(word)}) != recorded (${r["prev2"]}, ${r["prev"]}, ${r["word"]})"
            }
            val ex = Float.fromBits(r["exponent"]!!.asDouble().toLong().toInt())
            check(ex.toRawBits() == exponent.toRawBits() && Float.fromBits(r["score"]!!.asDouble().toLong().toInt()).toRawBits() == score.toRawBits()) {
                "markov call #$mi exponent/score ${exponent} ${score} != recorded ${ex}"
            }
            val v = nintype.engine.trie.MarkovScoring.score(listOf(englishMarkov), prev2, prev, word, ex)
            check(v.toRawBits() == r["result"]!!.asDouble().toLong().toInt()) { "markov ${String(prev)} ${String(word)}: ${v} != recorded" }
            return v
        }
        override fun gatherSuggestions() { gathered = true }
        override fun chainPred(word: ByteArray, next: ByteArray): Float {
            val r = cpRec.getOrNull(ci++) ?: error("extra chainPred call #$ci")
            check(hexBytes(r["a"]!!).contentEquals(word) && hexBytes(r["b"]!!).contentEquals(next)) { "chainPred call #$ci args differ" }
            return Float.fromBits(r["result"]!!.asDouble().toLong().toInt())
        }
    }
    nintype.engine.trie.NeoMarkov.perform(alts, st, hooks)
    check(mi == mkRec.size && ci == cpRec.size) { "call counts: markov $mi/${mkRec.size}, chainPred $ci/${cpRec.size}" }
    val outs = alts.map { a ->
        Values.struct("word" to Values.bytesLeaf(a.word), "score" to Leaf.f32(a.score).toJson(), "markov" to Leaf.f32(a.markov).toJson(),
            "chain" to Leaf.f32(a.chain).toJson(), "final" to Leaf.f32(a.final).toJson(), "flags" to Leaf.u32(a.flags).toJson())
    }
    return CaseResult(out = mapOf("alts" to Values.vec(outs), "chainGathered" to Leaf("u8", if (st.chainGathered) 1L else 0L).toJson(),
        "gathered" to Leaf.bool(gathered).toJson()))
}

/** Replays gen_trie_coreccion.py's op sequence on a fresh CoreccionStore, then answers its queries. */
private fun coreccionCase(c: VectorCase): CaseResult {
    val store = nintype.engine.trie.CoreccionStore()
    fun f64(j: nintype.engine.vectors.JsonValue) = Double.fromBits(java.lang.Long.parseUnsignedLong(j.asString().removePrefix("0x"), 16))
    val limit = f64(c.inputs["limit"]!!)
    for (op in c.inputs["ops"]!!.asArray().map { it.asObject() }) {
        val ws = op["words"]?.asArray()?.map { hexBytes(it) }
        when (op["op"]!!.asString()) {
            "reg" -> store.registerChainReinforcement(ws!!, op["n"]!!.asInt())
            "add" -> store.addReinforcementWithDelay(ws!!, op["n"]!!.asInt(), f64(op["now"]!!))
            "update" -> store.updateDelayedReinforcements(f64(op["now"]!!), limit)
        }
    }
    val res = c.inputs["queries"]!!.asArray().map { it.asObject() }.map { q ->
        if (q["q"]!!.asString() == "check") {
            val root = store.rootIfExistent(hexBytes(q["prev"]!!))
            if (root == null) Values.struct("root" to Leaf.bool(false).toJson())
            else {
                val a = intArrayOf(0); val b = intArrayOf(0)
                val found = root.check(hexBytes(q["word"]!!), a, b)
                Values.struct("root" to Leaf.bool(true).toJson(), "found" to Leaf.bool(found).toJson(),
                    "a" to Leaf.u32(a[0]).toJson(), "b" to Leaf.u32(b[0]).toJson())
            }
        } else {
            val p2 = hexBytes(q["prev2"]!!); val p = hexBytes(q["prev"]!!); val w = hexBytes(q["word"]!!)
            val ex = Float.fromBits(q["exponent"]!!.asDouble().toLong().toInt())
            val ms = listOf(englishMarkov)
            Values.struct("inner" to Leaf.f32(nintype.engine.trie.MarkovScoring.inner(ms, p2, p, w, store)).toJson(),
                "result" to Leaf.f32(nintype.engine.trie.MarkovScoring.score(ms, p2, p, w, ex, store)).toJson())
        }
    }
    val pend = store.pending.map { p ->
        Values.struct("words" to Values.vec(p.words.map { Values.bytesLeaf(it) }), "n" to Leaf.u32(p.n).toJson(),
            "time" to Leaf("f64", p.time.toRawBits()).toJson())
    }
    return CaseResult(out = mapOf("results" to Values.vec(res), "pending" to Values.vec(pend)))
}

private fun unsignedCompare(x: ByteArray, y: ByteArray): Int {
    for (i in 0 until minOf(x.size, y.size)) {
        val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return x.size - y.size
}

/** One root reinforced many times: compares the chain-data shingcachemap (entries, LRU, MFU). */
private fun coreccionDeepCase(c: VectorCase): CaseResult {
    val store = nintype.engine.trie.CoreccionStore()
    for (op in c.inputs["ops"]!!.asArray().map { it.asObject() })
        store.registerChainReinforcement(op["words"]!!.asArray().map { hexBytes(it) }, op["n"]!!.asInt())
    val cm = store.rootIfExistent(hexBytes(c.inputs["root"]!!))!!.cache
    val ents = cm.map.values.sortedWith { x, y -> unsignedCompare(x.key.b, y.key.b) }.map { el ->
        Values.struct("key" to Values.bytesLeaf(el.key.b), "a" to Leaf.u32(el.value[0]).toJson(), "b" to Leaf.u32(el.value[1]).toJson(),
            "usage" to Leaf.u32(el.usage).toJson())
    }
    val cfg = listOf(if (cm.pruning) 1 else 0, cm.mfuThreshold, cm.mfuCap, cm.lruCap).map { Leaf.u32(it).toJson() }
    return CaseResult(out = mapOf("entries" to Values.vec(ents), "lru" to Values.vec(cm.lru.map { Values.bytesLeaf(it.key.b) }),
        "mfu" to Values.vec(cm.mfu.map { Values.bytesLeaf(it.key.b) }), "cfg" to Values.vec(cfg)))
}

private val englishEnv: nintype.engine.trie.WordModEnv by lazy { nintype.engine.trie.WordModEnv(english) }

private val lowerEnv: nintype.engine.trie.WordModEnv by lazy { nintype.engine.trie.WordModEnv(englishPackOnly) }

/** Replays gen_trie_wordlearn.py's onMerge sequence on a fresh WordLearningModule and dumps it. */
private fun wordLearnCase(c: VectorCase): CaseResult = wordLearnRun(c, false)

/** gen_trie_wordlearn.py's load check: the binary's pendingwords files through
 *  WordLearningModule.load on a fresh module, then the same dump. */
private fun wordLoadCase(c: VectorCase): CaseResult = wordLearnRun(c, true)

private fun filesOf(j: nintype.engine.vectors.JsonValue): Map<String, ByteArray> =
    j.asObject()["\$vec"]!!.asArray().map { it.asObject() }.associate { String(bytesOf(it["name"]!!), Charsets.ISO_8859_1) to bytesOf(it["data"]!!) }

private fun filesJson(m: Map<String, ByteArray>) = Values.vec(m.toSortedMap().map { (n, d) ->
    Values.struct("name" to Values.bytesLeaf(n.toByteArray(Charsets.ISO_8859_1)), "data" to Values.bytesLeaf(d)) })

private fun wordLearnRun(c: VectorCase, load: Boolean): CaseResult {
    var clockNow = nintype.engine.trie.SDate(0, 0)
    val calls = ArrayList<nintype.engine.vectors.JsonValue>()
    fun call(what: String, w: ByteArray, extra: Int) = calls.add(Values.struct("what" to Values.bytesLeaf(what.toByteArray()),
        "word" to Values.bytesLeaf(w), "extra" to Leaf.u32(extra).toJson()))
    // The real LangEnv side: learnNewWord / markMinimumScoreForWord on a copy of the pack trie, whose
    // saveAndReflect calls back WordLearningModule::onInduce (erasePendingWord).
    lateinit var wlm: nintype.engine.trie.WordLearningModule
    val env by lazy { nintype.engine.trie.WordModEnv(englishPackOnly.copy()).also { e -> e.onInduce = { wlm.onInduce(it) } } }
    wlm = nintype.engine.trie.WordLearningModule(lower = { lowerEnv.standardiseToLower(it) },
        // Altchain words (ß, æ, ...) cannot be inserted into the trie yet (deferred); their learnNewWord
        // still ends in saveAndReflect -> onInduce, which is all these vectors observe.
        learnNewWord = { call("learn", it, 0); try { env.learnNewWord(it) } catch (x: UnsupportedOperationException) { wlm.onInduce(lowerEnv.standardiseToLower(it)) } },
        markMinimumScore = { w, f, l -> call("minscore", w, f.toRawBits()); try { env.markMinimumScoreForWord(w, f, l) } catch (x: UnsupportedOperationException) {} },
        now = { clockNow })
    if (load) {
        clockNow = nintype.engine.trie.SDate(c.inputs["sec"]!!.asDouble().toLong().toInt(), c.inputs["usec"]!!.asDouble().toLong().toInt())
        wlm.load(filesOf(c.inputs["files"]!!))
    }
    fun f64hex(j: nintype.engine.vectors.JsonValue) = Double.fromBits(java.lang.Long.parseUnsignedLong(j.asString().removePrefix("0x"), 16))
    for (op in (c.inputs["ops"]?.asArray() ?: emptyList()).map { it.asObject() }) {
        val w = op["word"]?.let { hexBytes(it) } ?: ByteArray(0)
        val st = if (op["sec"] == null) nintype.engine.trie.SDate(0, 0)
            else nintype.engine.trie.SDate(op["sec"]!!.asDouble().toLong().toInt(), op["usec"]!!.asDouble().toLong().toInt())
        when (op["op"]!!.asString()) {
            "merge" -> wlm.onMerge(w, (op["typed"] as nintype.engine.vectors.JsonValue.JBool).value, st)
            "mergeWA" -> wlm.onMergeAlternative(w, (op["hasTrieWord"] as nintype.engine.vectors.JsonValue.JBool).value, op["flags"]!!.asInt(), st)
            "afterAll" -> wlm.onMergeAfterMergingAll(hexBytes(op["pre"]!!), w, (op["hasTrieWord"] as nintype.engine.vectors.JsonValue.JBool).value,
                op["flags"]!!.asInt(), st, f64hex(op["dnow"]!!), (op["usf10b"] as nintype.engine.vectors.JsonValue.JBool).value,
                { (op["isTrieWord"] as nintype.engine.vectors.JsonValue.JBool).value })
            "backspace" -> wlm.onBackspace(w, f64hex(op["dnow"]!!))
            "late" -> wlm.performLateErasures(f64hex(op["dnow"]!!))
            "prune" -> wlm.performPruning()
        }
    }
    val ents = wlm.pending.entries.map { (k, pw) ->
        Values.struct("key" to Values.bytesLeaf(k), "word" to Values.bytesLeaf(pw.word),
            "hist" to Values.vec(pw.hist.map { h -> Values.struct("flags" to Leaf.u32(h.flags).toJson(), "u4" to Leaf.u32(h.u4).toJson(),
                "sec" to Leaf.u32(h.date.sec).toJson(), "usec" to Leaf.u32(h.date.usec).toJson()) }),
            "caps" to Leaf.u32(pw.caps).toJson(), "upos" to Leaf.u32(pw.upos).toJson(), "points" to Leaf.u32(pw.points).toJson(),
            "expirySet" to Leaf("u8", if (pw.expirySet) 1L else 0L).toJson(), "expSec" to Leaf.u32(pw.expiry.sec).toJson(),
            "expUsec" to Leaf.u32(pw.expiry.usec).toJson())
    }
    val late = wlm.lateErasures.entries.map { (k, t) -> Values.struct("word" to Values.bytesLeaf(k), "t" to Leaf("f64", t.toRawBits()).toJson()) }
    return CaseResult(out = mapOf("entries" to Values.vec(ents), "expiry" to Values.vec(wlm.expiry.map { Values.bytesLeaf(it.word) }),
        "late" to Values.vec(late), "lateXnl" to Leaf("u8", if (wlm.lateXnl) 1L else 0L).toJson(),
        "count" to Leaf.u32(wlm.pending.size).toJson(), "expCount" to Leaf.u32(wlm.expiry.size).toJson(),
        "trieSynced" to Leaf("u8", if (wlm.trieSynced) 1L else 0L).toJson(), "manyXnl" to Leaf("u8", if (wlm.manyPending) 1L else 0L).toJson(),
        "calls" to Values.vec(calls), "files" to filesJson(wlm.disk)))
}

/** Replays gen_trie_autocomp.py's onStandardUsage / onCancel sequence and dumps every stem. */
private fun autoCompCase(c: VectorCase): CaseResult {
    val ac = nintype.engine.trie.AutoCompLearn()
    for (op in c.inputs["ops"]!!.asArray().map { it.asObject() }) {
        val w = hexBytes(op["word"]!!)
        val usec = op["usec"]!!.asDouble().toLong().toInt()
        val now = nintype.engine.trie.SDate(op["sec"]!!.asDouble().toLong().toInt(), usec)
        val clock = nintype.engine.trie.SDate(op["clockSec"]!!.asDouble().toLong().toInt(), usec)
        when (op["op"]!!.asString()) {
            "cancel" -> ac.onCancel(w, clock)
            "lock" -> ac.onLockUsage(w, op["slot"]!!.asInt(), hexBytes(op["prefix"]!!), clock)
            "fav" -> ac.markFavorite(w, op["bit"]!!.asInt(), (op["value"] as nintype.engine.vectors.JsonValue.JBool).value, clock)
            else -> ac.onStandardUsage(w, now, clock)
        }
    }
    return acOut(ac)
}

/** gen_trie_autocomp.py's load check: the binary's stem files, then obtainStem for each in.stems. */
private fun acLoadCase(c: VectorCase): CaseResult {
    val ac = nintype.engine.trie.AutoCompLearn()
    ac.load(filesOf(c.inputs["files"]!!))
    val clock = nintype.engine.trie.SDate(c.inputs["clockSec"]!!.asDouble().toLong().toInt(), c.inputs["clockUsec"]!!.asDouble().toLong().toInt())
    for (st in c.inputs["stems"]!!.asObject()["\$vec"]!!.asArray()) ac.obtainStem(bytesOf(st), clock)
    return acOut(ac)
}

/** The stem dump (AutoCompEnv+8 set order), then the save directory once the save triggers fired. */
private fun acOut(ac: nintype.engine.trie.AutoCompLearn): CaseResult {
    fun date(d: nintype.engine.trie.SDate) = listOf(Leaf.u32(d.sec).toJson(), Leaf.u32(d.usec).toJson())
    val stems = ac.known.map { k ->
        val st = ac.stems[k] ?: return@map Values.struct("stem" to Values.bytesLeaf(k), "cached" to Leaf.bool(false).toJson())
        val cz = st.cancelled
        Values.struct("stem" to Values.bytesLeaf(st.stem), "cached" to Leaf.bool(true).toJson(),
            "onDisk" to Leaf("u8", if (st.onDisk) 1L else 0L).toJson(),
            "cancelled" to Values.vec(listOf(Values.bytesLeaf(cz.word)) + date(cz.date)),
            "locked" to Values.vec(st.locked.map { lw ->
                val raw = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                raw.put(lw.slot.toByte()); raw.put(lw.pad.toByte()); raw.put((lw.pad shr 8).toByte()); raw.put((lw.pad shr 16).toByte())
                raw.putInt(0); raw.putInt(lw.fav); raw.putInt(lw.shortcut)
                Values.struct("word" to Values.bytesLeaf(lw.word), "ts" to Values.bytesLeaf(lw.ts), "raw" to Values.bytesLeaf(raw.array()))
            }),
            "hist" to Values.vec(st.hist.map { h -> Values.struct("word" to Values.bytesLeaf(h.word), "ts" to Values.bytesLeaf(h.ts),
                "sec" to Leaf.u32(h.date.sec).toJson(), "usec" to Leaf.u32(h.date.usec).toJson()) }))
    }
    ac.flush()  // the generator dumps first, then steps 200 frames and lists the directory
    return CaseResult(out = mapOf("stems" to Values.vec(stems), "files" to filesJson(ac.disk)))
}

/** gen_trie_coreccion_roots.py: many roots through the 8 + 4 root cache, with explicit saves. */
private fun coreccionRootsCase(c: VectorCase): CaseResult {
    val store = nintype.engine.trie.CoreccionStore()
    val checks = ArrayList<nintype.engine.vectors.JsonValue>()
    for (op in c.inputs["ops"]!!.asArray().map { it.asObject() }) {
        when (op["op"]!!.asString()) {
            "reg" -> store.registerChainReinforcement(op["words"]!!.asArray().map { hexBytes(it) }, op["n"]!!.asInt())
            "save" -> { val r = hexBytes(op["root"]!!); store.rootIfExistent(r); store.save(r) }
            "check" -> {
                val root = store.rootIfExistent(hexBytes(op["root"]!!))
                if (root == null) checks.add(Values.struct("exists" to Leaf.bool(false).toJson()))
                else {
                    val a = intArrayOf(0); val b = intArrayOf(0)
                    val f = root.check(hexBytes(op["word"]!!), a, b)
                    checks.add(Values.struct("exists" to Leaf.bool(true).toJson(), "found" to Leaf.bool(f).toJson(),
                        "a" to Leaf.u32(a[0]).toJson(), "b" to Leaf.u32(b[0]).toJson()))
                }
            }
        }
    }
    val rootsIn = c.inputs["roots"]!!.asArray().map { hexBytes(it) }
    val res = rootsIn.map { r ->
        val cm = store.rootIfExistent(r)
        if (cm == null) Values.struct("root" to Values.bytesLeaf(r), "exists" to Leaf.bool(false).toJson())
        else Values.struct("root" to Values.bytesLeaf(r), "exists" to Leaf.bool(true).toJson(),
            "entries" to Values.vec(cm.cache.map.values.sortedWith { x, y -> nintype.engine.trie.WordLearningModule.unsignedCompare(x.key.b, y.key.b) }
                .map { el -> Values.struct("key" to Values.bytesLeaf(el.key.b), "a" to Leaf.u32(el.value[0]).toJson(),
                    "b" to Leaf.u32(el.value[1]).toJson(), "usage" to Leaf.u32(el.usage).toJson()) }))
    }
    val cached = store.roots.map.keys.map { it.b }.sortedWith { x, y -> nintype.engine.trie.WordLearningModule.unsignedCompare(x, y) }
    return CaseResult(out = mapOf("checks" to Values.vec(checks), "roots" to Values.vec(res), "cached" to Values.vec(cached.map { Values.bytesLeaf(it) })))
}

/** gen_trie_casing.py: WeirdCaseManager / BadExplicitManager op sequences. */
private fun casingCase(c: VectorCase): CaseResult {
    val lower = { w: ByteArray -> lowerEnv.standardiseToLower(w) }
    val wc = nintype.engine.trie.WeirdCaseManager(lower, (c.inputs["usf121"] as nintype.engine.vectors.JsonValue.JBool).value)
    val be = nintype.engine.trie.BadExplicitManager(lower)
    val res = ArrayList<nintype.engine.vectors.JsonValue>()
    for (op in c.inputs["ops"]!!.asArray().map { it.asObject() }) {
        val w = op["word"]?.let { hexBytes(it) } ?: ByteArray(0)
        fun i(k: String) = op[k]!!.asDouble().toLong().toInt()
        when (op["op"]!!.asString()) {
            "choose" -> wc.onChooseCasing(w)
            "disable" -> wc.disableWeirdCase(w)
            "getwc" -> res.add(Leaf.u32(wc.maybeGetWeirdCase(w)).toJson())
            "convert" -> res.add(Values.bytesLeaf(wc.convertWeirdCase(hexBytes(op["key"]!!), w)))
            "apply" -> res.add(Values.bytesLeaf(wc.applyWeirdCase(w, i("f"))))
            "be_usage" -> be.onUsage(w, i("wa1c"), i("flags"), i("sec"))
            "be_destr" -> be.onDestruction(w, i("wa1c"), i("flags"), i("sec"))
            "be_destrmaybe" -> be.onDestructionMaybe(w, i("sec"))
            "be_get" -> res.add(Leaf.u32(be.maybeGet(w)?.count ?: 0x7fffffff).toJson())
        }
    }
    val files = ArrayList<nintype.engine.vectors.JsonValue>()  // the generator lists weirdcases, then badexplicit
    if (wc.dirty) files.add(Values.struct("name" to Values.bytesLeaf("weirdcases".toByteArray()), "data" to Values.bytesLeaf(wc.binarize())))
    if (be.dirty) files.add(Values.struct("name" to Values.bytesLeaf("badexplicit".toByteArray()), "data" to Values.bytesLeaf(be.binarize())))
    return CaseResult(out = mapOf("results" to Values.vec(res)) + casingMaps(wc, be) + ("files" to Values.vec(files)))
}

private fun casingMaps(wc: nintype.engine.trie.WeirdCaseManager, be: nintype.engine.trie.BadExplicitManager): Map<String, nintype.engine.vectors.JsonValue> {
    val wcm = wc.map.entries.map { (k, v) -> Values.struct("key" to Values.bytesLeaf(k), "casings" to Values.vec(v.casings.map { Leaf.u32(it).toJson() }),
        "active" to Leaf("u8", if (v.active) 1L else 0L).toJson()) }
    val bem = be.map.entries.map { (k, v) -> Values.struct("key" to Values.bytesLeaf(k), "count" to Leaf.u32(v.count).toJson(),
        "usage" to Leaf.u32(v.lastUsage).toJson(), "destr" to Leaf.u32(v.lastDestruction).toJson()) }
    return mapOf("weird" to Values.vec(wcm), "explicit" to Values.vec(bem))
}

/** gen_trie_casing.py's load check: the saved weirdcases / badexplicit files into fresh managers. */
private fun casingLoadCase(c: VectorCase): CaseResult {
    val lower = { w: ByteArray -> lowerEnv.standardiseToLower(w) }
    val wc = nintype.engine.trie.WeirdCaseManager(lower, (c.inputs["usf121"] as nintype.engine.vectors.JsonValue.JBool).value)
    val be = nintype.engine.trie.BadExplicitManager(lower)
    val files = filesOf(c.inputs["files"]!!)
    files["weirdcases"]?.let { wc.load(it) }
    files["badexplicit"]?.let { be.load(it) }
    return CaseResult(out = casingMaps(wc, be))
}

/** gen_trie_chainstore.py: addWordAtEnd(std::string) sequences on a fresh ChainStore. */
private fun chainStoreCase(c: VectorCase): CaseResult {
    val cs = nintype.engine.trie.ChainStore()
    for (w in c.inputs["words"]!!.asArray()) cs.addWordAtEnd(hexBytes(w))
    val metas = cs.metas.entries.sortedBy { it.key }.map { (id, m) ->
        val pos = java.nio.ByteBuffer.allocate(80).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (p in m.pos) pos.putInt(p)
        Values.struct("id" to Leaf.u32(id).toJson(), "word" to Values.bytesLeaf(m.word), "head" to Leaf("u8", m.head.toLong()).toJson(),
            "n" to Leaf("u8", m.n.toLong()).toJson(), "pos" to Values.bytesLeaf(pos.array()), "count" to Leaf.u32(m.count).toJson(),
            "pref" to Leaf.u32(m.pref).toJson())
    }
    fun num(v: Int) = nintype.engine.vectors.JsonValue.JNumber(v.toDouble())
    val sspc = nintype.engine.vectors.JsonValue.JObject(linkedMapOf("blocksize" to num(2000), "pools" to nintype.engine.vectors.JsonValue.JArray(listOf(
        nintype.engine.vectors.JsonValue.JObject(linkedMapOf("count" to num(cs.metas.size), "free" to nintype.engine.vectors.JsonValue.JArray(emptyList())))))))
    val ids = (cs.earliest until cs.currentHistoricalPos()).map { Leaf.u32(cs.wordAtHistpos(it)).toJson() }
    val chain = Values.struct("earliest" to Leaf.u32(cs.earliest).toJson(), "start" to Leaf.u32(cs.start).toJson(), "end" to Leaf.u32(cs.end).toJson(),
        "ids" to Values.vec(ids), "x9bc" to Leaf.u32(cs.saveCounter).toJson())
    return CaseResult(out = mapOf("metas" to Values.vec(metas), "sspc" to sspc, "chain" to chain))
}

/** gen_trie_chaingather.py: ChainEnv::gatherSuggestions over a ChainStore rebuilt from the
 *  addWordAtEnd history, then calculateChainPredScore on the gathered SuggestionsColl. */
private fun chainGatherCase(c: VectorCase): CaseResult {
    val cs = nintype.engine.trie.ChainStore()
    for (w in c.inputs["history"]!!.asArray()) cs.addWordAtEnd(hexBytes(w))
    val coll = nintype.engine.trie.SuggestionsColl()
    val ranker = nintype.engine.trie.ChainNextWordRanker()
    val t = c.inputs["text"]
    val text = if (t == null || t is nintype.engine.vectors.JsonValue.JNull) ByteArray(0) else bytesOf(t)
    nintype.engine.trie.ChainGather.gatherSuggestions(cs, text, coll, ranker)
    val (chains, ranked) = gatherOut(cs, coll, ranker, true)
    val sugg = coll.suggestions(cs)
    val preds = c.outputs["preds"]!!.asObject()["\$vec"]!!.asArray().map { it.asObject() }.map { p ->
        val w = bytesOf(p["word"]!!); val nx = bytesOf(p["next"]!!)
        Values.struct("word" to Values.bytesLeaf(w), "next" to Values.bytesLeaf(nx), "ret" to Leaf.f32(nintype.engine.trie.ChainPred.calculateChainPredScore(sugg, w, nx)).toJson())
    }
    return CaseResult(out = mapOf("chains" to chains, "counters" to Values.vec(listOf(coll.tried, coll.compared, coll.made).map { Leaf.u32(it).toJson() }),
        "ranked" to ranked, "preds" to Values.vec(preds)))
}

private fun gatherOut(cs: nintype.engine.trie.ChainStore, coll: nintype.engine.trie.SuggestionsColl, ranker: nintype.engine.trie.ChainNextWordRanker, withPfc: Boolean): Pair<nintype.engine.vectors.JsonValue, nintype.engine.vectors.JsonValue> {
    val chains = coll.chains.withIndex().sortedBy { it.value.pos }.map { (order, fc) ->
        val f = linkedMapOf("ids" to Values.vec(fc.ids.map { Leaf.u32(it).toJson() }), "words" to Values.vec(fc.ids.map { Values.bytesLeaf(cs.word(it)) }),
            "pos" to Leaf.u32(fc.pos).toJson(), "matched" to Leaf.u32(fc.matched).toJson(), "n20" to Leaf.u32(fc.n20).toJson(),
            "score" to Leaf.f32(fc.score).toJson(), "b28" to Leaf("u8", if (fc.b28) 1 else 0).toJson(), "b29" to Leaf("u8", if (fc.b29) 1 else 0).toJson(),
            "key" to Leaf.f32(fc.key).toJson())
        if (withPfc) { f["pfc"] = Leaf.u32(0).toJson(); f["b14"] = Leaf("u8", if (fc.b14) 1 else 0).toJson() }
        f["order"] = Leaf.u32(order).toJson()
        Values.struct(*f.entries.map { it.key to it.value }.toTypedArray())
    }
    val ranked = ranker.ranked.map { Values.struct("id" to Leaf.u32(it.id).toJson(), "conf" to Leaf.f32(it.conf).toJson(), "weight" to Leaf.f32(it.weight).toJson()) }
    return Pair(Values.vec(chains), Values.vec(ranked))
}

/** gen_trie_nascent.py: inductChainModificationNoOldtext / maybeArchiveOldNascents sessions on a
 *  fresh ChainEnv, then gatherSuggestions. */
private fun nascentCase(c: VectorCase): CaseResult {
    val env = nintype.engine.trie.ChainEnv()
    val added = ArrayList<ByteArray>()
    env.store.onAdd = { added.add(it.copyOf()) }
    val steps = ArrayList<nintype.engine.vectors.JsonValue>()
    for (o in c.inputs["ops"]!!.asObject()["\$vec"]!!.asArray().map { it.asObject() }) {
        added.clear()
        val now = Leaf.fromJson(o["now"]!!).asDouble()
        if (o["op"]!!.asString() == "arch") env.maybeArchiveOldNascents(now)
        else env.inductChainModificationNoOldtext(bytesOf(o["pre"]!!), bytesOf(o["cur"]!!), bytesOf(o["post"]!!), now)
        val ns = env.nascents.map { nc ->
            Values.struct("t" to Leaf.f64(nc.time).toJson(), "f1c" to Leaf("u8", if (nc.f1c) 1 else 0).toJson(), "f1d" to Leaf("u8", if (nc.f1d) 1 else 0).toJson(),
                "els" to Values.vec(nc.els.map { e -> Values.struct("id" to Leaf.u32(e.id).toJson(), "word" to Values.bytesLeaf(e.word),
                    "f8" to Leaf("u8", e.f8.toLong()).toJson(), "f9" to Leaf("u8", e.f9.toLong()).toJson(), "fa" to Leaf("u8", e.fa.toLong()).toJson()) }))
        }
        steps.add(Values.struct("nascents" to Values.vec(ns), "added" to Values.vec(added.map { Values.bytesLeaf(it) })))
    }
    env.store.onAdd = null
    for (w in c.inputs["addedBeforeGather"]!!.asObject()["\$vec"]!!.asArray()) env.store.addWordAtEnd(bytesOf(w))
    val coll = nintype.engine.trie.SuggestionsColl()
    val ranker = nintype.engine.trie.ChainNextWordRanker()
    env.gatherSuggestions(bytesOf(c.inputs["gatherText"]!!), coll, ranker)
    val (chains, ranked) = gatherOut(env.store, coll, ranker, false)
    return CaseResult(out = mapOf("steps" to Values.vec(steps), "gather" to Values.struct("chains" to chains, "ranked" to ranked)))
}

fun registerTriePorts() {
    PortRegistry.register("Emkey::LangEnv::calculateMarkovScoring(Emkey::MarkovArg&)") { c ->
        val ms = listOf(englishMarkov)
        val p2 = bytesOf(c.inputs["prev2"]!!); val p = bytesOf(c.inputs["prev"]!!); val w = bytesOf(c.inputs["word"]!!)
        val inner = nintype.engine.trie.MarkovScoring.inner(ms, p2, p, w)
        CaseResult(out = mapOf("inner" to Leaf.f32(inner).toJson(), "result" to Leaf.f32(nintype.engine.trie.MarkovScoring.score(ms, p2, p, w, c.args[0].asFloat())).toJson()))
    }
    PortRegistry.register("Emkey::LangEnv::getBuiltinChainValue(std::string const&, std::string const&, int&, int&)") { c ->
        val a = intArrayOf(c.args[0].asInt()); val b = intArrayOf(c.args[1].asInt())
        nintype.engine.trie.BuiltInMarkov.getBuiltinChainValue(listOf(englishMarkov), bytesOf(c.inputs["prev"]!!), bytesOf(c.inputs["word"]!!), a, b)
        CaseResult(out = mapOf("a" to Leaf.u32(a[0]).toJson(), "b" to Leaf.u32(b[0]).toJson()))
    }
    PortRegistry.register("Emkey::LangEnv::registerChainReinforcement(std::vector<std::string, std::allocator<std::string>> const&, int)") { c -> coreccionCase(c) }
    PortRegistry.register("Emkey::CoreccionMarkov::reinforce(std::string const&, int, Emkey::LangEnv*)") { c -> coreccionDeepCase(c) }
    PortRegistry.register("Emkey::WordLearningModule::onMerge(std::string const&, bool)") { c -> wordLearnCase(c) }
    PortRegistry.register("Emkey::WordLearningModule::finalizeLoading()") { c -> wordLoadCase(c) }
    PortRegistry.register("Emkey::WordLearningModule::performPruning()") { c -> wordLearnCase(c).let { r -> CaseResult(out = r.out + ("calls" to Values.vec(emptyList()))) } }
    PortRegistry.register("Emkey::AutoCompEnv::onStandardUsage(std::string const&)") { c -> autoCompCase(c) }
    PortRegistry.register("Emkey::AutoCompEnv::obtainStem(std::string const&)") { c -> acLoadCase(c) }
    PortRegistry.register("Shing::Flector::load(void*, std::string const&, int)") { c -> casingLoadCase(c) }
    PortRegistry.register("Emkey::LangEnv::getCoreccionRootIfExistent(std::string const&)") { c -> coreccionRootsCase(c) }
    PortRegistry.register("Emkey::SuggestionsColl::calculateChainPredScore(std::string const&, std::string const&)") { c ->
        val coll = c.inputs["coll"]!!.asArray().map { it.asObject() }.map { o ->
            nintype.engine.trie.ChainSuggestion(o["words"]!!.asArray().map { hexBytes(it) }, Float.fromBits(o["score"]!!.asDouble().toLong().toInt()))
        }
        CaseResult(ret = Leaf.f32(nintype.engine.trie.ChainPred.calculateChainPredScore(coll, bytesOf(c.inputs["word"]!!), bytesOf(c.inputs["next"]!!))))
    }
    PortRegistry.register("Emkey::NascentChain::shouldBeExpired(int)") { c ->
        CaseResult(ret = Leaf.bool(nintype.engine.trie.NascentPolicy.shouldBeExpired(c.args[0].asInt(),
            Leaf.fromJson(c.inputs["now"]!!).asDouble(), Leaf.fromJson(c.inputs["touched"]!!).asDouble())))
    }
    PortRegistry.register("Emkey::NascentChain::canBeArchived()") { c ->
        val fl = c.inputs["flags"]!!.asArray().map { p -> p.asArray().let { it[0].asInt() to it[1].asInt() } }
        CaseResult(ret = Leaf.bool(nintype.engine.trie.NascentPolicy.canBeArchived(fl)))
    }
    PortRegistry.register("Emkey::WeirdCaseManager::onChooseCasing(std::string const&)") { c -> casingCase(c) }
    PortRegistry.register("Emkey::ChainEnv::addWordAtEnd(std::string const&)") { c -> chainStoreCase(c) }
    PortRegistry.register("Emkey::ChainEnv::inductChainModificationNoOldtext(std::string const&, std::string const&, std::string const&)") { c -> nascentCase(c) }
    PortRegistry.register("Emkey::ChainEnv::gatherSuggestions(Emkey::SuggestionsColl&, Emkey::ChainNextWordRanker&)") { c -> chainGatherCase(c) }
    PortRegistry.register("Emkey::ChainEnv::deriveChainlements(std::string const&, std::string const&, std::string const&)") { c ->
        val v = nintype.engine.trie.ChainDerive.deriveChainlements(bytesOf(c.inputs["pre"]!!), bytesOf(c.inputs["cur"]!!), bytesOf(c.inputs["post"]!!))
        val els = v.els.map { el ->
            val raw = java.nio.ByteBuffer.allocate(7).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(el.id).put(el.f8.toByte()).put(el.f9.toByte()).put(el.fa.toByte())
            Values.struct("word" to Values.bytesLeaf(el.word), "raw" to Values.bytesLeaf(raw.array()))
        }
        val f = java.nio.ByteBuffer.allocate(23).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        f.put(if (v.preShort) 1 else 0).put(if (v.postShort) 1 else 0).putInt(v.f2c).putInt(v.f30).putInt(v.f34).putInt(v.f38)
            .put(if (v.f3c) 1 else 0).putInt(v.f40)
        CaseResult(out = mapOf("els" to Values.vec(els), "fields" to Values.bytesLeaf(f.array())))
    }
    PortRegistry.register("Morestring::toWLower(std::string)") { c -> CaseResult(retValue = Values.bytesLeaf(Caps.toWLower(bytesOf(c.inputs["s"]!!)))) }
    PortRegistry.register("Morestring::splitOn(std::string const&, char, std::vector<std::string, std::allocator<std::string>>&)") { c ->
        val parts = nintype.engine.trie.NeoMarkov.splitOn(bytesOf(c.inputs["s"]!!), c.args[0].asInt().toByte())
        CaseResult(out = mapOf("parts" to Values.vec(parts.map { Values.bytesLeaf(it) })))
    }
    PortRegistry.register("Emkey::Moopad::performNeoMarkov(std::vector<Emkey::WordAlternative, std::allocator<Emkey::WordAlternative>>&)") { c ->
        neoMarkovCase(c)
    }
    regI("shing_towupper(int)", Caps::shingTowupper)
    regI("Emkey::turkish_towlower(int)", Caps::turkishTowlower)
    regI("Emkey::turkish_towupper(int)", Caps::turkishTowupper)
    PortRegistry.register("Morestring::toWUpper(std::string)") { c -> CaseResult(retValue = Values.bytesLeaf(Caps.toWUpper(bytesOf(c.inputs["s"]!!)))) }
    PortRegistry.register("Morestring::toWUpperFirstChar(std::string)") { c -> CaseResult(retValue = Values.bytesLeaf(Caps.toWUpperFirstChar(bytesOf(c.inputs["s"]!!)))) }
    PortRegistry.register("Emkey::reflectWordCapsness(std::string&, bool, bool, bool, bool)") { c ->
        val a = c.args.map { it.asBool() }
        CaseResult(out = mapOf("s" to Values.bytesLeaf(Caps.reflectWordCapsness(bytesOf(c.inputs["s"]!!), a[0], a[1], a[2], a[3]))))
    }
    PortRegistry.register("Emkey::reflectWordCapsnessFromWordFlags(std::string&, bool, bool, bool, bool, bool)") { c ->
        val a = c.args.map { it.asBool() }
        CaseResult(out = mapOf("s" to Values.bytesLeaf(Caps.reflectWordCapsnessFromWordFlags(bytesOf(c.inputs["s"]!!), a[0], a[1], a[2], a[3], a[4]))))
    }
    PortRegistry.register("Emkey::LangEnv::shouldSimplyCapitalize(std::string const&)") { c ->
        CaseResult(ret = Leaf.bool(Caps.shouldSimplyCapitalize(bytesOf(c.inputs["s"]!!), capsSettings(c))))
    }
    PortRegistry.register("Emkey::wordObject_spliceCaps(std::string&, bool, bool, bool, bool, bool, bool, Emkey::Moopad*)") { c ->
        val a = c.args.map { it.asBool() }
        CaseResult(out = mapOf("s" to Values.bytesLeaf(Caps.wordObjectSpliceCaps(bytesOf(c.inputs["s"]!!), a[0], a[1], a[2], a[3], a[4], a[5], capsSettings(c)))))
    }
    PortRegistry.register("Emkey::TrieWord::spliceCaps(std::string&, bool, bool, bool, Emkey::Moopad*)") { c ->
        val a = c.args.map { it.asBool() }
        CaseResult(out = mapOf("s" to Values.bytesLeaf(Caps.spliceCaps(leafInt(c.inputs["flags"]!!), bytesOf(c.inputs["s"]!!), a[0], a[1], a[2], capsSettings(c)))))
    }
    PortRegistry.register("Emkey::LangEnv::modifyWordInner(std::string const&, std::function<bool (Emkey::MooWord&, std::string const&)>)") { c ->
        val t = englishPackOnly.copy()
        val env = nintype.engine.trie.LangEnv.applyMoopadDefaults(t)
        for (op in c.inputs["ops"]!!.asObject()["\$vec"]!!.asArray()) {
            val o = op.asObject()
            val w = bytesOf(o["w"]!!)
            when (leafInt(o["op"]!!)) {
                0 -> { val a = o["a"]!!.asObject()["\$vec"]!!.asArray().map { Leaf.fromJson(it).asBool() }; env.modifyWordCaps(w, a[0], a[1], a[2], a[3]) }
                1 -> env.modifyWordLC(w, Leaf.fromJson(o["f"]!!).asFloat())
                else -> env.markMinimumScoreForWord(w, Leaf.fromJson(o["f"]!!).asFloat(), 1)
            }
        }
        val words = java.io.ByteArrayOutputStream()
        fun w32(o: java.io.ByteArrayOutputStream, v: Int) { o.write(v); o.write(v ushr 8); o.write(v ushr 16); o.write(v ushr 24) }
        for (pj in c.inputs["probe"]!!.asObject()["\$vec"]!!.asArray()) {
            val p = bytesOf(pj)
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
        val base = englishBase.second
        val changed = java.io.ByteArrayOutputStream()
        var o = 0
        while (o < now.size) {
            val m = maskedNode(now, o)
            if (o >= base.size || !m.contentEquals(maskedNode(base, o))) { w32(changed, o / 24); changed.write(m) }
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
        CaseResult(out = mapOf("words" to Values.bytesLeaf(words.toByteArray()), "nodes" to Values.bytesLeaf(changed.toByteArray()),
            "sizes" to Values.bytesLeaf(sizes.toByteArray()), "store" to Values.bytesLeaf(st.toByteArray())))
    }
    PortRegistry.register("ptrie_alphalow_indexer(char)") { c -> CaseResult(ret = Leaf.i32(PendingTrieNode.alphalow(c.args[0].asInt()))) }
    PortRegistry.register("Emkey::generate_triestrings(std::string const&, std::vector<std::string, std::allocator<std::string>>&)") { c ->
        CaseResult(out = mapOf("v" to Values.vec(PendingTrie.generateTriestrings(bytesOf(c.inputs["s"]!!)).map { Values.bytesLeaf(it) })))
    }
    PortRegistry.register("Emkey::WordLearningModule::resyncPendingWordsTrie()") { c ->
        val root = PendingTrieNode.create(null, ByteArray(0), 1)
        PendingTrie.resync(root, pendingWords(c.inputs["words"]!!))
        CaseResult(out = mapOf("trie" to Values.bytesLeaf(serializePending(root))))
    }
    PortRegistry.register("Emkey::PendingWordNode::syncPendingWordCompat(ptrie<Emkey::PendingWordNode>*, Emkey::PathSearch*)") { c ->
        val root = PendingTrieNode.create(null, ByteArray(0), 1)
        PendingTrie.resync(root, pendingWords(c.inputs["words"]!!))
        val order = pendingPreorder(root)
        val calls = c.inputs["calls"]!!.asArray().map { row -> row.asArray().map { (it as nintype.engine.vectors.JsonValue.JNumber).value.toLong() } }
        val ps = ReplaySearch(calls, Leaf.fromJson(c.inputs["ps124"]!!).asFloat())
        val seq = c.inputs["seq"]!!.asObject()["\$vec"]!!.asArray().map { leafInt(it) }
        val stamps = c.inputs["stamps"]!!.asObject()["\$vec"]!!.asArray().map { leafInt(it) }
        for ((i, n) in seq.withIndex()) order[n].syncPendingWordCompat(ps, stamps[i])
        CaseResult(out = mapOf("trie" to Values.bytesLeaf(serializePending(root)), "ps124" to Leaf.f32(ps.bestBound).toJson()))
    }

    PortRegistry.register("Emkey::CompactTrie::gatherAutoCompletes(Emkey::PathSearch*)") { c ->
        val table = HashMap<List<Long>, Int>()
        for (row in c.inputs["calls"]!!.asArray()) {
            val r = row.asArray().map { (it as nintype.engine.vectors.JsonValue.JNumber).value.toLong() }
            table[r.subList(0, r.size - 1)] = r.last().toInt()
        }
        val pts = java.io.ByteArrayOutputStream()
        fun w32(v: Int) { pts.write(v); pts.write(v ushr 8); pts.write(v ushr 16); pts.write(v ushr 24) }
        val ps = object : AutocompleteSearch {
            override var visitCount = leafInt(c.inputs["visits"]!!)
            override val acStart = Leaf.fromJson(c.inputs["acStart"]!!).asFloat()
            fun ask(vararg k: Long) = Float.fromBits(table[k.toList()] ?: error("unrecorded query ${k.toList()}"))
            override fun starterCoverage(pos: Int, key: Int) = ask(0, pos.toLong(), key.toLong())
            override fun starterDirectionalCoverage(prev: Int, c: Int, pos: Int) = ask(1, prev.toLong() and 0xffffffffL, c.toLong(), pos.toLong())
            override fun registerAutocompleteNode(p: AutocompleteTriePoint) {
                w32(p.path.size); p.path.forEach { w32(it) }; w32(p.curid); w32(p.node); w32(p.pos); w32(p.score.toRawBits())
            }
        }
        AutoComplete.gather(english, ps)
        CaseResult(out = mapOf("points" to Values.bytesLeaf(pts.toByteArray()), "visits" to Leaf.i32(ps.visitCount).toJson()))
    }

    PortRegistry.register("Emkey::CompactTriePath::syncCompat(Emkey::PathSearch*)") { c ->
        val calls = c.inputs["calls"]!!.asArray().map { row -> row.asArray().map { (it as nintype.engine.vectors.JsonValue.JNumber).value.toLong() } }
        val ps = ReplaySearch(calls, Leaf.fromJson(c.inputs["ps124"]!!).asFloat())
        val map = HashMap<Int, CompactCoverageData>()
        val cov = java.io.ByteArrayOutputStream()
        for (sj in c.inputs["seq"]!!.asObject()["\$vec"]!!.asArray()) {
            val o = sj.asObject()
            val p = CompactTriePath(english, ArrayList(idsOf(bytesOf(o["path"]!!))), leafInt(o["node"]!!), leafInt(o["node"]!!), leafInt(o["pos"]!!))
            Compat.syncCompat(p, ps, map)
            val v = Compat.coverageValue(p, map).toRawBits()
            cov.write(v); cov.write(v ushr 8); cov.write(v ushr 16); cov.write(v ushr 24)
        }
        val mb = java.io.ByteArrayOutputStream()
        for (k in map.keys.sorted()) {
            val d = map[k]!!
            for (v in intArrayOf(k, d.stamp, d.bound.toRawBits(), d.sumCovW.toRawBits(), d.sumW.toRawBits())) {
                mb.write(v); mb.write(v ushr 8); mb.write(v ushr 16); mb.write(v ushr 24)
            }
        }
        CaseResult(out = mapOf("map" to Values.bytesLeaf(mb.toByteArray()), "ps124" to Leaf.f32(ps.bestBound).toJson(),
            "cov" to Values.bytesLeaf(cov.toByteArray())))
    }

    run {
        val none = emptyMap<String, nintype.engine.vectors.JsonValue>()
        val P = "Emkey::CombinedTriePath::"
        regComb(P + "advance(char)") { p, c -> Leaf.bool(p.advance(c.args[0].asInt())) to none }
        regComb(P + "canadvance()") { p, _ -> Leaf.bool(p.canadvance()) to none }
        regComb(P + "canadvance(char)") { p, c -> Leaf.bool(p.canadvance(c.args[0].asInt())) to none }
        regComb(P + "canadvance(char, char)") { p, c -> Leaf.bool(p.canadvance(c.args[0].asInt(), c.args[1].asInt())) to none }
        regComb(P + "canadvance(char const*)") { p, c -> Leaf.bool(p.canadvance(bytesOf(c.inputs["s"]!!))) to none }
        regComb(P + "isvalued()") { p, _ -> Leaf.bool(p.isvalued()) to none }
        regComb(P + "lengthsofar()") { p, _ -> Leaf.i32(p.lengthsofar()) to none }
        regComb(P + "hasCurnode()") { p, _ -> Leaf.bool(p.hasCurnode()) to none }
        regComb(P + "hasMainNode()") { p, _ -> Leaf.bool(p.hasMainNode()) to none }
        regComb(P + "shortestUntilEnder()") { p, _ -> Leaf.i32(p.shortestUntilEnder()) to none }
        regComb(P + "longestPotentialLeft()") { p, _ -> Leaf.i32(p.longestPotentialLeft()) to none }
        PortRegistry.register(P + "pathsofar()") { c ->
            val ctx = PendCtx(c)
            val p = combOf(c.inputs["this"]!!, ctx)
            CaseResult(retValue = Values.bytesLeaf(p.pathsofar()), out = mapOf("this" to combJson(p, ctx)))
        }
        regComb(P + "gatherSeconds(std::set<char, std::less<char>, std::allocator<char>>&)") { p, _ ->
            val s = java.util.TreeSet<Int>(); p.gatherSeconds(s); null to mapOf("set" to setBytes(s))
        }
        regComb(P + "gatherskipadvance(char, std::vector<char, std::allocator<char>>&)") { p, c ->
            val v = ArrayList<Int>(); val r = p.gatherskipadvance(c.args[0].asInt(), v)
            Leaf.bool(r) to mapOf("vec" to Values.bytesLeaf(ByteArray(v.size) { v[it].toByte() }))
        }
        regComb(P + "getPendWords(std::vector<Emkey::PendingWord*, std::allocator<Emkey::PendingWord*>>&)") { p, _ ->
            null to mapOf("ids" to Values.bytesLeaf(idBytes(p.getPendWords().map { it as Int })))
        }
        PortRegistry.register(P + "syncCompat(Emkey::PathSearch*)") { c ->
            val ctx = PendCtx(c)
            english.resetCoverage()
            val p = combOf(c.inputs["this"]!!, ctx)
            val calls = c.inputs["calls"]!!.asArray().map { row -> row.asArray().map { (it as nintype.engine.vectors.JsonValue.JNumber).value.toLong() } }
            val ps = ReplaySearch(calls, Leaf.fromJson(c.inputs["ps124"]!!).asFloat())
            val stamp = leafInt(c.inputs["stamp"]!!)
            p.syncCompat(ps, stamp)
            val cov = p.coverageValue(stamp)
            val cov2 = if (c.note.isNotEmpty()) p.coverageValue(stamp + 1) else cov
            CaseResult(out = mapOf("cov" to Leaf.f32(cov).toJson(), "cov2" to Leaf.f32(cov2).toJson(), "ps124" to Leaf.f32(ps.bestBound).toJson(),
                "this" to combJson(p, ctx)))
        }
        regComb(P + "getTrieWords(std::vector<Emkey::TWRet, std::allocator<Emkey::TWRet>>&, Emkey::Moopad*)") { p, _ ->
            null to mapOf("ids" to Values.bytesLeaf(idBytes(p.getTrieWords())))
        }
        PortRegistry.register(P + "operator<(Emkey::CombinedTriePath const&) const") { c ->
            val ctx = PendCtx(c)
            val a = combOf(c.inputs["this"]!!, ctx)
            val b = combOf(c.inputs["o"]!!, ctx)
            // pointer order of pending nodes: null first; distinct nodes are not generated (heap order)
            CaseResult(ret = Leaf.bool(a.lessThan(b) { x, y ->
                if (x === y) 0 else if (x == null) -1 else if (y == null) 1 else ctx.index(x) - ctx.index(y) }))
        }
    }

    // gen_trie_path.py: node queries (args[0] = node id in the English trie)
    PortRegistry.register("Emkey::CompactTrieNode::canprogress(int, char, Emkey::CTNOCTX&)") { c ->
        CaseResult(ret = Leaf.bool(TrieQuery.canprogress(english, c.args[0].asInt(), c.args[1].asInt(), c.args[2].asInt())))
    }
    PortRegistry.register("Emkey::CompactTrieNode::canprogress(int, char const*, Emkey::CTNOCTX&)") { c ->
        CaseResult(ret = Leaf.bool(TrieQuery.canprogress(english, c.args[0].asInt(), c.args[1].asInt(), bytesOf(c.inputs["s"]!!))))
    }
    PortRegistry.register("Emkey::CompactTrieNode::gatherPossibilitiesAt(int, std::set<char, std::less<char>, std::allocator<char>>&, Emkey::CTNOCTX&)") { c ->
        val s = java.util.TreeSet<Int>()
        TrieQuery.gatherPossibilitiesAt(english, c.args[0].asInt(), c.args[1].asInt(), s)
        CaseResult(out = mapOf("set" to setBytes(s)))
    }
    PortRegistry.register("Emkey::CompactTrieNode::getTrieWords(std::vector<Emkey::TWRet, std::allocator<Emkey::TWRet>>&, Emkey::CTNOCTX&, Emkey::Moopad*)") { c ->
        CaseResult(out = mapOf("ids" to Values.bytesLeaf(idBytes(TrieQuery.getTrieWords(english, c.args[0].asInt())))))
    }
    val none = emptyMap<String, nintype.engine.vectors.JsonValue>()
    regPath("Emkey::CompactTriePath::advance(char)") { p, c -> Leaf.bool(p.advance(c.args[0].asInt())) to none }
    regPath("Emkey::CompactTriePath::canadvance(char)") { p, c -> Leaf.bool(p.canadvance(c.args[0].asInt())) to none }
    regPath("Emkey::CompactTriePath::canadvance()") { p, _ -> Leaf.bool(p.canadvance()) to none }
    regPath("Emkey::CompactTriePath::canadvance(char, char)") { p, c -> Leaf.bool(p.canadvance(c.args[0].asInt(), c.args[1].asInt())) to none }
    regPath("Emkey::CompactTriePath::canadvance(char const*)") { p, c ->
        val s = Values.parseBytes(c.args.let { _ -> c.inputs["s"]?.asObject()?.get("v")?.asString() ?: "0x" })
        Leaf.bool(p.canadvance(s)) to none
    }
    regPath("Emkey::CompactTriePath::isvalued()") { p, _ -> Leaf.bool(p.isvalued()) to none }
    regPath("Emkey::CompactTriePath::shortestUntilEnder()") { p, _ -> Leaf.i32(p.shortestUntilEnder()) to none }
    regPath("Emkey::CompactTriePath::longestPotentialLeft()") { p, _ -> Leaf.i32(p.longestPotentialLeft()) to none }
    regPath("Emkey::CompactTriePath::lengthsofar() const") { p, _ -> Leaf.i32(p.lengthsofar()) to none }
    regPath("Emkey::CompactTriePath::gatherSeconds(std::set<char, std::less<char>, std::allocator<char>>&)") { p, _ ->
        val s = java.util.TreeSet<Int>(); p.gatherSeconds(s); null to mapOf("set" to setBytes(s))
    }
    regPath("Emkey::CompactTriePath::gatherskipadvance(char, std::vector<char, std::allocator<char>>&)") { p, c ->
        val v = ArrayList<Int>(); val r = p.gatherskipadvance(c.args[0].asInt(), v)
        Leaf.bool(r) to mapOf("vec" to Values.bytesLeaf(ByteArray(v.size) { v[it].toByte() }))
    }
    PortRegistry.register("Emkey::CompactTriePath::pathsofar() const") { c ->
        val p = pathOf(c.inputs["this"]!!)
        CaseResult(retValue = Values.bytesLeaf(p.pathsofar()), out = mapOf("this" to pathJson(p)))
    }
    regPath("Emkey::CompactTriePath::getTrieWords(std::vector<Emkey::TWRet, std::allocator<Emkey::TWRet>>&, Emkey::Moopad*)") { p, _ ->
        null to mapOf("ids" to Values.bytesLeaf(idBytes(p.getTrieWords())))
    }
    PortRegistry.register("Emkey::CompactTriePath::operator<(Emkey::CompactTriePath const&) const") { c ->
        CaseResult(ret = Leaf.bool(pathOf(c.inputs["this"]!!).lessThan(pathOf(c.inputs["o"]!!))))
    }

    PortRegistry.register("Emkey::CompactTrie::obtainRawResident(std::string const&, Emkey::LangEnv*, Emkey::ORRCTX&)") { c ->
        val words = c.inputs["words"]!!.asObject()["\$vec"]!!.asArray().map { Values.parseBytes(it.asObject()["v"]!!.asString()) }
        val scores = Values.parseBytes(c.inputs["scores"]!!.asObject()["v"]!!.asString())
        val t = CompactTrie()
        val ids = java.io.ByteArrayOutputStream()
        for ((i, w) in words.withIndex()) {
            val id = t.obtainRawResident(w, scores[i].toInt() and 0xff) ?: error("unported path for ${String(w)}")
            ids.write(id); ids.write(id ushr 8); ids.write(id ushr 16); ids.write(id ushr 24)
        }
        val out = LinkedHashMap<String, nintype.engine.vectors.JsonValue>()
        out["ids"] = Values.bytesLeaf(ids.toByteArray())
        val pools = listOf("nodes" to t.nodes, "child3" to t.child3, "child12" to t.child12, "child20" to t.child20,
            "child64" to t.child64, "nodeval2" to t.nodeval2, "nodeval4" to t.nodeval4, "nodeval20" to t.nodeval20,
            "trieword" to t.trieWords)
        for ((name, s) in pools) {
            val raw = s.dumpBytes()
            out[name] = Values.bytesLeaf(when (name) { "nodes" -> sanitizedNodes(raw); "trieword" -> sanitizedTrieWords(raw); else -> raw })
            out["$name.free"] = Values.bytesLeaf(freeList(s))
        }
        val sp = java.io.ByteArrayOutputStream()
        for (i in t.strings.blocks.indices) sp.write(t.strings.blocks[i], 0, maxOf(t.strings.usedBytes(i), 4))
        out["strpool"] = Values.bytesLeaf(sp.toByteArray())
        CaseResult(out = out)
    }

    regTW("Emkey::TrieWord::getLangflowedScoremult(Emkey::Moopad*)") { t, c ->
        val mp = MemImage.decode(c.inputs["mp"]!!, "mp")
        Leaf.f32(t.langflowedScoremult(mp.i32(0x21bc8), FloatArray(4) { mp.f32(0x21c64 + 4 * it) }))
    }
    regTW("Emkey::TrieWord::isDisabledFCAL(Emkey::Moopad*)") { t, c ->
        Leaf.bool(t.isDisabledFCAL(MemImage.decode(c.inputs["mp"]!!, "mp").i32(0x21bc8)))
    }
    regTW("Emkey::TrieWord::isUnfavored(Emkey::Moopad*)") { t, c ->
        val mp = MemImage.decode(c.inputs["mp"]!!, "mp")
        Leaf.bool(t.isUnfavored(mp.i32(0x21bc8), FloatArray(4) { mp.f32(0x21c64 + 4 * it) }))
    }
    PortRegistry.register("Emkey::TrieWord::getRawWord(Emkey::Moopad*)") { c -> CaseResult(retValue = Values.bytesLeaf(english.getRawWord(c.args[0].asInt()))) }
    PortRegistry.register("Emkey::LangEnv::maybeGetTrieWord(std::string const&)") { c ->
        CaseResult(ret = Leaf.i32(englishEnv.maybeGetTrieWord(bytesOf(c.inputs["word"]!!))))
    }
    regTW("Emkey::TrieWord::hasWildGuess()") { t, _ -> Leaf.bool(t.hasWildGuess()) }
    regTW("Emkey::TrieWord::isFromLangDict()") { t, _ -> Leaf.bool(t.isFromLangDict()) }
    regTW("Emkey::TrieWord::scoringIsLocked()") { t, _ -> Leaf.bool(t.scoringIsLocked()) }
    regTW("Emkey::TrieWord::isSuperExiled()") { t, _ -> Leaf.bool(t.isSuperExiled()) }
    regTW("Emkey::TrieWord::hasNonBasic()") { t, _ -> Leaf.bool(t.hasNonBasic()) }
    regTW("Emkey::TrieWord::hasHangul()") { t, _ -> Leaf.bool(t.hasHangul()) }
    regTW("Emkey::TrieWord::emptyLangIndex()") { t, _ -> Leaf.bool(t.emptyLangIndex()) }
    regTW("Emkey::TrieWord::hasMooWord()") { t, _ -> Leaf.bool(t.hasMooWord()) }
    regTW("Emkey::TrieWord::markHasNonBasic()") { t, _ -> t.markHasNonBasic(); null }
    regTW("Emkey::TrieWord::markHangul()") { t, _ -> t.markHangul(); null }
    regTW("Emkey::TrieWord::getBestScoremult8bit()") { t, _ -> Leaf("u8", t.bestScoremult8bit().toLong()) }
    regTW("Emkey::TrieWord::getBestScoremult()") { t, _ -> Leaf.f32(t.bestScoremult()) }
    regTW("Emkey::TrieWord::getScoremultAtIndex(Emkey::LangOrder)") { t, c -> Leaf.f32(t.scoremultAtIndex(c.args[0].asInt())) }
    regTW("Emkey::TrieWord::isDisabledAtLangIndex(Emkey::LangOrder)") { t, c -> Leaf.bool(t.isDisabledAtLangIndex(c.args[0].asInt())) }
    regTW("Emkey::TrieWord::flagLangIndex(int)") { t, c -> t.flagLangIndex(c.args[0].asInt()); null }
    regTW("Emkey::TrieWord::langIndexIntersectsWith(Shing::Flag32 const&)") { t, c -> Leaf.bool(t.langIndexIntersectsWith(c.args[0].asInt())) }
    PortRegistry.register("Emkey::TrieWord::getCapsBools(bool&, bool&, bool&)") { c ->
        val img = MemImage.decode(c.inputs["this"]!!, "this")
        val (a, b, d) = TrieWord(img.readBytes(0, 16)).capsBools()
        fun u8(v: Boolean) = Leaf("u8", if (v) 1L else 0L).toJson()
        CaseResult(mem = mapOf("this" to img), out = mapOf("caps" to Values.struct("@0x0:a" to u8(a), "@0x1:b" to u8(b), "@0x2:c" to u8(d))))
    }

    // gen_trie_keycode.py
    regI("shing_towlower(int)", Keycode::shingTowlower)
    regI("Emkey::latin_chara_to_keycode(int)", Keycode::latin)
    regI("Emkey::chara_to_keycode_no_altchain(int)", Keycode::noAltchain)
    regB("Emkey::chara_has_altchain(int)", Keycode::hasAltchain)
    regI("Emkey::keycode_to_chara(int)", Keycode::toChara)
    regI("Emkey::triechara_to_keycode(int)", Keycode::trieCharaToKeycode)
    regB("Emkey::keycode_is_valid(int)", Keycode::isValid)
    regB("Emkey::keycode_is_coverable(int)", Keycode::isCoverable)
    regB("Emkey::chara_is_nonbasic(int)", Keycode::isNonbasic)
    PortRegistry.register("Emkey::chara_wildcard_keycode()") { CaseResult(ret = Leaf.i32(Keycode.WILDCARD)) }
    PortRegistry.register("Emkey::chara_to_keycode_basic(int, int&)") { c: VectorCase ->
        val img = MemImage.decode(c.inputs["out"]!!, "out")
        val r = Keycode.basic(c.args[0].asInt())
        if (r != null) img.setI32(0, r)
        CaseResult(ret = Leaf.bool(r != null), mem = mapOf("out" to img))
    }
    PortRegistry.register("Emkey::chara_to_keycode(int, std::string&)") { c: VectorCase ->
        val (k, exp) = Keycode.charaToKeycode(c.args[0].asInt())
        CaseResult(ret = Leaf.i32(k), out = mapOf("s" to Values.bytesLeaf((exp ?: "").toByteArray())))
    }
    PortRegistry.register("Emkey::score_to_triewordscore(float)") { c ->
        CaseResult(ret = Leaf("u8", LangEnv.scoreToTriewordscore(c.args[0].asFloat()).toLong()))
    }
    PortRegistry.register("Emkey::triewordscore_to_score(unsigned char)") { c ->
        CaseResult(ret = Leaf.f32(LangEnv.triewordscoreToScore(c.args[0].asInt())))
    }
    registerTrieLearnPorts()
}
