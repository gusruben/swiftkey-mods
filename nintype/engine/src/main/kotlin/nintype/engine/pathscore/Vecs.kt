package nintype.engine.pathscore

import nintype.engine.vectors.JsonValue
import nintype.engine.vectors.MemImage
import nintype.engine.vectors.MemWorld
import java.util.Collections
import java.util.WeakHashMap

/**
 * Element images of the gnustl vectors inside object images. A vector field's live contents
 * arrive in a vector case as `$vec` JSON (MemImage.seqs); [link] turns them into one
 * MemImage per element, with `$ref` pointers resolved against the case's world, and the port
 * code reads them back with [elems]. Keyed by owner image identity.
 */
object Vecs {
    private val store: MutableMap<MemImage, HashMap<Int, MutableList<MemImage>>> =
        Collections.synchronizedMap(WeakHashMap())

    fun put(owner: MemImage, off: Int, elems: MutableList<MemImage>) {
        store.getOrPut(owner) { HashMap() }[off] = elems
    }

    /** The element images of the vector at `off` (decoded without ref linking if [link] was
     * never called for it). */
    fun elems(owner: MemImage, off: Int): MutableList<MemImage> =
        store[owner]?.get(off) ?: owner.seq(off).mapIndexed { i, j -> decode(j, "${owner.name}@${off}[$i]") }.toMutableList()
            .also { put(owner, off, it) }

    fun size(owner: MemImage, off: Int): Int = store[owner]?.get(off)?.size ?: owner.seq(off).size

    private fun decode(j: JsonValue, name: String): MemImage {
        val o = j.asObjectOrNull()
        return if (o == null || o.isEmpty()) MemImage(name) else MemImage.decode(j, name)
    }

    /** Decodes owner's vector at `off` and links element `$ref`s to the world's images. */
    fun link(w: MemWorld, owner: MemImage, off: Int): MutableList<MemImage> {
        val list = owner.seq(off).mapIndexed { i, j -> decode(j, "${owner.name}@${off}[$i]") }.toMutableList()
        for (e in list) for ((o, n) in e.refNames) e.refs[o] = n?.let { w.images[it] ?: throw IllegalArgumentException("unknown \$ref '$it'") }
        put(owner, off, list)
        return list
    }
}
