package com.example.people.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.example.people.designsystem.theme.PeopleSpacing

/**
 * Shimmer effect modifier for loading skeletons.
 */
@Composable
fun Modifier.shimmer(): Modifier {
    val transition = rememberInfiniteTransition()
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        )
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 300f, translateAnim - 300f),
        end = Offset(translateAnim, translateAnim)
    )

    return this.background(brush)
}

/**
 * Single list row loading skeleton placeholder.
 */
@Composable
fun UserRowSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = PeopleSpacing.default,
        vertical = PeopleSpacing.medium,
    ),
    showDivider: Boolean = false
) {
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = contentPadding.calculateStartPadding(layoutDirection)
    val endPadding = contentPadding.calculateEndPadding(layoutDirection)

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PeopleSpacing.rowMinHeight)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Placeholder (44dp circle)
            Box(
                modifier = Modifier
                    .size(PeopleSpacing.avatarSize)
                    .clip(CircleShape)
                    .shimmer()
            )

            Spacer(modifier = Modifier.width(PeopleSpacing.default))

            // Text Placeholders
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Name Box
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmer()
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Email Box
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmer()
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Timestamp Box
                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmer()
                )
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

/**
 * List of shimmer loading skeletons shown during initial uncached loading.
 * Adapts to single-column (<600dp) or two-column grid (>=600dp).
 */
@Composable
fun UsersLoadingSkeleton(
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= PeopleSpacing.adaptiveWidthThreshold
        val rowHeight = PeopleSpacing.rowMinHeight
        val safeMaxHeight = if (maxHeight == androidx.compose.ui.unit.Dp.Infinity) 1000.dp else maxHeight
        val rowsToFill = (safeMaxHeight / rowHeight).toInt() + 1
        val itemsCount = if (isExpandedWidth) rowsToFill * 2 else rowsToFill

        if (isExpandedWidth) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = PeopleSpacing.small,
                    bottom = 0.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize()
            ) {
                items(itemsCount) {
                    UserRowSkeleton(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = PeopleSpacing.medium),
                        showDivider = false,
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).clipToBounds()) {
                repeat(itemsCount) {
                    UserRowSkeleton(showDivider = false)
                }
            }
        }
    }
}
