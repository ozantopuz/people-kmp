package com.example.people.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.people.designsystem.theme.PeopleSpacing
import com.example.people.designsystem.theme.UserNameTextStyle
import com.example.people.presentation.model.UserItemUiState

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * Clean user list row displaying initials avatar, name, email, and relative timestamp.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserRow(
    user: UserItemUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = PeopleSpacing.default,
        vertical = PeopleSpacing.medium,
    ),
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val clickableModifier = if ((onClick != null) || (onLongClick != null)) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = { onLongClick?.invoke() },
            onLongClickLabel = if (onLongClick != null) "Delete ${user.name}" else null,
        )
    } else {
        Modifier
    }

    val layoutDirection = LocalLayoutDirection.current
    val startPadding = contentPadding.calculateStartPadding(layoutDirection)
    val endPadding = contentPadding.calculateEndPadding(layoutDirection)
    val rowSemanticsDescription = "${user.name}, ${user.email}, ${user.relativeTimestamp}"
    val isLocal = user.relativeTimestamp.startsWith("Added")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(clickableModifier)
            .semantics(mergeDescendants = true) {
                contentDescription = rowSemanticsDescription
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PeopleSpacing.rowMinHeight)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(user = user)

            Spacer(modifier = Modifier.width(PeopleSpacing.default))

            // User Info (Name, Email, Relative Timestamp)
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = user.name,
                    style = UserNameTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(PeopleSpacing.extraSmall))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLocal) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clearAndSetSemantics { },
                        )
                    }
                    Text(
                        text = user.relativeTimestamp,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isLocal) FontWeight.Medium else FontWeight.Normal),
                        color = if (isLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(
                    start = startPadding + PeopleSpacing.avatarSize + PeopleSpacing.default,
                    end = endPadding,
                ),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp
            )
        }
    }
}
