package app.revanced.patches.swiftkey.misc.backup

import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val BACKUP_RULES = "mods_backup_rules"
private const val EXTRACTION_RULES = "mods_data_extraction_rules"

// Downloaded language packs are large and re-downloadable; Auto Backup skips an app entirely
// once its data passes 25 MB. Learned words (files/language_models/user/dynamic.lm) and
// shared prefs stay in. Temp is scratch space for model merges.
private val excludes = listOf("static_language_models", "language_models/temp")

private fun excludeTags() = excludes.joinToString("\n") { "    <exclude domain=\"file\" path=\"$it\"/>" }

@Suppress("unused")
val enableBackupPatch = resourcePatch(
    name = "Enable backups",
    description = "Lets Android back up settings and learned words (Google backup, device transfer). " +
        "Language packs are excluded and download again after a restore.",
) {
    compatibleWith("com.touchtype.swiftkey")

    apply {
        // API 23 to 30.
        get("res/xml/$BACKUP_RULES.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<full-backup-content>\n${excludeTags()}\n</full-backup-content>\n",
        )
        // API 31+, which ignores fullBackupContent.
        get("res/xml/$EXTRACTION_RULES.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<data-extraction-rules>\n" +
                "  <cloud-backup>\n${excludeTags()}\n  </cloud-backup>\n" +
                "  <device-transfer>\n${excludeTags()}\n  </device-transfer>\n" +
                "</data-extraction-rules>\n",
        )

        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            // The app's own agent (EmptyBackupAgent) backs up nothing. Without an agent,
            // Android falls back to Auto Backup of the whole data dir, minus the rules above.
            application.removeAttribute("android:backupAgent")
            application.setAttribute("android:allowBackup", "true")
            application.setAttribute("android:fullBackupContent", "@xml/$BACKUP_RULES")
            application.setAttribute("android:dataExtractionRules", "@xml/$EXTRACTION_RULES")
        }
    }
}
