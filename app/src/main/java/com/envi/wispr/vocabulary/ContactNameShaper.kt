package com.envi.wispr.vocabulary

import java.util.Locale

/** Raw contact name fields read on-device from [android.provider.ContactsContract]. */
internal data class ContactCandidateName(
    val contactId: String,
    val given: String?,
    val family: String?,
)

/**
 * Preview of an add-only Contacts import (#32).
 *
 * [newContactCount] counts distinct contacts contributing at least one new term so UI copy reads
 * in people/names consistently, while [preview] holds the deduplicated single-token [CustomTerm]s
 * ready for `CustomTermRepository.applyImport`.
 */
internal data class ContactsImportPlan(
    val preview: ImportPreview,
    val totalContactsScanned: Int,
    val newContactCount: Int,
    val alreadyPresentCount: Int,
    val truncatedByCapacity: Int = 0,
    val atCapacity: Boolean = false,
) {
    val newTermCount: Int get() = preview.accepted.size

    companion object {
        val EMPTY = ContactsImportPlan(
            preview = ImportPreview(emptyList(), emptyList(), 0, false),
            totalContactsScanned = 0,
            newContactCount = 0,
            alreadyPresentCount = 0,
        )
    }
}

/**
 * Shapes raw contact given/family names into distinctive single-token custom vocabulary entries,
 * rejecting multi-word phrases, punctuated initials ("J.", "Will."), digit strings, emails, and
 * common English words that also happen to be names ("Will", "May", "Grace", etc.).
 */
internal object ContactNameShaper {
    private const val MIN_LONE_TOKEN_LENGTH = 2
    private const val MAX_TOKEN_LENGTH = 200
    private const val DEFAULT_MAX_TERMS = 2_000
    private const val IMPORTED_CONTACT_PRIORITY = -10
    private const val CONTACT_CATEGORY = "Contact"

    /**
     * Whether [token] is ONE Unicode name token bounded by letters (allowing Unicode combining
     * marks such as matras or NFD accents attached to letters, plus internal apostrophes and
     * hyphens such as "O'Brien", "Jean-Luc", "आशा", or NFD "José"; rejecting phrases, emails,
     * digits, or leading/trailing punctuation such as "J." or "Will.").
     */
    fun isNameLike(token: String?): Boolean {
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_TOKEN_LENGTH) return false
        var index = 0
        var prevJoiner = false
        var hasBaseLetter = false
        var lastWasJoiner = false
        while (index < trimmed.length) {
            val cp = trimmed.codePointAt(index)
            val charCount = Character.charCount(cp)
            val nextIndex = index + charCount
            val isLetter = Character.isLetter(cp)
            val type = Character.getType(cp)
            val isCombiningMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            val isJoiner = cp == '\''.code || cp == '\u2019'.code || cp == '-'.code || cp == '\u2010'.code
            when {
                isLetter -> {
                    hasBaseLetter = true
                    prevJoiner = false
                    lastWasJoiner = false
                }
                isCombiningMark -> {
                    // Marks must attach to a preceding letter, never start a token or follow a joiner.
                    if (!hasBaseLetter || prevJoiner) return false
                    lastWasJoiner = false
                }
                isJoiner -> {
                    if (!hasBaseLetter || nextIndex >= trimmed.length || prevJoiner) return false
                    prevJoiner = true
                    lastWasJoiner = true
                }
                else -> return false
            }
            index = nextIndex
        }
        return hasBaseLetter && !lastWasJoiner
    }

    /** Whether [token] is a distinctive standalone given or family name safe to auto-correct. */
    fun isDistinctiveLoneToken(token: String?): Boolean {
        val trimmed = token?.trim().orEmpty()
        if (!isNameLike(trimmed) || trimmed.length < MIN_LONE_TOKEN_LENGTH) return false
        return !commonWordStoplist.contains(trimmed.lowercase(Locale.ROOT))
    }

    /**
     * Shapes [candidates] into an add-only [ContactsImportPlan] against [existing] terms.
     * Existing terms are never overwritten (`replaceCollisions = false`), and imported terms get
     * lower priority ([IMPORTED_CONTACT_PRIORITY]) than user-entered terms (priority `0`).
     */
    fun planImport(
        candidates: List<ContactCandidateName>,
        existing: List<CustomTerm>,
        maxTotalTerms: Int = DEFAULT_MAX_TERMS,
    ): ContactsImportPlan {
        val existingLower = existing.mapTo(HashSet()) { it.spelling.trim().lowercase(Locale.ROOT) }
        val seenNewLower = HashSet<String>()
        val seenTruncatedLower = HashSet<String>()
        val accepted = mutableListOf<CustomTerm>()
        val collisions = mutableListOf<CustomTerm>()
        val seenCollisionLower = HashSet<String>()
        val remainingCapacity = (maxTotalTerms - existing.size).coerceAtLeast(0)
        var truncatedByCapacity = 0

        // Merge linked StructuredName rows by contactId so one person is counted once even when
        // multiple rows exist for the same contact. Blank IDs remain distinct per row.
        val grouped = LinkedHashMap<String, MutableList<ContactCandidateName>>()
        var anonymousIndex = 0
        for (candidate in candidates) {
            val key = if (candidate.contactId.isNotBlank()) "id:${candidate.contactId}" else "anon:${anonymousIndex++}"
            grouped.getOrPut(key) { mutableListOf() }.add(candidate)
        }

        var newContacts = 0
        var alreadyPresentContacts = 0
        var distinctiveContacts = 0

        for ((_, rowsForContact) in grouped) {
            val seenWithinContact = HashSet<String>()
            val uniqueTokens = mutableListOf<String>()
            for (row in rowsForContact) {
                listOf(row.given, row.family).forEach { raw ->
                    val cleaned = raw?.trim()
                    if (cleaned != null && isDistinctiveLoneToken(cleaned)) {
                        val lower = cleaned.lowercase(Locale.ROOT)
                        if (seenWithinContact.add(lower)) {
                            uniqueTokens += cleaned
                        }
                    }
                }
            }
            if (uniqueTokens.isEmpty()) continue
            distinctiveContacts += 1

            var contactContributedNew = false
            var contactHadCollision = false
            for (token in uniqueTokens) {
                val lower = token.lowercase(Locale.ROOT)
                val term = CustomTerm(
                    spelling = token,
                    category = CONTACT_CATEGORY,
                    priority = IMPORTED_CONTACT_PRIORITY,
                    imported = true,
                )
                if (lower in existingLower) {
                    contactHadCollision = true
                    if (seenCollisionLower.add(lower)) collisions += term
                } else if (lower !in seenNewLower) {
                    if (accepted.size < remainingCapacity) {
                        seenNewLower += lower
                        accepted += term
                        contactContributedNew = true
                    } else if (seenTruncatedLower.add(lower)) {
                        truncatedByCapacity += 1
                    }
                }
            }
            if (contactContributedNew) {
                newContacts += 1
            } else if (contactHadCollision) {
                alreadyPresentContacts += 1
            }
        }

        return ContactsImportPlan(
            preview = ImportPreview(
                accepted = accepted,
                collisions = collisions,
                rejected = 0,
                replaceCollisions = false,
            ),
            totalContactsScanned = grouped.size,
            newContactCount = newContacts,
            alreadyPresentCount = alreadyPresentContacts,
            truncatedByCapacity = truncatedByCapacity,
            atCapacity = remainingCapacity == 0 && truncatedByCapacity > 0,
        )
    }

    /** Formats plain confirmation summary text matching macOS copy without misleading empty states. */
    fun confirmationSummary(plan: ContactsImportPlan): String {
        if (plan.totalContactsScanned == 0) {
            return "No contacts were found on this phone."
        }
        if (plan.atCapacity && plan.newTermCount == 0) {
            return "Your word list is already at the 2,000-word limit."
        }
        val newNames = plan.newContactCount
        val existingNames = plan.alreadyPresentCount
        val head = when {
            newNames == 0 && existingNames > 0 -> return "All eligible names are already in your word list."
            newNames == 0 -> return "No new distinctive names were found to import."
            newNames == 1 -> "We'll add 1 name from your contacts"
            else -> "We'll add $newNames names from your contacts"
        }
        val tail = when (existingNames) {
            0 -> "."
            1 -> ". 1 is already in your list."
            else -> ". $existingNames are already in your list."
        }
        val capNote = if (plan.truncatedByCapacity > 0) {
            " (${plan.truncatedByCapacity} skipped at the 2,000-word limit.)"
        } else {
            ""
        }
        return "$head$tail$capNote"
    }

    // Mirrors macOS ContactNameShaper.commonWordStoplist so importing common nouns/verbs never
    // capitalizes or alters ordinary spoken words.
    private val commonWordStoplist: Set<String> = setOf(
        "will", "may", "mark", "bill", "drew", "rose", "grant", "chase", "chance",
        "hope", "grace", "faith", "joy", "art", "guy", "frank", "rich", "wade",
        "reed", "reid", "lance", "miles", "jack", "pat", "ray", "dean", "dale",
        "summer", "autumn", "april", "june", "august", "dawn", "noel",
        "holly", "ivy", "lily", "daisy", "violet", "iris", "fern", "olive",
        "sage", "basil", "rosemary", "ginger", "jasmine", "poppy", "heather",
        "willow", "hazel", "laurel", "myrtle", "berry", "cherry", "plum",
        "pearl", "ruby", "amber", "crystal", "jade", "opal", "coral", "gem",
        "robin", "jay", "wren", "dove", "lark", "drake", "fox", "wolf", "bear",
        "hawk", "finch", "raven", "swift", "bird",
        "star", "sky", "rain", "storm", "river", "brook", "lake", "bay", "ocean",
        "sea", "stone", "wood", "woods", "cliff", "glen", "ford", "heath", "vale",
        "field", "fields", "hill", "hills", "lane", "park", "parks", "meadow",
        "gray", "grey", "brown", "white", "black", "green",
        "king", "knight", "baker", "cook", "hunter", "fisher", "carter", "potter",
        "porter", "mason", "marshall", "marshal", "sawyer", "archer", "chandler",
        "fletcher", "weaver", "tanner", "shepherd", "gardener", "page", "abbott",
        "bishop", "major", "sterling", "noble", "earl", "duke", "bell",
        "honey", "candy", "sugar", "sunny", "sandy", "misty", "stormy", "rocky",
        "buddy", "sonny", "merry", "melody", "harmony", "bliss",
        "an", "the", "is", "it", "in", "on", "of", "to", "at", "as", "be", "by",
        "do", "go", "he", "if", "me", "my", "no", "or", "so", "up", "us", "we",
    )
}
