package com.example.people.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.people.designsystem.theme.PeopleSpacing
import com.example.people.designsystem.components.PeopleIcons
import com.example.people.domain.model.Gender
import com.example.people.domain.model.UserField
import com.example.people.presentation.model.AddUserFormState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun AddUserModal(
    state: AddUserFormState,
    expanded: Boolean,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onGenderChange: (Gender) -> Unit,
    onBlur: (UserField) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var dismissRequested by remember { mutableStateOf(false) }
    val dismiss = {
        if (dismissEnabled && !state.isSubmitting && !dismissRequested) {
            dismissRequested = true
            focusManager.clearFocus()
            keyboard?.hide()
            onDismiss()
        }
    }
    val content: @Composable () -> Unit = {
        AddUserForm(state, onNameChange, onEmailChange, onGenderChange, onBlur, onSubmit, dismiss)
    }
    if (expanded) {
        Dialog(
            onDismissRequest = dismiss,
            properties = DialogProperties(
                dismissOnBackPress = !state.isSubmitting,
                dismissOnClickOutside = !state.isSubmitting,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                modifier = modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding().padding(PeopleSpacing.large),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    content = content,
                )
            }
        }
    } else {
        BackHandler(enabled = true) { dismiss() }
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                AddUserForm(
                    state, onNameChange, onEmailChange, onGenderChange, onBlur, onSubmit, dismiss,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** One shared form for both presentations. Editor selection and composition stay with the mounted fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddUserForm(
    state: AddUserFormState,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onGenderChange: (Gender) -> Unit,
    onBlur: (UserField) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val emailFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var nameWasFocused by remember { mutableStateOf(false) }
    var emailWasFocused by remember { mutableStateOf(false) }
    var genderWasFocused by remember { mutableStateOf(false) }
    // The ViewModel owns the submitted strings. Each field mirrors its current text
    // immediately and keeps selection/composition locally, away from StateFlow echoes.
    var nameInput by remember {
        mutableStateOf(TextFieldValue(state.name, selection = TextRange(state.name.length)))
    }
    var emailInput by remember {
        mutableStateOf(TextFieldValue(state.email, selection = TextRange(state.email.length)))
    }
    // Opening the form must leave focus on no field so the keyboard stays closed.
    val inputShape = RoundedCornerShape(16.dp)
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        errorBorderColor = MaterialTheme.colorScheme.error,
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
        errorContainerColor = MaterialTheme.colorScheme.surface,
    )
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(PeopleSpacing.large),
        verticalArrangement = Arrangement.spacedBy(PeopleSpacing.default),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text("New user", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(
                    "Add someone to the directory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PeopleSpacing.extraSmall),
                )
            }
            IconButton(
                onClick = onDismiss,
                enabled = !state.isSubmitting,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(PeopleIcons.Close, contentDescription = "Close")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Full name",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.clearAndSetSemantics { },
            )
            OutlinedTextField(
                value = nameInput,
                onValueChange = { edited ->
                    val textChanged = edited.text != nameInput.text
                    nameInput = edited
                    if (textChanged) onNameChange(edited.text)
                },
                placeholder = { Text("Jane Appleseed") },
                singleLine = true,
                shape = inputShape,
                colors = inputColors,
                enabled = state.canEdit,
                isError = UserField.Name in state.errors,
                supportingText = state.errors[UserField.Name]?.let { error -> { FormError(error) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .onFocusChanged {
                        if (nameWasFocused && !it.isFocused) onBlur(UserField.Name)
                        nameWasFocused = it.isFocused
                    }
                    .semantics { contentDescription = "Full name" },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Email",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.clearAndSetSemantics { },
            )
            OutlinedTextField(
                value = emailInput,
                onValueChange = { edited ->
                    val textChanged = edited.text != emailInput.text
                    emailInput = edited
                    if (textChanged) onEmailChange(edited.text)
                },
                placeholder = { Text("jane@domain.com") },
                singleLine = true,
                shape = inputShape,
                colors = inputColors,
                enabled = state.canEdit,
                isError = UserField.Email in state.errors,
                supportingText = state.errors[UserField.Email]?.let { error -> { FormError(error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .focusRequester(emailFocus)
                    .onFocusChanged {
                        if (emailWasFocused && !it.isFocused) onBlur(UserField.Email)
                        emailWasFocused = it.isFocused
                    }
                    .semantics { contentDescription = "Email" },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(PeopleSpacing.small)) {
            Text("Gender", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            val genderTransition = updateTransition(state.gender, label = "Gender selection")
            val indicatorPosition by genderTransition.animateFloat(
                transitionSpec = {
                    if (initialState == null) snap()
                    else tween(durationMillis = 250, easing = LinearOutSlowInEasing)
                },
                label = "Gender indicator position",
            ) { gender -> if (gender == Gender.Male) 1f else 0f }
            val indicatorAlpha by genderTransition.animateFloat(
                transitionSpec = { tween(durationMillis = 150) },
                label = "Gender indicator visibility",
            ) { gender -> if (gender == null) 0f else 1f }
            Box(
                Modifier.fillMaxWidth()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .padding(PeopleSpacing.extraSmall)
                    .onFocusChanged {
                        if (genderWasFocused && !it.hasFocus) onBlur(UserField.Gender)
                        genderWasFocused = it.hasFocus
                    }
                    .focusGroup()
                    .selectableGroup(),
            ) {
                BoxWithConstraints(Modifier.matchParentSize()) {
                    Box(
                        Modifier.offset(x = maxWidth / 2 * indicatorPosition)
                            .fillMaxWidth(0.5f)
                            .fillMaxHeight()
                            .graphicsLayer { alpha = indicatorAlpha }
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf(Gender.Female, Gender.Male).forEach { gender ->
                        val selected = state.gender == gender
                        val labelColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(durationMillis = 150),
                            label = "Gender selection label",
                        )
                        val checkAlpha by animateFloatAsState(
                            targetValue = if (selected) 1f else 0f,
                            animationSpec = tween(durationMillis = 150),
                            label = "Gender checkmark",
                        )
                        Row(
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = state.canEdit,
                                    role = Role.RadioButton,
                                    onClick = { onGenderChange(gender) },
                                ),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                PeopleIcons.Check,
                                contentDescription = null,
                                tint = labelColor,
                                modifier = Modifier.size(16.dp).graphicsLayer { alpha = checkAlpha },
                            )
                            Text(
                                gender.name.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                color = labelColor,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
            state.errors[UserField.Gender]?.let { FormError(it) }
        }
        state.message?.let { message ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
            }
        }
        Button(
            onClick = {
                focusManager.clearFocus()
                keyboard?.hide()
                onSubmit()
            },
            enabled = !state.isSubmitting && !state.outcomeUnknown,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PeopleSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                if (state.isSubmitting) CircularProgressIndicator(
                    modifier = Modifier.size(PeopleSpacing.contentInset),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
                Text(when {
                    state.isSubmitting -> if (state.needsLocalSave) "Saving…" else "Adding…"
                    state.needsLocalSave -> "Retry saving"
                    else -> "Add user"
                })
            }
        }
    }
}

@Composable
private fun FormError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
