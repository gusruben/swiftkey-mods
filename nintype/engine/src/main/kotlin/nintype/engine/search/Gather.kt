package nintype.engine.search

import nintype.engine.Bits
import nintype.engine.SoftFloat.d2f
import nintype.engine.SoftFloat.dcmplt
import nintype.engine.SoftFloat.dsub
import nintype.engine.SoftFloat.fadd
import nintype.engine.SoftFloat.fcmplt
import nintype.engine.SoftFloat.fdiv
import nintype.engine.SoftFloat.fmul
import nintype.engine.math.CurveMapper
import nintype.engine.math.Point2D
import nintype.engine.touch.PlanarCoverage
import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.MemWorld
import nintype.engine.vectors.Values

/**
 * The search's object world as resetEx builds it (the gen_search_e2e.py layout): named images
 * in a [MemWorld] ("ps", "mp", Mootouches, MooTouchPoints, TravKeyDatas), pointer vectors as
 * `$vec`s of `{"$ref": name}`, raw-element vectors as `{"@0x0:raw", "@0x<k>:r"}`, the points'
 * PlanarCoverage vectors (+0xa0 / +0xac) in the search vectors' PlanarCoverage JSON, curves as
 * `{"@0x4:map"}` structs.
 */
class SearchWorld(val w: MemWorld) {
    val ps: MemImage get() = w["ps"]
    val mp: MemImage get() = w["mp"]
    fun refs(img: MemImage, off: Int): List<MemImage> = img.seq(off).map { w[it["\$ref"]!!.asString()] }
    fun setRefs(img: MemImage, off: Int, l: List<MemImage>) { img.seqs[off] = l.map { Values.ref(it.name) }.toMutableList() }
    fun add(img: MemImage): MemImage { w.images[img.name] = img; return img }

    fun pcs(p: MemImage, off: Int): MutableList<PlanarCoverage> = p.seq(off).map { pcOf(it) }.toMutableList()
    fun setPcs(p: MemImage, off: Int, l: List<PlanarCoverage>) { p.seqs[off] = l.map { pcJson(it) }.toMutableList() }

    fun nexts(p: MemImage, off: Int): MutableList<NextStep> = p.seq(off).map { j ->
        val e = MemImage.decode(j)
        NextStep(e.i32(0), w[(e.refs[4]?.name ?: e.refNames[4])!!], w[(e.refs[8]?.name ?: e.refNames[8])!!])
    }.toMutableList()
    fun setNexts(p: MemImage, off: Int, l: List<NextStep>) {
        p.seqs[off] = l.map { s ->
            val b = ByteArray(12); for (i in 0 until 4) b[i] = (s.key ushr (8 * i)).toByte()
            Values.struct("@0x0:raw" to Values.bytesLeaf(b), "@0x4:r" to Values.ref(s.p.name), "@0x8:r" to Values.ref(s.n.name))
        }.toMutableList()
    }

    companion object {
        fun pcOf(j: JsonValue): PlanarCoverage {
            val pc = PlanarCoverage(areas = j["@0x14:areas"]!!["\$vec"]!!.asArray().map { Leaf.fromJson(it).asInt() }.toMutableList())
            pc.tMin = Leaf.fromJson(j["@0x0:tMin"]!!).asDouble(); pc.tMax = Leaf.fromJson(j["@0x8:tMax"]!!).asDouble()
            pc.hasTime = Leaf.fromJson(j["@0x10:hasTime"]!!).asInt(); pc.maxCoverage = Leaf.fromJson(j["@0x20:maxCoverage"]!!).asFloat()
            return pc
        }
        fun pcJson(pc: PlanarCoverage): JsonValue = Values.struct(
            "@0x0:tMin" to Leaf.f64(pc.tMin).toJson(), "@0x8:tMax" to Leaf.f64(pc.tMax).toJson(),
            "@0x10:hasTime" to Leaf("u8", pc.hasTime.toLong() and 0xFF).toJson(),
            "@0x20:maxCoverage" to Leaf.f32(pc.maxCoverage).toJson(),
            "@0x14:areas" to Values.vec(pc.areas.map { Leaf("f32", it.toLong() and 0xFFFFFFFFL).toJson() }))
        fun cmOf(j: JsonValue): CurveMapper {
            val c = CurveMapper()
            for (p in j["@0x4:map"]!!["\$map"]!!.asArray()) c.put(Leaf.fromJson(p["first"]!!).asFloat(), Leaf.fromJson(p["second"]!!).asFloat())
            return c
        }
        fun cmJson(c: CurveMapper): JsonValue = Values.struct("@0x4:map" to Values.map((0 until c.size).map {
            Values.struct("first" to Leaf("f32", c.keyBits(it).toLong() and 0xFFFFFFFFL).toJson(),
                "second" to Leaf("f32", c.valueBits(it).toLong() and 0xFFFFFFFFL).toJson())
        }))
    }
}

/** What gatherTouches reads from outside the PathSearch: the keyboard (visitation points'
 * coverage, simple-tap test), RoenGL's scale, and syncBaseTimes' text context. */
class GatherEnv(val model: nintype.engine.geometry.KeyboardModel, val roenScale: Float,
                val lastChar: Int? = null, val ctxOverride: Boolean = false, val tempAutospace: Boolean = false)

object Gather {
    val CURVE_OFFS = intArrayOf(0x27c, 0x294, 0x2ac, 0x2c4, 0x2dc, 0x2f4, 0x30c)
    private fun f(i: Long) = Bits.bitsToFloat(i.toInt())

    /** Mootouch::Mootouch(Mootouch const&) @0072b16c in the world layout: the body and every
     * point (and each point's TravKeyData) copied into new images named after [name]; pointer
     * fields keep pointing where the source's do. */
    fun copyMootouch(sw: SearchWorld, src: MemImage, name: String): MemImage {
        fun copyImg(s: MemImage, n: String, size: Int): MemImage {
            val d = MemImage(n, size)
            d.writeBytes(0, s.readBytes(0, size))
            d.refs.putAll(s.refs); d.refNames.putAll(s.refNames)
            for ((o, l) in s.seqs) d.seqs[o] = l.toMutableList()
            for ((o, c) in s.ptrs) d.ptrs[o] = c
            return d
        }
        val m = sw.add(copyImg(src, name, 0x3b8))
        for ((v, off) in intArrayOf(0x244, 0x250, 0x25c).withIndex()) {
            val pts = sw.refs(src, off).mapIndexed { j, p ->
                val np = sw.add(copyImg(p, "$name.$v.$j", 1000))
                val ts = sw.refs(p, 0x328).mapIndexed { i, t -> sw.add(copyImg(t, "${np.name}.t$i", 0x20)) }
                sw.setRefs(np, 0x328, ts)
                np
            }
            sw.setRefs(m, off, pts)
        }
        return m
    }

    /** Mootouch::findImportantPointClosestTo(MooTouchPoint* p) @009542c8: over the visitation
     * drift points (+0x34d or +0x34c), the first v with p.t(+0x120) < v.t + 0.01; with an earlier
     * one prev, prev when 0.6 * d(p, prev) / (d(p, prev) + d(p, v)) + 0.4 * dt(p, prev) /
     * (dt(p, prev) + dt(p, v)) < 0.5 (d over +0xdc, dt = |difference of +0x128|), else v; without
     * such a v the last visitation point (null without any). */
    fun findImportantPointClosestTo(pts: List<MemImage>, p: MemImage): MemImage? {
        val d001 = Bits.bitsToDouble(0x3f847ae140000000L)
        var prev: MemImage? = null
        for (q in pts) {
            if (q.u8(0x34d) == 0 && q.u8(0x34c) == 0) continue
            val t = nintype.engine.SoftFloat.dadd(q.f64(0x120), d001)
            val pt = p.f64(0x120)
            if (prev == null) {
                if (dcmplt(pt, t)) return q
                prev = q; continue
            }
            if (nintype.engine.SoftFloat.dcmpge(pt, t) || nintype.engine.SoftFloat.dcmpun(pt, t)) { prev = q; continue }
            fun pos(x: MemImage) = Point2D(x.f32(0xdc), x.f32(0xe0))
            val lp = pos(p).lengthFrom(pos(prev)); val lq = pos(p).lengthFrom(pos(q))
            val tp = d2f(Math.abs(dsub(p.f64(0x128), prev.f64(0x128))))
            val tq = d2f(Math.abs(dsub(p.f64(0x128), q.f64(0x128))))
            val s = fadd(fmul(fdiv(lp, fadd(lp, lq)), f(0x3f19999a)), fmul(fdiv(tp, fadd(tp, tq)), f(0x3ecccccd)))
            return if (fcmplt(s, 0.5f)) prev else q
        }
        return prev
    }

    /** syncVisitationPoint via the pipeline's port (on touch's Mootouch class): the corner-kink
     * points' travs go in as raw TravKeyData structs, +0xc / +0x10 come back as drift-point
     * indices and become refs. */
    fun syncVisitationPoint(sw: SearchWorld, mt: MemImage, env: GatherEnv) {
        val pts = sw.refs(mt, 0x244).toMutableList()
        val ck = sw.refs(mt, 0x250).toMutableList()
        val all = pts + ck
        val travImgs = all.associateWith { sw.refs(it, 0x328) }
        val saved = all.associateWith { it.seqs[0x328] }
        for (p in all) p.seqs[0x328] = travImgs[p]!!.map { Values.struct("@0x0" to Values.bytesLeaf(it.readBytes(0, 0x20))) }.toMutableList()
        val t = nintype.engine.touch.Mootouch(mt, listOf(pts, ck, sw.refs(mt, 0x25c).toMutableList()), ArrayDeque())
        nintype.engine.pipeline.VisitationPoint.sync(t, env.model)
        for (p in all) {
            val ts = travImgs[p]!!
            for ((k, j) in p.seq(0x328).withIndex()) ts[k].writeBytes(0, MemImage.decode(j).readBytes(0, 0x20))
            p.seqs[0x328] = saved[p]!!
        }
        for (c in ck) for (o in intArrayOf(0xc, 0x10)) {
            val i = c.i32(o)
            if (i >= 0) c.refs[o] = pts[i] else { c.refs.remove(o); c.refNames.remove(o) }
        }
    }

    /** gatherTouches() @007d8294. [sitting] = the Moopad's sitting touches in list order. */
    fun gatherTouches(sw: SearchWorld, sitting: List<MemImage>, env: GatherEnv, curves: MutableMap<Int, CurveMapper>) {
        val ps = sw.ps
        val mp = sw.mp
        for (o in intArrayOf(0x1b0, 0x1bc, 0x1e0, 0x1ec, 0x1c8, 0x1d4, 0x228)) ps.seqs[o] = mutableListOf()
        for (o in intArrayOf(0x200, 0x218, 0x23c, 0x254, 0x26c)) { ps.setI32(o, 0); ps.setI32(o + 0xc, 0) }
        ps.setI32(0x348, 0)
        ps.setU8(0x1ac, mp.u8(0x294d8 + 0xc))
        val mts = ArrayList<MemImage>()
        val lo = ps.i32(0x194); val hi = ps.i32(0x198)
        for (s in sitting) {
            if (lo <= hi && (s.i32(0xd8) < lo || hi < s.i32(0xd8))) continue
            mts.add(copyMootouch(sw, s, "mt${mts.size}"))
        }
        when (ps.i32(0x114)) {
            2 -> {
                val drop = (ps.u8(0x11c) xor 1) + ps.i32(0x118)
                val keep = mts.size - drop
                check(keep >= 1) { "resetEx mode 2 keeps no touch (the binary asserts)" }
                val kept = mts.subList(drop, mts.size).toList()
                mts.clear(); mts.addAll(kept)
            }
            1 -> { val n = ps.i32(0x118); check(n <= mts.size) { "mode 1 extends with default Mootouches: not modelled" }; while (mts.size > n) mts.removeAt(mts.size - 1) }
        }
        sw.setRefs(ps, 0x1a0, mts)
        PathSearch.syncTouchIslandness(ps, mts)
        fun byId(id: Int) = sitting.firstOrNull { it.i32(0xc0) == id }
        PathSearch.syncTouchLRNess(mts, mts.map { sw.refs(it, 0x244) }, mp.i32(0x1f490)) { byId(it) }
        val important = ArrayList<MemImage>()
        for ((i, m) in mts.withIndex()) {
            PointerMesh.syncPointerMesh(m) { mm, o -> sw.refs(mm, o) }
            m.setI32(0xc8, i)
            for (p in sw.refs(m, 0x244)) {
                p.setU8(0x21c, m.u8(0xec))
                if (nintype.engine.emkey.MooTouchPoint.isVisitationPoint(p)) important.add(p)
            }
            for (p in sw.refs(m, 0x250)) { p.setU8(0x21c, m.u8(0xec)); important.add(p) }
        }
        if (important.isNotEmpty()) nintype.engine.pathscore.StlSort.sort(important) { a, b -> dcmplt(a.f64(0x128), b.f64(0x128)) }
        val generic = ArrayList<MemImage>(); val f1 = ArrayList<MemImage>(); val f0 = ArrayList<MemImage>()
        val e1 = ArrayList<MemImage>(); val e2 = ArrayList<MemImage>()
        var g = 0; var c1 = 0; var c2 = 0
        for ((i, q) in important.withIndex()) {
            q.setI32(0x308, g); q.setI32(0x30c, i)
            val fing = q.u8(0x21c)
            if (fing == 2) {
                q.setI32(0x310, c2)
                if (nintype.engine.emkey.MooTouchPoint.isVisitationPoint(q)) { f0.add(q); c2++ }
                e2.add(q)
                ps.setI32(0x224, ps.i32(0x224) + 1)
            } else if (fing <= 1) {
                q.setI32(0x310, c1)
                if (nintype.engine.emkey.MooTouchPoint.isVisitationPoint(q)) { f1.add(q); c1++ }
                e1.add(q)
                ps.setI32(0x20c, ps.i32(0x20c) + 1)
            }
            if (nintype.engine.emkey.MooTouchPoint.isVisitationPoint(q)) {
                generic.add(q); g++
                if (q.u8(0x34d) != 0) ps.setI32(0x348, ps.i32(0x348) + 1)
            }
        }
        fun link(l: List<MemImage>, next: Int, prev: Int) {
            for (k in 1 until l.size) { l[k - 1].refs[next] = l[k]; l[k].refs[prev] = l[k - 1] }
        }
        link(important, 0x30, 0x2c); link(generic, 0x20, 0x1c); link(e1, 0x38, 0x34); link(e2, 0x38, 0x34)
        link(f1, 0x28, 0x24); link(f0, 0x28, 0x24)
        sw.setRefs(ps, 0x1b0, important); sw.setRefs(ps, 0x1bc, generic); sw.setRefs(ps, 0x1c8, f1); sw.setRefs(ps, 0x1d4, f0)
        sw.setRefs(ps, 0x1e0, e1); sw.setRefs(ps, 0x1ec, e2)
        for (m in mts) {
            syncVisitationPoint(sw, m, env)
            val pts = sw.refs(m, 0x244)
            for (p in pts) if (!nintype.engine.emkey.MooTouchPoint.isVisitationPoint(p)) p.setI32(0x304, findImportantPointClosestTo(pts, p)!!.i32(0x308))
            for (p in sw.refs(m, 0x250)) p.setI32(0x304, findImportantPointClosestTo(pts, p)!!.i32(0x308))
        }
        BaseTimes.syncBaseTimes(ps, mts, { m, o -> sw.refs(m, o) }, env.lastChar, env.ctxOverride, env.tempAutospace)
        PathSearch.syncDoubleLetterAllowances(generic, mts, mts.map { sw.refs(it, 0x244) }, mp.u8(0x2b578 + 0xc), mp.u8(0x2b544 + 0xc)) { id ->
            byId(id)?.let { it to sw.refs(it, 0x244) }
        }
        var simple = true
        if (mts.isNotEmpty()) {
            var n = 0
            for (m in mts) {
                val t = nintype.engine.touch.Mootouch(m, listOf(sw.refs(m, 0x244).toMutableList(), sw.refs(m, 0x250).toMutableList(),
                    sw.refs(m, 0x25c).toMutableList()), ArrayDeque())
                if (!t.isSimpleTap(nintype.engine.touch.TouchEnv(env.model, mp, 0.0, env.roenScale))) n++
            }
            simple = n == 0
        }
        ps.setU8(0x35a, if (simple) 1 else 0)
        ps.setF32(0x360, if (simple) f(0x4019999a) else 1f)
        ps.setF32(0x364, if (simple) f(0x4079999a) else 1f)
        ForceAC.syncForceACPoints(ps, generic, mp.u8(0x28ac8 + 0xc), mp.f32(0x1f4a8))
        PathSearch.syncFingerSpecifForelookAllowances(f1)
        PathSearch.syncFingerSpecifForelookAllowances(f0)
        PathSearch.syncOtherFingIndexes(important, 1)
        PathSearch.syncOtherFingIndexes(important, 2)
        PathSearch.syncGenericForelookAllowances(ps, generic, f1, f0)
        PathSearch.syncCKPointSoftIndexes(important)
        for (o in CURVE_OFFS) curves.getOrPut(o) { CurveMapper() }
        PathSearch.syncSoftIndexToTimeCurvemap(ps, generic, f1, f0, mts.size == 1, curves)
        for (o in CURVE_OFFS) {
            val j = SearchWorld.cmJson(curves[o]!!)
            ps.seqs[o + 4] = j["@0x4:map"]!!["\$map"]!!.asArray().toMutableList()
            ps.setI32(o + 0x14, curves[o]!!.size)
        }
        val lists = HashMap<Pair<MemImage, Int>, MutableList<NextStep>>()
        NextSteps.syncNextStepPoints(mts, { m, o -> sw.refs(m, o) }, { p -> sw.refs(p, 0x328) }) { p, o -> lists.getOrPut(p to o) { sw.nexts(p, o) } }
        for ((k, l) in lists) sw.setNexts(k.first, k.second, l)
        val rank = HashMap<MemImage, Int>()
        for (m in mts) for (p in sw.refs(m, 0x244)) rank[p] = rank.size
        for (m in mts) SlideHist.syncSlideVSHist(m, mts, { mm, o -> sw.refs(mm, o) }, mp.f32(0x1f4ac), { rank[it] ?: Int.MAX_VALUE }) { sw.w[it] }
        for (p in important) {
            val own = sw.pcs(p, 0xa0); val other = sw.pcs(p, 0xac)
            FuturePlanar.syncFuturePlanarCoverage(p, own, other) { q -> sw.refs(q, 0x328) }
            sw.setPcs(p, 0xa0, own); sw.setPcs(p, 0xac, other)
        }
        for (q in important) {
            if (q.u8(0x21c) == 1) ps.setI32(0x260, ps.i32(0x260) + 1) else ps.setI32(0x278, ps.i32(0x278) + 1)
            ps.setI32(0x248, ps.i32(0x248) + 1)
        }
        val span = Bits.bitsToFloat(Bits.floatToBits(d2f(dsub(ps.f64(0x178), ps.f64(0x168)))) and 0x7fffffff)
        ps.setF32(0x344, fdiv(fmul(fdiv(fadd(ps.f32(0x148), 1f), fadd(span, f(0x3e19999a))), 60f), 5f))
        Overlaps.syncOverlaps(ps, mts) { m, o -> sw.refs(m, o) }
        PathSearch.syncWordMultness(ps, mts.size == 1)
    }
}
