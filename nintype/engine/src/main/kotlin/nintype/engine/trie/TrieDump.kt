package nintype.engine.trie

import nintype.dict.NinDict
import java.io.File

/**
 * Compares a Kotlin-built dictionary with the engine's own, dumped from the booted emulator by
 * re/vectors/gen_trie_dump.py into re/out/trie/<lang>/ (one .bin per pool + meta.json).
 *
 * Masking: a CompactTrieNode's bytes 4..0xB past its label length and its padding 0xD..0xF are not
 * defined by the engine (a fresh node's label bytes come from an uninitialised stack temporary),
 * so they are skipped; every other byte of every pool must match.
 *
 *   ./gradlew run -PmainClass=nintype.engine.trie.TrieDumpKt --args="../re/out/trie/english"
 */
object TrieDump {
    class Result(val lines: List<String>, val mismatches: Int)

    /** The English pack loaded (LangEnv::loadNBIPackFlector), before the default word mods. */
    fun loadEnglish(dir: File): CompactTrie {
        val pack = NinDict.load(File(dir, "english.nbipack").readBytes())
        val trie = CompactTrie()
        val skipped = LangEnv.loadPackWords(trie, pack, 0, english = true)
        check(skipped == 0) { "$skipped words took an unported path" }
        return trie
    }

    /** The dictionary as the booted oracle has it: the pack plus the default word mods. */
    fun buildEnglish(dir: File): CompactTrie {
        val trie = loadEnglish(dir)
        LangEnv.applyMoopadDefaults(trie)
        return trie
    }

    fun compare(trie: CompactTrie, dir: File, maxReport: Int = 20): Result {
        val out = ArrayList<String>()
        var bad = 0
        fun cmp(name: String, mine: ByteArray, elem: Int, mask: ((Int, ByteArray, Int) -> Boolean)?) {
            val ref = File(dir, "$name.bin").readBytes()
            var n = 0
            if (mine.size != ref.size) {
                out.add("$name: size ${mine.size} vs engine ${ref.size} (${mine.size / maxOf(elem, 1)} vs ${ref.size / maxOf(elem, 1)} elements)")
                n++
            }
            val lim = minOf(mine.size, ref.size)
            var reported = 0
            var i = 0
            while (i < lim) {
                if (mine[i] != ref[i] && (mask == null || mask(i % elem, ref, i - i % elem))) {
                    n++
                    if (reported < maxReport) {
                        val e = i / elem
                        val o = e * elem
                        out.add("$name[$e] +0x${(i % elem).toString(16)}: mine ${hex(mine, o, elem)} engine ${hex(ref, o, elem)}")
                        reported++
                    }
                    i = (i / elem + 1) * elem // one report per element
                    continue
                }
                i++
            }
            out.add("$name: ${lim / maxOf(elem, 1)} elements compared, $n mismatching")
            bad += n
        }
        val nodeMask = { f: Int, ref: ByteArray, base: Int ->
            val len = ref[base + 0xc].toInt() and 0xff
            when (f) {
                in 4..0xb -> f - 4 < len
                in 0xd..0xf -> false
                else -> true
            }
        }
        cmp("nodes", trie.nodes.dumpBytes(), CompactTrie.NODE, nodeMask)
        cmp("child3", trie.child3.dumpBytes(), 0x10, null)
        cmp("child12", trie.child12.dumpBytes(), 0x3c, null)
        cmp("child20", trie.child20.dumpBytes(), 0x64, null)
        cmp("child64", trie.child64.dumpBytes(), 0x140, null)
        cmp("nodeval2", trie.nodeval2.dumpBytes(), 8, null)
        cmp("nodeval4", trie.nodeval4.dumpBytes(), 0x10, null)
        cmp("nodeval20", trie.nodeval20.dumpBytes(), 0x50, null)
        // TrieWord +4 bits 0-3 / 25-31: stack garbage in the engine (TrieWord.kt), compared as
        // "the bits flagLangIndex set are set" only
        val twMask = { f: Int, _: ByteArray, _: Int -> f !in 4..7 }
        cmp("trieword", trie.trieWords.dumpBytes(), 16, twMask)
        run {
            val mine = trie.trieWords.dumpBytes()
            val ref = File(dir, "trieword.bin").readBytes()
            var n = 0
            for (o in 0 until minOf(mine.size, ref.size) step 16) {
                val m = CompactTrie.le32(mine, o + 4); val r = CompactTrie.le32(ref, o + 4)
                if ((m and 0x01fffff0) != (r and 0x01fffff0) || (m and 0xf and r.inv()) != 0) n++
            }
            out.add("trieword +4 (MooWord index exact, lang bits subset): $n mismatching")
            bad += n
        }
        val sp = java.io.ByteArrayOutputStream()
        for (i in trie.strings.blocks.indices) sp.write(trie.strings.blocks[i], 0, maxOf(trie.strings.usedBytes(i), 4))
        cmp("strpool", sp.toByteArray(), 1, null)
        return Result(out, bad)
    }

    private fun hex(b: ByteArray, o: Int, n: Int) = (o until minOf(o + n, b.size)).joinToString("") { "%02x".format(b[it].toInt() and 0xff) }
}

fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "../re/out/trie/english" })
    val t0 = System.nanoTime()
    val trie = TrieDump.buildEnglish(dir)
    println("built in ${(System.nanoTime() - t0) / 1_000_000} ms: ${trie.nodes.size} nodes, ${trie.trieWords.size} TrieWords")
    args.getOrNull(1)?.let { o ->
        val od = File(o); od.mkdirs()
        File(od, "nodes.bin").writeBytes(trie.nodes.dumpBytes())
        File(od, "trieword.bin").writeBytes(trie.trieWords.dumpBytes())
    }
    val r = TrieDump.compare(trie, dir)
    r.lines.forEach(::println)
    println(if (r.mismatches == 0) "MATCH" else "MISMATCH (${r.mismatches})")
}
