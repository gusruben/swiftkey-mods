package nintype.engine.search

import nintype.engine.pathscore.Flag64
import nintype.engine.trie.CombinedTriePath
import nintype.engine.trie.Keycode
import nintype.engine.vectors.MemImage
import java.util.TreeMap
import java.util.TreeSet

/**
 * `Emkey::CanSkipToCache` (0x20 bytes): which chars a cursor may skip to. +0 Shing::Flag64 of
 * keycodes; +8 std::set<int> (header +0xc .. count +0x1c) of the chars whose keycode is not a
 * plain key (invalid or the wildcard).
 */
class CanSkipToCache {
    val flags = MemImage("flags", 8).also { Flag64.init(it, 0) }
    val others = TreeSet<Int>()

    private fun plainKey(c: Int): Int? {
        val k = Keycode.noAltchain(c)
        return if (Keycode.isValid(k) && k != Keycode.WILDCARD) k else null
    }

    /** markPossible(int c) @007d261c. */
    fun markPossible(c: Int) {
        val k = plainKey(c)
        if (k != null) Flag64.flag(flags, 0, k) else others.add(c)
    }

    /** isPossible(int c) @007d2734. */
    fun isPossible(c: Int): Boolean {
        val k = plainKey(c)
        return if (k != null) Flag64.flagged(flags, 0, k) else others.contains(c)
    }
}

/**
 * PathSearch +0xe74: std::map<CombinedTriePath, CanSkipToCache> (CombinedTriePath::operator<;
 * the pending node compares by pointer, and a fresh install has a single pending root).
 */
class SkipCheckerCaches {
    private val pendOrder = { a: nintype.engine.trie.PendingTrieNode?, b: nintype.engine.trie.PendingTrieNode? ->
        if (a === b) 0 else if (a == null) -1 else if (b == null) 1 else System.identityHashCode(a).compareTo(System.identityHashCode(b))
    }
    val map = TreeMap<CombinedTriePath, CanSkipToCache>(Comparator { x, y ->
        if (x.lessThan(y, pendOrder)) -1 else if (y.lessThan(x, pendOrder)) 1 else 0
    })

    /** PathSearch::getSkipCheckerCache(CombinedTriePath&) @007e98d4: the cache for [p], built
     * on first use from CombinedTriePath::gatherSeconds (each char -> markPossible). */
    fun get(p: CombinedTriePath): CanSkipToCache {
        map[p]?.let { return it }
        val key = CombinedTriePath(p.main.copy(), p.pnode, p.ppos, p.pendDead, p.mainDead)
        val c = CanSkipToCache()
        map[key] = c
        val seconds = TreeSet<Int>()
        p.gatherSeconds(seconds)
        for (ch in seconds) c.markPossible(ch and 0xff)
        return c
    }
}
