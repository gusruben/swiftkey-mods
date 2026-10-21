package nintype.engine.pipeline

import nintype.engine.Bits
import nintype.engine.vectors.Json
import nintype.engine.vectors.MemImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * The post-boot engine state NintypeEngine starts from, as dumped by
 * `re/vectors/gen_pipeline.py boot` (re/out/e2e/BOOT.json + moopad_boot.bin): the clocks, the
 * Mootouch id counter, RoenGL's length unit and the raw Moopad image (settings, Xnls, the
 * embedded AutospaceModule; pointer words in it are stale addresses that ports never follow).
 * App start is (1500000000 s, 0 us), the clock the oracle boots with.
 */
class BootState(
    val gts: Double, val prevGts: Double, val lastDt: Double, val lastDtFactor: Double,
    val recordTime: Double, val recordDtf: Double, val recordDts: Double,
    val mootouchCounter: Int, val roenScale: Float, val moopadBytes: ByteArray,
    val emojiExpanderXnl: Int = 0,
    /** Emkey::soundsys_slidedisp(Moopad+0x2d9ec): the slide-sound step (20 without a sound set). */
    val slideDisp: Int = 20,
    val startSec: Int = 1500000000, val startUsec: Int = 0,
    /** The English trie dump directory (re/out/trie/english: the pack plus the default word mods). */
    val trieDir: java.io.File? = null,
    /** The search area's booted PathSearch state (re/out/e2e_search_boot.json), for
     * nintype.engine.search.PipelineMergeHost. */
    val searchBoot: java.io.File? = null,
) {
    /** The englishTrie and the LangEnv learning objects; one per engine (NintypeEngine.langState). */
    fun langState(pad: MemImage, learnedDir: java.io.File? = null): LangState =
        LangState(trieDir ?: error("BootState without a trie directory"), pad, learnedDir)
    fun clock() = EngineClock(startSec, startUsec, gts, prevGts, lastDt, lastDtFactor, recordTime, recordDtf, recordDts)

    fun moopad(): MemImage = MemImage("moopad", moopadBytes.size).also { it.load(0, moopadBytes) }

    companion object {
        fun load(dir: Path = Path.of("../re/out/e2e")): BootState {
            val j = Json.parse(Files.readString(dir.resolve("BOOT.json"))).asObject()
            fun d(k: String) = Bits.bitsToDouble(java.lang.Long.parseUnsignedLong(j[k]!!.asString().removePrefix("0x"), 16))
            return BootState(d("gts"), d("prevGts"), d("ld"), d("ldf"), d("recordtime"), d("recorddtf"), d("recorddts"),
                j["mootouch_counter"]!!.asInt(),
                Bits.bitsToFloat(java.lang.Long.parseLong(j["roenScale"]!!.asString().removePrefix("0x"), 16).toInt()),
                Files.readAllBytes(dir.resolve("moopad_boot.bin")),
                j["emojiExpanderXnl"]?.asInt() ?: 0, j["slideDisp"]?.asInt() ?: 20,
                trieDir = dir.resolve("../trie/english").normalize().toFile().takeIf { it.isDirectory },
                searchBoot = dir.resolve("../e2e_search_boot.json").normalize().toFile().takeIf { it.isFile })
        }
    }
}
