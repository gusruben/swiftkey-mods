package nintype.engine.pathscore

import nintype.engine.SoftFloat.dadd
import nintype.engine.trie.CombinedTriePath
import nintype.engine.trie.CompactTriePath
import nintype.engine.trie.CompactTrie
import nintype.engine.trie.PendingTrieNode
import nintype.engine.vectors.MemImage

/**
 * PredPath constructors. Placement over an existing block: a few bitfield bytes keep their
 * other bits (+0x30 bits 2-7, FingerPathData +0x10 bits 3-7), as in the binary. The Poolable
 * header (+0..+0xb, pool links) and the Shing tracker counts are memory management and not
 * modelled.
 */
object PredPathCtor {
    private val T_M004 = Double.fromBits(0xbfa47ae140000000uL.toLong())   // -0.04

    /** PredPathDataPart::PredPathDataPart() @0081f2ac (at PredPath+0x10). */
    fun dataPart(pp: MemImage) {
        pp.setI32(0x1c, 0)                       // pending finisher
        pp.setI32(0x20, -1); pp.setI32(0x24, 0)  // TWRet()
        pp.setU16(0x28, 0xffff)
        pp.setU8(0x2a, 0); pp.setU8(0x2d, 0); pp.setU8(0x2c, 0); pp.setU8(0x2b, 0); pp.setU8(0x2e, 0)
        val f = ((pp.u8(0x30) shl 8) and 0xfc00)
        pp.setU8(0x2f, f and 0xff); pp.setU8(0x30, f ushr 8)
        pp.setU8(0x31, 0); pp.setU8(0x32, 0); pp.setU8(0x33, 0xff); pp.setU8(0x34, 0)
        pp.setU16(0x36, 0xffff)
        for (o in intArrayOf(0x7c, 0x80, 0x84, 0x88, 0x8c, 0x90)) pp.setI32(o, 0)
        WeightedFloats.init(pp, 0xd8)
        WeightedFloats.init(pp, 0xe4)
        Flag64.init(pp, 0xf0)
        pp.setI32(0x108, 0); pp.setI32(0x10c, 0); pp.setI32(0x110, 0)
        Vecs.put(pp, PredPath.OFF_STEPS, ArrayList())
    }

    private fun fingerData(pp: MemImage, b: Int, finger: Int, t0lo: Int, t0hi: Int) {
        for (o in 0 until 0x10 step 4) pp.setI32(b + o, 0)
        pp.setU8(b + 0x10, pp.u8(b + 0x10) and 0xf8)
        pp.setU8(b + 0x11, finger)
        for (o in 0x18 until 0x28 step 4) pp.setI32(b + o, 0)
        pp.setU8(b + 0x13, 0xff); pp.setU8(b + 0x14, 0xff); pp.setU8(b + 0x15, 0); pp.setU8(b + 0x12, 0xff); pp.setU8(b + 0x16, 0)
        pp.setU8(b + 0x28, 0); pp.setU8(b + 0x29, 0)
        pp.setI32(b + 0x30, t0lo); pp.setI32(b + 0x34, t0hi)
        pp.setI32(b + 0x38, t0lo); pp.setI32(b + 0x3c, t0hi)
        pp.setI32(b + 0x40, 0); pp.setI32(b + 0x44, 0)
        pp.refs.remove(b + 0x40)
    }

    /** PredPath::PredPath(PathSearch*) @0081f39c: a root path. The cursor (+0x14c) is
     * CombinedTriePath(PathSearch*): the dictionary root plus the pending root;
     * `curidLeftover` is the cursor's +0x10, which CompactTrie::path() never writes. */
    fun construct(pp: MemImage, ps: MemImage, trie: CompactTrie, pendRoot: PendingTrieNode, curidLeftover: Int) {
        pp.setI32(0xc, 0)
        dataPart(pp)
        pp.refs[PredPath.OFF_PS] = ps
        val main = CompactTriePath(trie)
        main.curid = curidLeftover
        Ctp.attach(pp, CombinedTriePath(main, pendRoot, 0, false, false))
        val lo = ps.i32(0x168); val hi = ps.i32(0x16c)
        fingerData(pp, PredPath.OFF_FING1, 1, lo, hi)
        fingerData(pp, PredPath.OFF_FING2, 2, lo, hi)
        val id = ps.i32(0x53c)
        ps.setI32(0x53c, id + 1)
        pp.setI32(0x18, id); pp.setI32(0x14, id); pp.setI32(0x10, 0)
        for (o in intArrayOf(0x40, 0x48, 0x50)) { pp.setI32(o, lo); pp.setI32(o + 4, hi) }
        pp.setI32(0x38, 0)
        pp.setF64(0x58, dadd(ps.f64(0x168), T_M004))
        for (o in intArrayOf(0x74, 0x70, 0x6c, 0x68, 0x64, 0x60)) pp.setI32(o, 0)
        for (o in intArrayOf(0x78, 0xb0, 0xb4, 0xb8, 0xbc, 0xc0, 0xc4, 0xc8, 0xcc, 0xd0, 0xd4, 0xe0, 0xec)) pp.setF32(o, 1f)
        pp.setI32(0xf8, 0); pp.refs.remove(0xf8)
        pp.setU8(0x174, 0)
        pp.setF32(0x100, 1f); pp.setF32(0x104, 1f); pp.setF32(0xfc, 1f)
        pp.setI32(0x140, 0); pp.setF32(0x144, 1f)
        for (o in intArrayOf(0xa4, 0x94, 0xa8, 0xac, 0x13c)) pp.setI32(o, 0)
        pp.setF32(0x98, 1f); pp.setF32(0x9c, 1f); pp.setF32(0xa0, 1f)
        pp.setI32(0x138, 0xc2c80000.toInt())     // -100f
        pp.setI32(0x114, 0); pp.setI32(0x118, 0); pp.setU8(0x11c, 0)
        for (o in intArrayOf(0x134, 0x130, 0x12c, 0x128, 0x124, 0x120)) pp.setI32(o, 0)
    }

    private fun copyRange(dst: MemImage, src: MemImage, from: Int, to: Int) {
        dst.writeBytes(from, src.readBytes(from, to - from))
        for (o in src.refs.keys.toList()) if (o in from until to) dst.refs[o] = src.refs[o]
        for (o in src.refNames.keys.toList()) if (o in from until to && !src.refs.containsKey(o)) dst.refNames[o] = src.refNames[o]
    }

    private fun copyImage(src: MemImage, name: String): MemImage {
        val d = MemImage(name, 0x28)
        d.writeBytes(0, src.readBytes(0, 0x28))
        d.refs.putAll(src.refs); d.refNames.putAll(src.refNames)
        return d
    }

    /** PredPath::PredPath(PredPath* parent) @00821394: a child path. Copies the parent's
     * state (cursor, fingers, +0x10..+0x108 data, steps, +0x114..+0x144); parent id (+0x14) =
     * the parent's id (+0x18); own id = PathSearch+0x53c++. */
    fun copyConstruct(pp: MemImage, parent: MemImage) {
        pp.setI32(0xc, 0)
        dataPart(pp)
        val ps = parent.ref(PredPath.OFF_PS)!!
        pp.refs[PredPath.OFF_PS] = ps
        val pc = Ctp.of(parent)
        Ctp.attach(pp, CombinedTriePath(pc.main.copy(), pc.pnode, pc.ppos, pc.pendDead, pc.mainDead))
        copyRange(pp, parent, PredPath.OFF_FING1, PredPath.OFF_FING1 + FingerPathData.SIZE)
        copyRange(pp, parent, PredPath.OFF_FING2, PredPath.OFF_FING2 + FingerPathData.SIZE)
        pp.setU8(0x174, parent.u8(0x174))
        copyRange(pp, parent, 0x10, 0x1c)
        check(parent.i32(0x1c) == 0 && pp.i32(0x1c) == 0) { "pending-word finisher not modelled" }
        copyRange(pp, parent, 0x20, 0x108)
        Vecs.put(pp, PredPath.OFF_STEPS, Vecs.elems(parent, PredPath.OFF_STEPS).mapIndexed { k, e -> copyImage(e, "${pp.name}.steps[$k]") }.toMutableList())
        copyRange(pp, parent, 0x114, 0x148)
        pp.setI32(0x14, parent.i32(0x18))
        val id = ps.i32(0x53c)
        ps.setI32(0x53c, id + 1)
        pp.setI32(0x18, id)
    }
}
