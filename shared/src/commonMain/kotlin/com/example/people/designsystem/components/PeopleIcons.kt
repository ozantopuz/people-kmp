package com.example.people.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Built-in vector icons for design system states without external font/icon dependencies.
 */
object PeopleIcons {
    val Refresh: ImageVector by lazy {
        ImageVector.Builder("Refresh", 24.dp, 24.dp, 24f, 24f)
            .path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(21f, 2f); verticalLineTo(8f); horizontalLineTo(15f)
                moveTo(21f, 13f)
                curveTo(20.45f, 17.46f, 16.63f, 21f, 12f, 21f)
                curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
                curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
                curveTo(15.74f, 3f, 18.95f, 5.28f, 20.31f, 8.52f)
            }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black)) {
                moveTo(18.3f, 7.1f); lineTo(16.9f, 5.7f); lineTo(12f, 10.6f)
                lineTo(7.1f, 5.7f); lineTo(5.7f, 7.1f); lineTo(10.6f, 12f)
                lineTo(5.7f, 16.9f); lineTo(7.1f, 18.3f); lineTo(12f, 13.4f)
                lineTo(16.9f, 18.3f); lineTo(18.3f, 16.9f); lineTo(13.4f, 12f)
                close()
            }.build()
    }

    val Check: ImageVector by lazy {
        ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 16.2f); lineTo(4.8f, 12f); lineTo(3.4f, 13.4f)
                lineTo(9f, 19f); lineTo(21f, 7f); lineTo(19.6f, 5.6f)
                close()
            }.build()
    }

    val Person: ImageVector by lazy {
        ImageVector.Builder(
            name = "Person",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black)) {
            // Head
            moveTo(12f, 12f)
            curveTo(14.21f, 12f, 16f, 10.21f, 16f, 8f)
            curveTo(16f, 5.79f, 14.21f, 4f, 12f, 4f)
            curveTo(9.79f, 4f, 8f, 5.79f, 8f, 8f)
            curveTo(8f, 10.21f, 9.79f, 12f, 12f, 12f)
            close()
            // Body
            moveTo(12f, 14f)
            curveTo(9.33f, 14f, 4f, 15.34f, 4f, 18f)
            verticalLineTo(20f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
            curveTo(20f, 15.34f, 14.67f, 14f, 12f, 14f)
            close()
        }.build()
    }

    val CloudOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "CloudOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(19f)
            curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            close()
            moveTo(19f, 18f)
            horizontalLineTo(6f)
            curveTo(3.79f, 18f, 2f, 16.21f, 2f, 14f)
            curveTo(2f, 11.86f, 3.67f, 10.09f, 5.81f, 10.01f)
            lineTo(6.89f, 9.97f)
            lineTo(7.39f, 9.01f)
            curveTo(8.29f, 7.28f, 10.04f, 6f, 12f, 6f)
            curveTo(14.58f, 6f, 16.74f, 7.82f, 17.15f, 10.37f)
            lineTo(17.34f, 11.58f)
            lineTo(18.57f, 11.66f)
            curveTo(20.02f, 11.75f, 21.14f, 12.98f, 21.14f, 14.43f)
            curveTo(21.14f, 16.4f, 19.89f, 18f, 19f, 18f)
            close()
        }.build()
    }

    val Add: ImageVector by lazy {
        ImageVector.Builder(
            name = "Add",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 13f)
            horizontalLineTo(13f)
            verticalLineTo(19f)
            horizontalLineTo(11f)
            verticalLineTo(13f)
            horizontalLineTo(5f)
            verticalLineTo(11f)
            horizontalLineTo(11f)
            verticalLineTo(5f)
            horizontalLineTo(13f)
            verticalLineTo(11f)
            horizontalLineTo(19f)
            verticalLineTo(13f)
            close()
        }.build()
    }
}
