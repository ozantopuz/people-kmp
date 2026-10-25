package com.example.people.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.people.designsystem.theme.PeopleSpacing
import com.example.people.presentation.model.UserItemUiState

/** The same stable, theme-aware initials treatment in rows and confirmation UI. */
@Composable
fun UserAvatar(
    user: UserItemUiState,
    modifier: Modifier = Modifier,
    size: Dp = PeopleSpacing.avatarSize,
) {
    val blueAvatar = user.id % 2L == 0L
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (blueAvatar) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = user.initials,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = if (blueAvatar) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
