package nintype.engine.merge

import nintype.engine.vectors.MemImage

/**
 * `Emkey::ACCollectage` (0xe4 bytes): the autocomplete candidates gathered for one merge-buffer
 * result. Known fields: +0x4 std::string current starter, +0x8 i32 its length, +0xc
 * std::vector<ACCollectageSlot> (16-byte slots, +0xc u8 open), +0x48 multimap<float,
 * ACCollectedWord>. Only the leaves are ported here; collect / slotify / filtering wait on
 * AutoCompEnv and the trie.
 */
object ACCollectage {
    /** closeSlotsBesidesZero() @0106e904: slot k's +0xc = (k == 0). [slots] are the slot images. */
    fun closeSlotsBesidesZero(slots: List<MemImage>) {
        for ((k, s) in slots.withIndex()) s.setU8(0xc, if (k == 0) 1 else 0)
    }

    /** markCurrentStarter(std::string const& s) @0106d1dc: starter = s; +0x8 = s.length() (bytes).
     * Returns the new starter (the std::string at +4 is modelled by its bytes). */
    fun markCurrentStarter(ac: MemImage, s: ByteArray): ByteArray {
        ac.setI32(0x8, s.size)
        return s.copyOf()
    }
}

/** `Emkey::ACCollectedWord` (0x28 bytes): the std::string word at +0x0 and the plain data at
 * +0x4..+0x28 (kept as raw bytes until its fields are needed; ctor: +4 = -1, +0x10 = 1.0,
 * +0x20 = 1.0, the rest 0). */
class ACWord(var text: ByteArray, val rest: ByteArray) {
    fun copy() = ACWord(text.copyOf(), rest.copyOf())
}

/** `Emkey::ACCollectageSlot` (16 bytes): vector<ACCollectedWord> + u8 open (+0xc). */
class ACSlot(val words: MutableList<ACWord> = mutableListOf(), var open: Int = 0) {
    fun copy() = ACSlot(words.mapTo(mutableListOf()) { it.copy() }, open)
}

/**
 * `Emkey::ACCollectage` as a value (layout in re/vectors/merge_ext.py: starter string +0x4,
 * its length +0x8, slots +0xc, two set<string> +0x18 / +0x30, multimap<float, word> +0x48,
 * +0x60, three deque<word> +0x64 / +0x8c / +0xb4, +0xdc, +0xe0). The Moopad* at +0x0 is the
 * [mp] image the gating Xnls are read from.
 */
class ACModel(var mp: MemImage?) {
    var starter = ByteArray(0)
    var starterLen = 0
    val slots = mutableListOf<ACSlot>()
    val set18 = mutableListOf<ByteArray>()
    val set30 = mutableListOf<ByteArray>()
    val byScore = mutableListOf<Pair<Float, ACWord>>()
    var f60 = 0
    val dq64 = mutableListOf<ACWord>()
    val dq8c = mutableListOf<ACWord>()
    val dqb4 = mutableListOf<ACWord>()
    var fdc = 0
    var fe0 = 0

    /** ACCollectage(Moopad*) @0106c084: empty containers, then reset(). */
    fun construct(moopad: MemImage) { mp = moopad; reset() }

    /** reset() @0106c478: starter = "", 7 empty closed slots, then open by Moopad Xnls:
     * 0xff0 -> slots 0..3, 0x28ac8 -> slot 0, 0x1f7d0 -> slots 0, 1, 0x9798 -> slots 4..6;
     * the deques, the multimap and both sets emptied; +0x8, +0x60, +0xdc, +0xe0 = 0. */
    fun reset() {
        starter = ByteArray(0)
        slots.clear()
        repeat(7) { slots.add(ACSlot()) }
        val m = mp ?: error("ACCollectage: no Moopad")
        if (Xnl.val_(m, 0xff0) == 1) for (i in 0..3) slots[i].open = 1
        if (Xnl.val_(m, 0x28ac8) == 1) slots[0].open = 1
        if (Xnl.val_(m, 0x1f7d0) == 1) { slots[0].open = 1; slots[1].open = 1 }
        if (Xnl.val_(m, 0x9798) == 1) for (i in 4..6) slots[i].open = 1
        dq64.clear(); dq8c.clear(); byScore.clear(); dqb4.clear(); set18.clear(); set30.clear()
        starterLen = 0; f60 = 0; fdc = 0; fe0 = 0
    }

    /** operator=(ACCollectage const&) @0089eb88: a member-wise deep copy (Moopad* included). */
    fun assign(o: ACModel) {
        if (o === this) return
        mp = o.mp; starter = o.starter.copyOf(); starterLen = o.starterLen
        slots.clear(); o.slots.mapTo(slots) { it.copy() }
        set18.clear(); o.set18.mapTo(set18) { it.copyOf() }
        set30.clear(); o.set30.mapTo(set30) { it.copyOf() }
        byScore.clear(); o.byScore.mapTo(byScore) { (k, w) -> Pair(k, w.copy()) }
        f60 = o.f60
        dq64.clear(); o.dq64.mapTo(dq64) { it.copy() }
        dq8c.clear(); o.dq8c.mapTo(dq8c) { it.copy() }
        dqb4.clear(); o.dqb4.mapTo(dqb4) { it.copy() }
        fdc = o.fdc; fe0 = o.fe0
    }

    /** isEmpty() @0106c960: every slot's word list is empty (true with no slots). */
    fun isEmpty(): Boolean = slots.all { it.words.isEmpty() }

    /** hasLeftCandidates() @0106cab0: slot 1 non-empty, else (with >= 4 slots) slot 3 non-empty. */
    fun hasLeftCandidates(): Boolean {
        if (slots.size < 2) return false
        if (slots[1].words.isNotEmpty()) return true
        if (slots.size < 4) return false
        return slots[3].words.isNotEmpty()
    }

    /** hasRightCandidates() @0106cb04: slot 0 non-empty, else (with >= 3 slots) slot 2 non-empty. */
    fun hasRightCandidates(): Boolean {
        if (slots.isNotEmpty() && slots[0].words.isNotEmpty()) return true
        if (slots.size < 3) return false
        return slots[2].words.isNotEmpty()
    }
}
