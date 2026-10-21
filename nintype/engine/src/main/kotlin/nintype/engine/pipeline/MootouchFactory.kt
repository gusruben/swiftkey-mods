package nintype.engine.pipeline

import nintype.engine.math.Point2D
import nintype.engine.touch.Mootouch
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.Values

/**
 * Mootouch construction and Moopad's live-touch map.
 *
 * Emkey::Mootouch::Mootouch() @009626b0 writes only constants ([MootouchCtorTemplate],
 * generated from the emulator) except:
 *  - +0xf8 = Emkey::__recordtime, +0xf0 = RoenGL::globaltimestamp - __recordtime;
 *  - +0xc0 unique id = Emkey::mootouch_counter++ (the counter wraps to 0 once it reaches
 *    100000001);
 *  - +0x26c: low 21 bits cleared, the high 11 kept (whatever the storage held);
 *  - the std::deque at +0x1ec (the pixel-movement frames): an empty [Mootouch.pix];
 *  - the three MooTouchPoint vectors at +0x244/+0x250/+0x25c: empty.
 */
object MootouchFactory {
    const val SIZE = 0x3b8
    private val template: List<Pair<Int, ByteArray>> by lazy {
        MootouchCtorTemplate.SPANS.map { (off, hex) -> off to Values.parseBytes(hex) }
    }

    /** Mootouch() on [storage] (its +0x26c high bits are kept), with the clocks the ctor reads
     * (Emkey::__recordtime, RoenGL::globaltimestamp); returns the new counter. */
    fun construct(storage: MemImage, counter: Int, recordTime: Double, gts: Double): Pair<Mootouch, Int> {
        for ((off, b) in template) storage.writeBytes(off, b)
        storage.setF64(0xf8, recordTime)
        storage.setF64(0xf0, nintype.engine.SoftFloat.dsub(gts, recordTime))
        storage.setI32(0xc0, counter)
        var next = counter + 1
        if (next >= 100000001) next = 0
        storage.setI32(0x26c, storage.i32(0x26c) and 0xffe00000.toInt())
        val mt = Mootouch(storage, listOf(mutableListOf(), mutableListOf(), mutableListOf()), ArrayDeque())
        return mt to next
    }
}

/**
 * Moopad's touch containers (Moopad+0x2942c live `std::map<long pid, Mootouch>`, +0x294c4
 * sitting `std::list<Mootouch>`) and Moopad::obtainTouch(long, Point2D const&) @00684844.
 */
class TouchBook(private val pad: MemImage, var counter: Int, private val clock: EngineClock) {
    val live = java.util.TreeMap<Int, Mootouch>()
    val sitting = ArrayList<Mootouch>()
    /** Moopad+0x294cc vector<Mootouch*>: the sitting touches by start time (+0xf8), rebuilt by
     * resyncSittingTouchesStartBasedSortation. */
    val sittingByStart = ArrayList<Mootouch>()
    /** Moopad+0x29920: the taps sitTouch routes to release handling (space and function-key
     * taps) instead of the sitting list. */
    val tapReleases = ArrayList<Mootouch>()
    private var made = 0

    /** obtainTouch: the live touch for [pid], created (Mootouch(), start position, order index)
     * when missing; then the per-call fields: +0xc4 pid, +0xd0 = Moopad+0x2991c, stock mode
     * (+0xb6 when the Xnl at Moopad+0x29a70 is 1), +0xcd = Xnl Moopad+0x29c28, Xnl Moopad+0x2946c
     * set, Moopad+0x29940 cleared. syncMooTouchForces (pressure UI) is not modelled. */
    fun obtainTouch(pid: Int, startPos: Point2D): Mootouch {
        var mt = live[pid]
        val isNew = mt == null
        if (mt == null) {
            val (m, next) = MootouchFactory.construct(MemImage("mt${made}", MootouchFactory.SIZE), counter, clock.recordTime, clock.gts)
            // the five CabaxHolders' Cabax* (+0x8, 0x14, 0x20, 0x2c, 0x38) are null pointers;
            // the touch port holds them as key indices, -1 for null (MootouchCodec)
            for (h in nintype.engine.touch.MootouchCodec.MT_HOLDERS) m.body.setI32(h + 4, -1)
            counter = next
            mt = m
            live[pid] = m
            made++
        }
        pad.setU8(0x29940, 0)
        mt.body.setI32(0xc4, pid)
        mt.body.setI32(0xd0, pad.i32(0x2991c))
        if (pad.u8(0x29a70 + 0xc) == 1) mt.body.setU8(0xb6, 1)
        mt.body.setU8(0xcd, pad.u8(0x29c28 + 0xc))
        pad.setU8(0x2946c + 0xc, 1)
        if (isNew) {
            mt.body.setF32(0x128, startPos.x); mt.body.setF32(0x12c, startPos.y)
            mt.body.setI32(0xbc, live.size - 1)
            for (o in live.values) if (o !== mt) o.body.setI32(0xbc, o.body.i32(0xbc) + 1)
        }
        pad.setI32(0x29440, live.size)
        return mt
    }
}
