package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.liquidglass.outsideShadow

@Composable
fun LiquidGlassEditingBarSurface(
  backdrop: Any? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val shape = CircleShape
  val fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
  Box(
    modifier = Modifier.outsideShadow(shape, radius = 8.dp).clip(shape).background(fallbackColor),
    content = content,
  )
}
