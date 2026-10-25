package com.example.people.presentation

import com.example.people.domain.model.Gender
import com.example.people.domain.model.NewUser
import com.example.people.domain.model.UserField
import com.example.people.presentation.model.AddUserFormState
import com.example.people.presentation.model.AddUserValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddUserValidationTest {
    @Test
    fun untouchedFormHasNoErrorsAndSubmitRequiresEveryField() {
        val state = AddUserFormState()
        assertTrue(state.errors.isEmpty())
        assertNull(state.gender)
        assertEquals(UserField.entries.toSet(), state.validateAll().errors.keys)
        assertNull(state.validatedUser())
    }

    @Test
    fun namesAcceptUnicodeSingleNamesAndPunctuation() {
        for (name in listOf("李小龍", "Марія", "İpek", "أحمد", "José O’Neill", "A", "किरण")) {
            assertNull(AddUserValidation.name(name), name)
        }
        assertNotNull(AddUserValidation.name(" \t\n "))
    }

    @Test
    fun trimsSurroundingWhitespaceWithoutChangingInteriorOrEmailCase() {
        val state = AddUserFormState(name = "  李  小龍 \n", email = " Ada+tag@Example.com \t", gender = Gender.Female)
        assertEquals(NewUser("李  小龍", "Ada+tag@Example.com", Gender.Female), state.validatedUser())
        assertEquals("  李  小龍 \n", state.name)
    }

    @Test
    fun rejectsReasonablyInvalidEmailsWithoutRestrictingNamesOrPlusAddresses() {
        for (email in listOf("", "  ", "ada", "@example.com", "a@", "a@@example.com", "a@localhost", "a b@example.com", "a@.com", "a@b..com", ".a@b.com", "a..b@c.com", "a@-b.com")) {
            assertNotNull(AddUserValidation.email(email), email)
        }
        for (email in listOf("a+b@example.co.uk", "o'neill@example.com", "名@example.com", "a@例子.公司")) {
            assertNull(AddUserValidation.email(email), email)
        }
    }

    @Test
    fun validatesOnlyInteractedFieldUntilSubmitAndClearsCorrectedError() {
        val blurred = AddUserFormState().validate(UserField.Name)
        assertEquals(setOf(UserField.Name), blurred.errors.keys)
        val corrected = blurred.copy(name = "Ada").validate(UserField.Name)
        assertTrue(corrected.errors.isEmpty())
        val editing = corrected.copy(email = "bad").validate(UserField.Email)
        assertEquals(setOf(UserField.Email), editing.errors.keys)
        assertEquals(setOf(UserField.Email, UserField.Gender), editing.validateAll().errors.keys)
    }
}
