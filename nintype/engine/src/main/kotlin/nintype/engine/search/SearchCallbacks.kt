package nintype.engine.search

import nintype.engine.math.CurveMapper
import nintype.engine.touch.PlanarCoverage
import nintype.engine.trie.CompatSearch
import nintype.engine.vectors.MemImage

/**
 * PathSearch's side of the trie area's callbacks (trie/Compat.kt `CompatSearch`), over the
 * ported PathSearch functions. The state it needs is the PathSearch image plus the containers
 * those functions take (see PathSearch.kt's field map):
 *
 *  - [important]: the +0x1b0 important points; [singleTouch]: +0x1a0 holds one Mootouch
 *  - [siToTime] / [timeToSi]: the CurveMappers at +0x27c / +0x294
 *  - [nthCache] (+0x3dc) and [slowCache] (+0x3e8): the lazily grown coverage caches
 *  - [nth]: the +0x3d0 rough nth-char coverages; [ender1]: the +0x438 ender coverage
 *  - [travs]: a point's traversed keys (+0x328) as (chara, score)
 *  - [generic]: the +0x1bc points (checkTouchCoverageByLetters walks those)
 *
 * Not yet checked against real decode state: the trie's compat vectors replay recorded answers.
 * Each method is a composition of vector-verified ports.
 */
class SearchCompat(
    val ps: MemImage,
    val important: List<MemImage>,
    val singleTouch: Boolean,
    val siToTime: CurveMapper,
    val timeToSi: CurveMapper,
    val nthCache: MutableList<PlanarCoverage>,
    val slowCache: MutableList<PlanarCoverage>,
    val nth: List<PlanarCoverage>,
    val ender1: PlanarCoverage,
    val travs: (MemImage) -> List<Pair<Int, Float>>,
    val generic: List<MemImage>,
) : CompatSearch {
    override fun nthCharCoverage(n: Int, key: Int): Float =
        PathSearch.getNthCharPlanarCoverage(nthCache, important, siToTime, timeToSi, n, travs).getCoverage(key)

    override fun onlyHasASingleTouch(): Boolean = singleTouch

    override fun slowSlideCoverage(n: Int, key: Int): Float =
        PathSearch.getSlowSlideCoverage(slowCache, singleTouch, important.firstOrNull(), n, travs).getCoverage(key)

    override fun checkTrieNodeLengthMultSatisfaction(len: Int, thr: Float): Boolean =
        PathSearch.checkTrieNodeLengthMultSatisfaction(ps, len, thr)

    override fun checkCoverage(a: Int, b: Int, c: Int, key: Int): Float = PathSearch.checkCoverage(ps, nth, a, b, c, key)

    override fun checkTouchCoverageByLetters(word: ByteArray): Float = TravCoverage.checkTouchCoverageByLetters(ps, generic, travs, word)

    override fun checkTrieNodeEnderMult(len: Int, key: Int): Float = PathSearch.checkTrieNodeEnderMult(ps, ender1, len, key)

    /** PathSearch+0x124. */
    override var bestBound: Float
        get() = ps.f32(0x124)
        set(v) = ps.setF32(0x124, v)
}

/**
 * PathSearch as trie.AutoComplete's callback (CompactTrie::gatherAutoCompletes): +0x50c is the
 * visit counter, +0x14c the autocomplete start, [nodes] the +0x500 vector that
 * registerAutocompleteNode appends to. [starter] is getAutocompleteStarterCoverage(pos);
 * [directional] is the board-directional coverage value of getAutocompleteStarterDirectionalCoverage.
 */
class AcSearch(
    val ps: MemImage,
    val nodes: MutableList<nintype.engine.trie.AutocompleteTriePoint>,
    val starter: (Int) -> nintype.engine.touch.PlanarCoverage,
    val directional: (prev: Int, c: Int, pos: Int) -> Float,
) : nintype.engine.trie.AutocompleteSearch {
    override var visitCount: Int
        get() = ps.i32(0x50c)
        set(v) = ps.setI32(0x50c, v)
    override val acStart: Float get() = ps.f32(0x14c)
    override fun starterCoverage(pos: Int, key: Int): Float = starter(pos).getCoverage(key)
    override fun starterDirectionalCoverage(prev: Int, c: Int, pos: Int): Float = directional(prev, c, pos)
    /** registerAutocompleteNode(AutocompleteTriePoint const&) @007e26d0: push_back of a copy. */
    override fun registerAutocompleteNode(p: nintype.engine.trie.AutocompleteTriePoint) {
        nodes.add(nintype.engine.trie.AutocompleteTriePoint(p.path.toList(), p.curid, p.node, p.pos, p.score))
    }
}
