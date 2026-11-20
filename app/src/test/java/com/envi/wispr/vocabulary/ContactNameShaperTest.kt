package com.envi.wispr.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME (#32): Contacts import adds distinctive single-token names add-only, rejects
 * multi-word phrases, initials, emails, digits, and common English words, groups linked contact
 * rows per person, surfaces accurate empty/full/duplicate summaries, and aligns privacy copy.
 */
class ContactNameShaperTest {

    @Test
    fun distinctiveSingleTokensPassWhilePhrasesPunctuatedInitialsAndEmailsFail() {
        assertTrue(ContactNameShaper.isDistinctiveLoneToken("Ramachandran"))
        assertTrue(ContactNameShaper.isDistinctiveLoneToken("O'Brien"))
        assertTrue(ContactNameShaper.isDistinctiveLoneToken("Jean-Luc"))
        assertTrue(ContactNameShaper.isDistinctiveLoneToken("Nguyễn"))
        assertTrue(ContactNameShaper.isDistinctiveLoneToken("आशा"))
        assertTrue(ContactNameShaper.isDistinctiveLoneToken("Jose\u0301"))

        assertFalse("single-letter initial dropped", ContactNameShaper.isDistinctiveLoneToken("J"))
        assertFalse("trailing joiner dropped", ContactNameShaper.isDistinctiveLoneToken("Jean-"))
        assertFalse("trailing combining mark alone dropped", ContactNameShaper.isDistinctiveLoneToken("\u0301"))
        assertFalse("punctuated initial dropped", ContactNameShaper.isDistinctiveLoneToken("J."))
        assertFalse("multi-word phrase dropped", ContactNameShaper.isDistinctiveLoneToken("Mary Jane"))
        assertFalse("punctuated common word dropped", ContactNameShaper.isDistinctiveLoneToken("Will."))
        assertFalse("email dropped", ContactNameShaper.isDistinctiveLoneToken("user@example.com"))
        assertFalse("digit junk dropped", ContactNameShaper.isDistinctiveLoneToken("Alice555"))
        assertFalse("leading joiner dropped", ContactNameShaper.isDistinctiveLoneToken("-O'Brien"))
        assertFalse("common word dropped", ContactNameShaper.isDistinctiveLoneToken("Will"))
        assertFalse("common word case-insensitive", ContactNameShaper.isDistinctiveLoneToken("GRACE"))
        assertFalse("blank dropped", ContactNameShaper.isDistinctiveLoneToken("   "))
    }

    @Test
    fun planImportMergesLinkedRowsPerContactIdAndDeduplicatesWordsSafely() {
        val existing = listOf(CustomTerm(spelling = "saurabh", priority = 0))
        val candidates = listOf(
            // Linked StructuredName rows with the same contactId count as one person.
            ContactCandidateName("1", "Saurabh", "Vaish"),
            ContactCandidateName("1", "Saurabh", "V."),
            ContactCandidateName("2", "Will", "Grant"),
            ContactCandidateName("3", "Aarav", "Chatterjee"),
            ContactCandidateName("4", "aarav", "vaish"),
            ContactCandidateName("5", "Priya", "user@example.com"),
        )
        val plan = ContactNameShaper.planImport(candidates, existing)

        assertEquals(listOf("Vaish", "Aarav", "Chatterjee", "Priya"), plan.preview.accepted.map { it.spelling })
        assertFalse("add-only import never replaces collisions", plan.preview.replaceCollisions)
        assertTrue("imported terms sort below user priority 0", plan.preview.accepted.all { it.priority < 0 && it.imported })
        assertEquals(3, plan.newContactCount)
        assertEquals(0, plan.alreadyPresentCount)
        assertEquals("We'll add 3 names from your contacts.", ContactNameShaper.confirmationSummary(plan))
    }

    @Test
    fun zeroAddSummariesDistinguishEmptyFullAlreadyPresentAndCommonOnlyBooks() {
        val emptyPlan = ContactNameShaper.planImport(emptyList(), emptyList())
        assertEquals("No contacts were found on this phone.", ContactNameShaper.confirmationSummary(emptyPlan))

        val candidates = listOf(
            ContactCandidateName("1", "Saurabh", "Vaish"),
            ContactCandidateName("2", "Aarav", "Mehta"),
        )
        val alreadyPlan = ContactNameShaper.planImport(
            candidates,
            existing = listOf(CustomTerm("Saurabh"), CustomTerm("Vaish"), CustomTerm("Aarav"), CustomTerm("Mehta")),
        )
        assertEquals("All eligible names are already in your word list.", ContactNameShaper.confirmationSummary(alreadyPlan))

        val duplicateExcludedCandidates = listOf(
            ContactCandidateName("1", "Saurabh", "Vaish"),
            ContactCandidateName("2", "Aarav", "Mehta"),
            ContactCandidateName("3", "Aarav", "Mehta"),
        )
        val dedupTruncatedPlan = ContactNameShaper.planImport(
            duplicateExcludedCandidates,
            existing = emptyList(),
            maxTotalTerms = 2,
        )
        assertEquals(2, dedupTruncatedPlan.truncatedByCapacity)

        val fullDictPlan = ContactNameShaper.planImport(
            candidates,
            existing = listOf(CustomTerm("ExistingOne"), CustomTerm("ExistingTwo")),
            maxTotalTerms = 2,
        )
        assertEquals("Your word list is already at the 2,000-word limit.", ContactNameShaper.confirmationSummary(fullDictPlan))

        val commonOnlyPlan = ContactNameShaper.planImport(
            listOf(ContactCandidateName("9", "Will", "Grant")),
            existing = emptyList(),
        )
        assertEquals("No new distinctive names were found to import.", ContactNameShaper.confirmationSummary(commonOnlyPlan))

        val cappedPlan = ContactNameShaper.planImport(
            candidates,
            existing = emptyList(),
            maxTotalTerms = 2,
        )
        assertEquals(2, cappedPlan.newTermCount)
        assertEquals(2, cappedPlan.truncatedByCapacity)
        assertTrue(ContactNameShaper.confirmationSummary(cappedPlan).contains("2,000-word limit"))
    }

    @Test
    fun contactsPermissionConfirmationAndDisclosureAreWiredBeforeWrite() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").takeIf { it.exists() }
            ?: java.io.File("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.readText().contains("android.permission.READ_CONTACTS"))

        val screen = java.io.File("src/main/java/com/envi/wispr/ui/DictionaryScreen.kt").takeIf { it.exists() }
            ?: java.io.File("app/src/main/java/com/envi/wispr/ui/DictionaryScreen.kt")
        val screenText = screen.readText()
        assertTrue(screenText.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(screenText.contains("ContactsImportConfirmDialog("))
        assertTrue(screenText.contains("PrivacyDisclosures.CONTACTS_IMPORT"))
        assertTrue(com.envi.wispr.privacy.PrivacyDisclosures.CONTACTS_IMPORT.contains("never uploads your address book"))
        assertTrue(com.envi.wispr.privacy.PrivacyDisclosures.CONTACTS_IMPORT.contains("only after you tap Add"))
    }
}
