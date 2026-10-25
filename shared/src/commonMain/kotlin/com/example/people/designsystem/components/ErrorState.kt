package com.example.people.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.people.designsystem.theme.PeopleSpacing

/**
 * Centered Error State component shown when network/API failure occurs with empty cache.
 */
@Composable
fun ErrorState(
    modifier: Modifier = Modifier,
    title: String = "No internet connection",
    message: String = "Unable to load users. Please check your network connection and try again.",
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PeopleSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(PeopleIcons.CloudOff, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(PeopleSpacing.contentInset))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(PeopleSpacing.small))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (onRetry != null) {
            Spacer(modifier = Modifier.height(PeopleSpacing.large))

            Button(
                onClick = onRetry,
                shape = CircleShape,
                modifier = Modifier.sizeIn(minHeight = 48.dp)
            ) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
