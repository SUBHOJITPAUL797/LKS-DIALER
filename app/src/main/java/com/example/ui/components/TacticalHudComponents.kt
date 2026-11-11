package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

data class QuickStartStep(
    val number: String,
    val title: String,
    val description: String,
    val accentColor: Color = SignalAmber
)

/**
 * Industry-standard optical surface finish modifier for iPhone Liquid Glass, Frosted Fog Glass,
 * 3D Soft Clay, and Clean Utility.
 * Renders multi-layer specular highlights, inner luminous rims, and prismatic edge refraction
 * without obscuring text or icons.
 */
fun Modifier.surfaceFinishOverlay(
    style: AppSurfaceStyle,
    cornerRadius: Dp = style.cornerRadiusDp.dp,
    accentColor: Color = SignalAmber
): Modifier = this.drawWithContent {
    val rPx = cornerRadius.toPx()
    when (style) {
        AppSurfaceStyle.LIQUID_GLASS -> {
            // 1. Base Liquid Glass optical refraction wash (top-left specular caustics -> bottom-right prism tint)
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.34f),
                        Color(0xFF38BDF8).copy(alpha = 0.09f),
                        Color.Transparent,
                        Color(0xFFA855F7).copy(alpha = 0.07f),
                        Color.White.copy(alpha = 0.14f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = CornerRadius(rPx, rPx)
            )
            // 2. Soft top meniscus liquid sheen
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.48f
                ),
                cornerRadius = CornerRadius(rPx, rPx)
            )
            drawContent()
            // 3. Outer iPhone Liquid Glass specular prism rim (bright top-left & bottom-right specular reflection)
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f),
                        Color(0xFF38BDF8).copy(alpha = 0.55f),
                        accentColor.copy(alpha = 0.38f),
                        Color(0xFFE879F9).copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.70f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = CornerRadius(rPx, rPx),
                style = Stroke(width = 1.35.dp.toPx())
            )
        }
        AppSurfaceStyle.FROSTED_GLASS -> {
            // 1. Satin Fog Glass diffused atmospheric layer
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.40f),
                        Color(0xFFE0F2FE).copy(alpha = 0.16f),
                        Color(0xFFF1F5F9).copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.24f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = CornerRadius(rPx, rPx)
            )
            // 2. Radial soft fog glow from top center
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.24f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.3f, 0f),
                    radius = size.width.coerceAtLeast(1f) * 0.85f
                ),
                cornerRadius = CornerRadius(rPx, rPx)
            )
            drawContent()
            // 3. Crisp frosted acrylic rim
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.78f),
                        Color(0xFF7DD3FC).copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.42f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = CornerRadius(rPx, rPx),
                style = Stroke(width = 1.15.dp.toPx())
            )
        }
        AppSurfaceStyle.CLAY_3D -> {
            // Smooth full-card soft clay ambient gradient BEFORE content so text/icons stay 100% crisp
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.08f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height)
                ),
                cornerRadius = CornerRadius(rPx, rPx)
            )
            drawContent()
            // Subtle 1dp top-lit clay rim
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.32f),
                        style.borderColor.copy(alpha = 0.45f),
                        Color.Black.copy(alpha = 0.15f)
                    )
                ),
                cornerRadius = CornerRadius(rPx, rPx),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        AppSurfaceStyle.TACTICAL_HUD -> {
            drawContent()
        }
    }
}

@Composable
fun StyledSurfaceCard(
    modifier: Modifier = Modifier,
    style: AppSurfaceStyle = LocalSurfaceStyle.current,
    accentColor: Color = LocalThemeColor.current.primary,
    isHighlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val cornerRadius = style.cornerRadiusDp.dp
    val shape = RoundedCornerShape(cornerRadius)
    val baseBorder = style.resolvedBorderColor(isDark)
    val baseCard = style.resolvedCardColor(isDark)
    val borderColor = if (isHighlighted) accentColor else baseBorder.copy(alpha = 0.75f)
    val bgColor = if (isHighlighted) accentColor.copy(alpha = 0.15f) else baseCard

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .clip(shape)
                .surfaceFinishOverlay(style, cornerRadius, accentColor),
            shape = shape,
            color = bgColor,
            border = BorderStroke(if (isHighlighted) 1.5.dp else 1.dp, borderColor)
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier
                .clip(shape)
                .surfaceFinishOverlay(style, cornerRadius, accentColor),
            shape = shape,
            color = bgColor,
            border = BorderStroke(if (isHighlighted) 1.5.dp else 1.dp, borderColor)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun TacticalStatusPill(
    text: String,
    dotColor: Color = CyberMint,
    borderColor: Color = dotColor.copy(alpha = 0.45f),
    backgroundColor: Color = HudGlassOverlay,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = HudTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TacticalActionCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    accentColor: Color = SignalAmber,
    isActive: Boolean = false,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val surfaceStyle = LocalSurfaceStyle.current
    val cornerRadius = 14.dp
    val shape = RoundedCornerShape(cornerRadius)
    val borderColor = if (isActive) accentColor else surfaceStyle.borderColor.copy(alpha = 0.65f)
    val containerColor = if (isActive) accentColor.copy(alpha = 0.16f) else surfaceStyle.cardColor

    Surface(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 50.dp)
            .clip(shape)
            .surfaceFinishOverlay(surfaceStyle, cornerRadius, accentColor)
            .testTag(testTag),
        shape = shape,
        color = containerColor,
        border = BorderStroke(if (isActive) 1.4.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(9.dp),
                color = accentColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.40f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = HudTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = HudTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun TacticalToggleChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    activeColor: Color = CyberMint,
    testTag: String = "toggle_chip_$label",
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val surfaceStyle = LocalSurfaceStyle.current
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) activeColor else surfaceStyle.borderColor.copy(alpha = 0.65f)
    val bgColor = if (selected) activeColor.copy(alpha = 0.18f) else surfaceStyle.cardColor

    Surface(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .surfaceFinishOverlay(surfaceStyle, 12.dp, activeColor)
            .testTag(testTag),
        shape = shape,
        color = bgColor,
        border = BorderStroke(if (selected) 1.4.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else icon,
                contentDescription = label,
                tint = if (selected) activeColor else HudTextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (selected) HudTextPrimary else HudTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun QuickStartGuideCard(
    headerTitle: String = "3-STEP QUICK START GUIDE",
    headerSubtitle: String = "Tactical field readiness in 5 seconds",
    steps: List<QuickStartStep>,
    actionButtonText: String? = null,
    actionButtonIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val surfaceStyle = LocalSurfaceStyle.current
    val cornerRadius = 16.dp
    val shape = RoundedCornerShape(cornerRadius)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .surfaceFinishOverlay(surfaceStyle, cornerRadius, SignalAmber)
            .testTag("quick_start_guide_card"),
        shape = shape,
        color = surfaceStyle.cardColor,
        border = BorderStroke(1.dp, surfaceStyle.borderColor.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = SignalAmber.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, SignalAmber.copy(alpha = 0.45f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = SignalAmber,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = headerTitle,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = headerSubtitle,
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 11.sp,
                            color = HudTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TacticalStatusPill(
                    text = surfaceStyle.badge,
                    dotColor = CyberMint
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = step.accentColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, step.accentColor.copy(alpha = 0.45f)),
                        modifier = Modifier.size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = step.number,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = step.accentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.title,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = step.description,
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 11.sp,
                            color = HudTextSecondary,
                            lineHeight = 15.sp
                        )
                    }
                }
                if (index < steps.lastIndex) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }

            if (actionButtonText != null && onActionClick != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onActionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 46.dp)
                        .testTag("quick_start_action_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SignalAmber,
                        contentColor = HudTextPrimary
                    )
                ) {
                    if (actionButtonIcon != null) {
                        Icon(
                            imageVector = actionButtonIcon,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = actionButtonText,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
