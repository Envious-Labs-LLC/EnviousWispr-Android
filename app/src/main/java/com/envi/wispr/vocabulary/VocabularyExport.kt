package com.envi.wispr.vocabulary

/**
 * What an exported word list is called and what it claims to be.
 *
 * Named here rather than at the button, because a file the user keeps for a year and imports on a new
 * phone is a promise about a format, and a promise belongs beside the code that writes it.
 * `VocabularyTransfer` owns the CONTENT; this owns how it arrives on disk.
 */
object VocabularyExport {

    /**
     * `text/plain`, because that is what the file honestly is.
     *
     * `VocabularyTransfer.export` writes a header line and then one tab-separated line per word. It is
     * not JSON and it is not a CSV, and claiming either would make a file picker offer it to apps that
     * would then read it wrongly. Plain text is the truthful answer and it is also the one that lets a
     * user open the file and see their own words.
     */
    const val MIME_TYPE = "text/plain"

    /**
     * The name the save dialog starts with. The user can change it; this only has to be recognisable a
     * year later, next to whatever else is in their Downloads folder.
     */
    const val FILENAME = "enviouswispr-words.txt"

    /**
     * The largest file the importer will read, so a file this writes can always be read back.
     *
     * `VocabularyTransfer.preview` refuses above this, and so does the import reader on the Dictionary
     * screen. Writing more would be writing a file our own importer rejects.
     */
    private const val MAX_IMPORTABLE_BYTES = 2_000_000

    /**
     * The text to write, or a refusal.
     *
     * **"It saved" and "you can get it back" were not the same promise, and this is what makes them
     * one.** `VocabularyTransfer.export` refuses on the term COUNT. `preview`, which is what reads the
     * file back, also refuses on aliases per term, on field lengths, and on the total size. So a list
     * could export happily and then fail to import, and the user would only find out on the day they
     * needed the backup.
     *
     * The check is a real round trip through the real importer: export, read it back, and require that
     * nothing was rejected and that re-exporting what came back is identical. An approximation of the
     * importer's rules here would be a second answer to a question that already has one.
     *
     * Throws with a sentence the user can read. The caller reports it and removes the file.
     */
    fun verifiedPayload(terms: List<CustomTerm>): String {
        val payload = VocabularyTransfer.export(terms)
        val readBack = VocabularyTransfer.preview(payload)
        require(readBack.rejected == 0) {
            "${readBack.rejected} of your words cannot be saved in a file that reads back completely."
        }
        require(VocabularyTransfer.export(readBack.accepted) == payload) {
            "Your words cannot be saved in a file that reads back exactly."
        }
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_IMPORTABLE_BYTES) {
            "Your word list is too large to save in a file this app can read back."
        }
        return payload
    }
}
