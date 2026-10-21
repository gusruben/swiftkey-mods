package nintype.engine.pathscore

import nintype.engine.math.Point2D
import nintype.engine.vectors.MemImage

private fun MemImage.pt(o: Int) = Point2D(f32(o), f32(o + 4))
private fun MemImage.setPt(o: Int, p: Point2D) { setF32(o, p.x); setF32(o + 4, p.y) }

/** `Emkey::SlideVSArg` (0x7c bytes, see LAYOUT.md). */
object SlideVSArg {
    const val SIZE = 0x7c

    /** SlideVSArg() @0081a68c. */
    fun init(a: MemImage) {
        a.setU8(1, 0); a.setU8(0, 0)
        a.setI32(0x48, 0); a.setI32(0x4c, 0); a.setU8(0x50, 0)
        a.writeBytes(4, ByteArray(0x40))
        a.writeBytes(0x58, ByteArray(0x22))
        a.setF32(0x54, 1f); a.setF32(0x6c, 1f); a.setF32(0x70, 1f)
    }

    /** markKeyDiff(char c1, char c2, Point2D const& a, Point2D const& b) @0081a6e8:
     * stores both chars and key centres, keyDiff (+0x3c) = b - a. */
    fun markKeyDiff(arg: MemImage, c1: Int, c2: Int, a: Point2D, b: Point2D) {
        arg.setU8(0, c1); arg.setU8(1, c2)
        arg.setPt(0x2c, a); arg.setPt(0x34, b)
        arg.setPt(0x3c, b - a)
    }
}

/** `Emkey::determineSlideDiffs` @0081f000 -> (outA, outB). Each output is p1 - p0 or
 * p2 - p0 depending on the three flags (exactly the compiled branch structure; bools are 0/1). */
fun determineSlideDiffs(b0: Boolean, b1: Boolean, p0: Point2D, b2: Boolean, p1: Point2D, p2: Point2D): Pair<Point2D, Point2D> {
    if (b1 && b2) { val d = p1 - p0; return d to d }
    if (!b1 && !b2) return (p1 - p0) to (p2 - p0)
    if (!b1) { val d = p1 - p0; return d to d }          // b1 = 0, b2 = 1
    if (b0) return (p1 - p0) to (p2 - p0)                // b1 = 1, b2 = 0
    val d = p1 - p0
    return d to d
}

/** `Emkey::FingerPathData` (0x48 bytes, embedded twice in PredPath at 0x178 / 0x1c0). Functions
 * take the image holding it and its base offset. */
object FingerPathData {
    const val SIZE = 0x48

    /** calcSlideDiff(FingerPathData const& o, Point2D const& pt, SlideVSArg& arg) const @0081f1bc. */
    fun calcSlideDiff(t: MemImage, tb: Int, o: MemImage, ob: Int, pt: Point2D, arg: MemImage) {
        val p0 = t.pt(tb + 0x20)
        arg.setPt(4, p0)
        val (a, b) = determineSlideDiffs(t.u8(tb + 0x13) != o.u8(ob + 0x13), (t.u8(tb + 0x10) and 2) != 0, p0,
            (o.u8(ob + 0x10) and 2) != 0, o.pt(ob + 0x20), pt)
        arg.setPt(0x1c, a); arg.setPt(0x24, b)
        arg.setPt(0xc, arg.pt(4) + arg.pt(0x1c))
        arg.setPt(0x14, arg.pt(4) + arg.pt(0x24))
    }
}
