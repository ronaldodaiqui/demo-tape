package app.demotape.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A tuning fork: two prongs joined in a U, on a short handle. */
val TuningFork: ImageVector by lazy {
    ImageVector.Builder(
        name = "TuningFork",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9f, 2.5f)
            lineTo(9f, 10f)
            curveTo(9f, 14f, 15f, 14f, 15f, 10f)
            lineTo(15f, 2.5f)
            moveTo(12f, 13f)
            lineTo(12f, 21.5f)
        }
    }.build()
}
