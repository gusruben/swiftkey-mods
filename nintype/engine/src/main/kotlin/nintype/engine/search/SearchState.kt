package nintype.engine.search

import nintype.engine.SoftFloat.fcmple
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fcmpun
import nintype.engine.vectors.MemImage
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * The search driver's containers around the PathSearch byte image [ps]. PredPaths are the
 * pathscore area's MemImages; a Refcounter<PredPath> is a plain reference (refcounts at
 * PredPath +0xc are not modelled: the JVM collects paths, and no decode-visible state
 * depends on when the binary frees one).
 *
 *   buckets[k]   QueueBucket at +0x540 + 0x34 k: [Bucket.queue] = +0xc heap, [Bucket.best] = +0x1c
 *                heap (both pathpointer_comparer max-heaps by score); allowance +8 and push
 *                counter +0x30 stay in [ps]
 *   bestPath     +0x610 best path of pathsize >= 2;  bestByLength +0x614 (index = word length - 1)
 *   traceHits    +0x9d0 paths whose word matched the Moopad debug trace word ([traceWord], +0x333ec)
 *   stepStats    +0xea8, with each element's +0x3c heap and +0x4c word counts in [statsHeap]/[statsCounts]
 *
 * [word] / [wordLength] are PredPath::getWord() / getWordLength() (pathscore/PredPathTrie.kt).
 */
class SearchState(
    val ps: MemImage,
    val word: (MemImage) -> ByteArray,
    val wordLength: (MemImage) -> Int,
    val traceWord: ByteArray = ByteArray(0),
    /** Moopad +0x333e0 (result tracing on) and +0x333e8 (the word regResult traces). */
    val resultTraceOn: Boolean = false,
    val resultTraceWord: ByteArray = ByteArray(0),
    /** PredPath::getUnAutoAposWord() (@008239f8: the step chars, see [unAutoAposWordOf]). */
    val unAutoAposWord: (MemImage) -> ByteArray = { error("unAutoAposWord not supplied") },
    /** PredPath::determineFinalScore() (pathscore; rewrites the score +0x74). */
    val determineFinalScore: (MemImage) -> Unit = { error("determineFinalScore not supplied") },
) {
    class Bucket(val queue: MutableList<MemImage> = ArrayList(), val best: MutableList<MemImage> = ArrayList())

    val buckets = Array(4) { Bucket() }
    var bestPath: MemImage? = null
    val bestByLength = ArrayList<MemImage?>()
    val traceHits = ArrayList<MemImage>()
    val stepStats = ArrayList<MemImage>()
    /** +0x620: std::map<std::string, Refcounter<PredPath>>, each word's best finished path. */
    val results = TreeMap<StrKey, MemImage>()
    /** +0x4b0: ptrie<AccessageData> word dedup, word -> {done (u8 +0), count (i16 +2)}. Only
     * get-or-insert is ever used on it, so a map with default-zero values is equivalent. */
    val accessage = TreeMap<StrKey, IntArray>()
    /** +0xe90: std::map<std::string, ArrivalPair> per-word arrivals (step statistics). */
    val arrivals = TreeMap<StrKey, ArrivalPair>()
    class ArrivalPair(var name: ByteArray = ByteArray(0), var maxLen: Int = 0, val list: MutableList<IntArray> = ArrayList())
    /** +0x638: the ranked result list built by listResults. */
    val resultList = ArrayList<MemImage>()
    val statsHeap = IdentityHashMap<MemImage, MutableList<MemImage>>()
    val statsCounts = IdentityHashMap<MemImage, TreeMap<StrKey, Int>>()

    fun heapOf(s: MemImage) = statsHeap.getOrPut(s) { ArrayList() }
    /** A step stats' +0x78 list of stepped paths. */
    val statsPendingMap = IdentityHashMap<MemImage, MutableList<MemImage>>()
    fun statsPending(s: MemImage) = statsPendingMap.getOrPut(s) { ArrayList() }
    fun countsOf(s: MemImage) = statsCounts.getOrPut(s) { TreeMap() }

    companion object {
        /** PredPath::getUnAutoAposWord() @008239f8: the chars (+0xc) of the path's steps
         * (vector +0x108, 0x28 bytes each), in order: the word as typed, without auto apostrophes. */
        fun unAutoAposWordOf(stepChars: List<Int>): ByteArray = ByteArray(stepChars.size) { stepChars[it].toByte() }

        /** pathpointer_comparer: `a.score < b.score` (PredPath +0x74): a max-heap. */
        val BY_SCORE = { a: MemImage, b: MemImage -> fcmplt(a.f32(0x74), b.f32(0x74)) }

        /** `!(a.score <= b.score)`: the strict-improvement test pushQueue uses for its best slots. */
        fun beats(a: MemImage, b: MemImage) = !(fcmple(a.f32(0x74), b.f32(0x74)) || fcmpun(a.f32(0x74), b.f32(0x74)))
    }
}
