package com.example.people.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.people.designsystem.theme.PeopleSpacing

/**
 * The two-line header from the original People prototype.
 */
@Composable
fun UsersTopAppBar(
    modifier: Modifier = Modifier,
    peopleCount: Int? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = PeopleSpacing.contentInset, end = PeopleSpacing.contentInset, top = 16.dp, bottom = 12.dp),
    ) {
        Text(
            text = "PEOPLE",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.7.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Users",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (peopleCount != null && peopleCount > 0) {
                Text(
                    text = "$peopleCount people",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 3.dp),
                )
            }
        }
    }
}
