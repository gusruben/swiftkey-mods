package nintype.engine.pathscore

import nintype.engine.Bits
import nintype.engine.math.CurveMapper
import nintype.engine.vectors.Leaf
import nintype.engine.vectors.MemImage
import java.util.Collections
import java.util.WeakHashMap

/** CurveMapper members of object images. A CurveMapper at `off` arrives as the `$map` of its
 * std::map (at off + 4); built once per image and offset. */
object Curves {
    private val store: MutableMap<MemImage, HashMap<Int, CurveMapper>> = Collections.synchronizedMap(WeakHashMap())

    fun curveMapperAt(img: MemImage, off: Int): CurveMapper = store.getOrPut(img) { HashMap() }.getOrPut(off) {
        val c = CurveMapper()
        for (e in img.seq(off + 4)) {
            val o = e.asObject()
            c.put(Bits.bitsToFloat(Leaf.fromJson(o["first"]!!).asInt()), Bits.bitsToFloat(Leaf.fromJson(o["second"]!!).asInt()))
        }
        c
    }

    fun put(img: MemImage, off: Int, c: CurveMapper) { store.getOrPut(img) { HashMap() }[off] = c }
}
