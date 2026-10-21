package nintype.engine.pipeline

import nintype.engine.merge.MergeBufferUpdate
import nintype.engine.merge.MooMergeBuffer
import nintype.engine.vectors.MemImage

/**
 * Moopad's merge buffers: `std::map<int, Emkey::MooMergeBuffer>` at Moopad+0x29d38 (header
 * +0x29d3c, root +0x29d40, leftmost +0x29d44, count +0x29d4c), keyed by the touch group a
 * buffer starts at (Mootouch +0xd8, see registerSittingTouch). Buffer c holds the word
 * hypotheses that start at group c; MooMergeBuffer::update extends it to the newest group.
 *
 * [MergeSlot.buf] is the 0x44-byte scalar image (merge/MooMergeBuffer.kt has the layout);
 * results / ACCollectages / the score multimap are Kotlin containers. Their element type is
 * the host's PathResult handle ([MergeBufferUpdate.Host]).
 */
class MergeSlot(val key: Int) {
    val buf = MemImage("MooMergeBuffer", MooMergeBuffer.SIZE).also { it.writeBytes(0, ByteArray(MooMergeBuffer.SIZE)) }
    val results = mutableListOf<Any?>()
    val acs = mutableListOf<Any?>()
    val order = mutableListOf<Pair<Float, Int>>()
    fun containers() = MooMergeBuffer.Containers(results, acs, order)
}

/** What the search side supplies for MooMergeBuffer::update: a [MergeBufferUpdate.Host] bound
 * to [slot] (its newACCollectage must append to slot.acs). */
interface MergeHostFactory {
    fun host(slot: MergeSlot): MergeBufferUpdate.Host<Any?>
    /** The ranked candidates (word, PredPath +0x74 final score, +0xfc word prior) that the
     * latest Host.listResults left in the PathSearch result vector. */
    fun lastCandidates(): List<Candidate>
    /** A PathResult handle's fields MooMergeBuffer::getSweepChoice / returnSweepChoice read. */
    fun pathResult(r: Any?): PathResultView =
        throw NotImplementedError("MergeHostFactory.pathResult: PathResult +0xc WordAlternative, +0x5c alternatives, +0x88 time, +0x94 PathResults")
}

/** One PathResult (0xa0 bytes) as the pipeline reads it: [image] with the score at +0x2c (its
 * WordAlternative's +0x20), [wa] its WordAlternative (+0xc), [alts] +0x5c, [time] +0x88 and its
 * own PathResult vector (+0x94) as handles. */
class PathResultView(val image: MemImage, val wa: MemImage, val alts: List<MemImage>, val time: Double, val subResults: List<Any?>)

/** Where the engine finds the search side's factory without a compile-time dependency: an
 * `object nintype.engine.search.PipelineMergeHost : (NintypeEngine) -> MergeHostFactory`,
 * when that class exists. */
object MergeHosts {
    @Suppress("UNCHECKED_CAST")
    val default: ((NintypeEngine) -> MergeHostFactory)? by lazy {
        runCatching {
            Class.forName("nintype.engine.search.PipelineMergeHost").getField("INSTANCE").get(null) as (NintypeEngine) -> MergeHostFactory
        }.getOrNull()
    }
}

/** Forwards to [h]; reports each listResults to [onList] (the engine keeps the first one of
 * the latest MooMergeBuffer::update as its candidates, the oracle driver's definition). */
class ListingHost(private val h: MergeBufferUpdate.Host<Any?>, private val f: MergeHostFactory, private val onList: () -> Unit) :
    MergeBufferUpdate.Host<Any?> by h {
    override fun listResults(n: Int) { h.listResults(n); onList() }
    /** PathSearch::topResult relists (listResults(min(+0x634, 9))) when fewer are ranked; the
     * factory then hands out a new candidate list object, which counts as a listing. */
    override fun topResult(): Any? {
        val before = f.lastCandidates()
        val r = h.topResult()
        if (f.lastCandidates() !== before) onList()
        return r
    }
}

class MergeBuffers {
    val map = java.util.TreeMap<Int, MergeSlot>()

    /** static lastUpdateT of MooMergeBuffer::update (zero-initialised). */
    var lastUpdateT = 0.0

    /** Moopad::obtainMergeBuffer(int) @006d32ec: map[c], constructing and init(c)-ing a new one. */
    fun obtain(c: Int): MergeSlot = map.getOrPut(c) {
        MergeSlot(c).also { MooMergeBuffer.construct(it.buf); MooMergeBuffer.init(it.buf, c) }
    }

    /** MooMergeBuffer::update(last, first, full, Moopad*) through [h]. */
    fun update(s: MergeSlot, last: Int, first: Boolean, full: Boolean, mp: MemImage, now: Double, h: MergeBufferUpdate.Host<Any?>) {
        val st = MergeBufferUpdate.State(s.results, s.acs.size, s.order)
        lastUpdateT = MergeBufferUpdate.update(s.buf, st, last, first, full, mp, now, lastUpdateT, h)
        check(st.acs == s.acs.size) { "host must append one ACCollectage per newACCollectage" }
    }
}
