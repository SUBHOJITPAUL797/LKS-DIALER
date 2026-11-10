package com.example.ui.screens.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.example.ui.components.surfaceFinishOverlay
import com.example.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * 3 Selectable 3D Characters for the Optional 3D AR Character Studio Overlay.
 */
enum class ArCharacterType(
    val id: String,
    val displayName: String,
    val badgeLabel: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val visorColor: Color,
    val defaultSpeech: String
) {
    CYBER_BOT(
        id = "cyber_bot",
        displayName = "Cyber Bot",
        badgeLabel = "BOT-01",
        primaryColor = Color(0xFF38BDF8),
        secondaryColor = Color(0xFF1E40AF),
        visorColor = CyberMint,
        defaultSpeech = "BEEP! LKS LINK READY"
    ),
    ASTRO_EXPLORER(
        id = "astro_explorer",
        displayName = "Astro Explorer",
        badgeLabel = "EVA-09",
        primaryColor = Color(0xFFF8FAFC),
        secondaryColor = Color(0xFF475569),
        visorColor = SignalAmber,
        defaultSpeech = "ORBITAL COMMS CLEAR!"
    ),
    FIELD_DRONE(
        id = "field_drone",
        displayName = "Field Drone",
        badgeLabel = "UAV-X4",
        primaryColor = Color(0xFF64748B),
        secondaryColor = Color(0xFF1E293B),
        visorColor = CyberMint,
        defaultSpeech = "RECON TARGET LOCKED"
    ),
    TOON_BUDDY(
        id = "toon_buddy",
        displayName = "Toon Buddy",
        badgeLabel = "TOON-3D",
        primaryColor = Color(0xFFFF6D00),
        secondaryColor = Color(0xFFFFB74D),
        visorColor = Color(0xFF00E676),
        defaultSpeech = "LET'S DANCE & CALL! 🎵"
    )
}

/**
 * 3 Selectable 3D Material & UI Surface Finishes:
 * - CLAY_3D: Sculpted Soft Matte 3D Clay (Toy/Pixar claymorphic volume, warm subsurface glow, soft rim)
 * - FROSTED_GLASS: Translucent Icy Frosted Acrylic Glass (diffused inner light + crisp frost border)
 * - LIQUID_GLASS: High-Gloss Iridescent Liquid Glass (dual specular caustics + chrome-glass sheen)
 */
enum class ArMaterialFinish(
    val id: String,
    val displayName: String,
    val accentColor: Color,
    val surfaceStyle: AppSurfaceStyle
) {
    CLAY_3D(
        id = "clay_3d",
        displayName = "Clay 3D",
        accentColor = SignalAmber,
        surfaceStyle = AppSurfaceStyle.CLAY_3D
    ),
    FROSTED_GLASS(
        id = "frosted_glass",
        displayName = "Frosted Glass",
        accentColor = Color(0xFF38BDF8),
        surfaceStyle = AppSurfaceStyle.FROSTED_GLASS
    ),
    LIQUID_GLASS(
        id = "liquid_glass",
        displayName = "Liquid Glass",
        accentColor = Color(0xFFE879F9),
        surfaceStyle = AppSurfaceStyle.LIQUID_GLASS
    )
}

/**
 * 4 Live Animated Poses for the 3D Characters.
 */
enum class ArCharacterPose(
    val id: String,
    val displayName: String,
    val icon: ImageVector
) {
    WAVE("wave", "Wave", Icons.Default.WavingHand),
    DANCE("dance", "Dance", Icons.Default.MusicNote),
    JUMP("jump", "Jump", Icons.Default.KeyboardDoubleArrowUp),
    SALUTE("salute", "Salute", Icons.Default.MilitaryTech)
}

/**
 * Complete state for the 3D AR Character Studio Overlay.
 * STRICT RULE: [enabled] MUST default to `false` so the camera viewfinder is 100% clean by default.
 */
data class ArCharacterState(
    val enabled: Boolean = false,
    val character: ArCharacterType = ArCharacterType.CYBER_BOT,
    val pose: ArCharacterPose = ArCharacterPose.WAVE,
    val materialFinish: ArMaterialFinish = ArMaterialFinish.CLAY_3D,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.54f,
    val scale: Float = 1.05f,
    val rotationYDeg: Float = 12f,
    val showSpeechBubble: Boolean = true,
    val speechIndex: Int = 0
) {
    val activeSpeechText: String
        get() {
            val options = when (character) {
                ArCharacterType.CYBER_BOT -> listOf(
                    "BEEP! LKS LINK READY",
                    "CLAY BOT ONLINE!",
                    "SAY CHEESE OPERATOR!"
                )
                ArCharacterType.ASTRO_EXPLORER -> listOf(
                    "ORBITAL COMMS CLEAR!",
                    "ZERO-G FIELD READY!",
                    "100% SIGNAL LOCKED!"
                )
                ArCharacterType.FIELD_DRONE -> listOf(
                    "RECON TARGET LOCKED",
                    "360° PERIMETER SAFE",
                    "TELEMETRY STREAMING"
                )
                ArCharacterType.TOON_BUDDY -> listOf(
                    "LET'S DANCE & CALL! 🎵",
                    "BOUNCY BEATS READY!",
                    "READY TO GROOVE! ✨"
                )
            }
            return options[speechIndex.mod(options.size)]
        }
}

private data class Vec3(val x: Float, val y: Float, val z: Float)

private sealed class RenderPrimitive(val sortDepth: Float) {
    class Sphere3D(
        val center: Vec3,
        val radius: Float,
        val baseColor: Color,
        val darkColor: Color,
        val isEmissive: Boolean = false,
        val isCheekBlush: Boolean = false,
        sortDepth: Float = center.z
    ) : RenderPrimitive(sortDepth)

    class Capsule3D(
        val start: Vec3,
        val end: Vec3,
        val radius: Float,
        val baseColor: Color,
        val darkColor: Color,
        val isEmissive: Boolean = false,
        sortDepth: Float = (start.z + end.z) * 0.5f
    ) : RenderPrimitive(sortDepth)

    class RotorDisk3D(
        val center: Vec3,
        val radius: Float,
        val spinPhase: Float,
        val glowColor: Color,
        sortDepth: Float = center.z
    ) : RenderPrimitive(sortDepth)
}

object ArCharacter3dRenderer {

    private fun rotateY(v: Vec3, rad: Float): Vec3 {
        val c = cos(rad)
        val s = sin(rad)
        return Vec3(
            x = v.x * c + v.z * s,
            y = v.y,
            z = -v.x * s + v.z * c
        )
    }

    private fun rotateX(v: Vec3, rad: Float): Vec3 {
        val c = cos(rad)
        val s = sin(rad)
        return Vec3(
            x = v.x,
            y = v.y * c - v.z * s,
            z = v.y * s + v.z * c
        )
    }

    private fun rotateZ(v: Vec3, rad: Float): Vec3 {
        val c = cos(rad)
        val s = sin(rad)
        return Vec3(
            x = v.x * c - v.y * s,
            y = v.x * s + v.y * c,
            z = v.z
        )
    }

    private fun project(
        v: Vec3,
        centerX: Float,
        centerY: Float,
        baseUnitPx: Float
    ): Triple<Float, Float, Float> {
        val cameraDist = 4.6f
        val perspective = cameraDist / (cameraDist - v.z.coerceIn(-2.5f, 2.5f))
        val sx = centerX + v.x * baseUnitPx * perspective
        val sy = centerY - v.y * baseUnitPx * perspective
        return Triple(sx, sy, perspective)
    }

    private fun lighten(color: Color, amount: Float): Color {
        return Color(
            red = (color.red + (1f - color.red) * amount).coerceIn(0f, 1f),
            green = (color.green + (1f - color.green) * amount).coerceIn(0f, 1f),
            blue = (color.blue + (1f - color.blue) * amount).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }

    private fun darken(color: Color, amount: Float): Color {
        return Color(
            red = (color.red * (1f - amount)).coerceIn(0f, 1f),
            green = (color.green * (1f - amount)).coerceIn(0f, 1f),
            blue = (color.blue * (1f - amount)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }

    private fun mix(c1: Color, c2: Color, ratio: Float): Color {
        val r = ratio.coerceIn(0f, 1f)
        return Color(
            red = c1.red * (1f - r) + c2.red * r,
            green = c1.green * (1f - r) + c2.green * r,
            blue = c1.blue * (1f - r) + c2.blue * r,
            alpha = c1.alpha * (1f - r) + c2.alpha * r
        )
    }

    /**
     * Builds the sculpted 3D clay/glass character primitives in world space.
     */
    private fun buildCharacterScene(
        state: ArCharacterState,
        timeSec: Float
    ): Pair<List<RenderPrimitive>, Float> {
        val primitives = mutableListOf<RenderPrimitive>()

        val idleBob = sin(timeSec * 2.8f) * 0.045f
        val jumpOffset = when (state.pose) {
            ArCharacterPose.JUMP -> abs(sin(timeSec * 4.4f)) * 0.44f
            ArCharacterPose.DANCE -> abs(sin(timeSec * 6.0f)) * 0.14f
            else -> 0f
        }
        val totalLiftY = idleBob + jumpOffset

        val danceYaw = if (state.pose == ArCharacterPose.DANCE) sin(timeSec * 3.2f) * 28f else 0f
        val danceTiltZ = if (state.pose == ArCharacterPose.DANCE) sin(timeSec * 6.0f) * 0.16f else 0f
        val jumpPitchX = if (state.pose == ArCharacterPose.JUMP) sin(timeSec * 4.4f) * 0.12f else 0f

        val yawRad = Math.toRadians((state.rotationYDeg + danceYaw).toDouble()).toFloat()

        fun xform(local: Vec3): Vec3 {
            val zTilted = rotateZ(local, danceTiltZ)
            val xTilted = rotateX(zTilted, jumpPitchX)
            val yRotated = rotateY(xTilted, yawRad)
            return Vec3(yRotated.x, yRotated.y + totalLiftY, yRotated.z)
        }

        // Adapt palette slightly for Clay / Frosted / Liquid finishes
        val pColor = when (state.materialFinish) {
            ArMaterialFinish.CLAY_3D -> lighten(state.character.primaryColor, 0.12f)
            ArMaterialFinish.FROSTED_GLASS -> mix(state.character.primaryColor, Color(0xFFE0F2FE), 0.35f)
            ArMaterialFinish.LIQUID_GLASS -> mix(state.character.primaryColor, Color(0xFF60A5FA), 0.25f)
        }
        val sColor = when (state.materialFinish) {
            ArMaterialFinish.CLAY_3D -> lighten(state.character.secondaryColor, 0.10f)
            ArMaterialFinish.FROSTED_GLASS -> mix(state.character.secondaryColor, Color(0xFF38BDF8), 0.28f)
            ArMaterialFinish.LIQUID_GLASS -> mix(state.character.secondaryColor, Color(0xFF818CF8), 0.30f)
        }
        val visorColor = state.character.visorColor
        val blushColor = Color(0xFFFF758F)

        when (state.character) {
            ArCharacterType.CYBER_BOT -> {
                // Cute sculpted Clay/Glass Robot with chubby rounded torso, articulated limbs, ear pods & glowing visor
                val rightArmWave = when (state.pose) {
                    ArCharacterPose.WAVE -> 0.90f + sin(timeSec * 7.5f) * 0.42f
                    ArCharacterPose.DANCE -> 0.55f + sin(timeSec * 6.0f) * 0.60f
                    ArCharacterPose.JUMP -> 1.15f
                    ArCharacterPose.SALUTE -> 1.30f
                }
                val leftArmSwing = when (state.pose) {
                    ArCharacterPose.WAVE -> -0.25f + sin(timeSec * 2.8f) * 0.08f
                    ArCharacterPose.DANCE -> 0.55f - sin(timeSec * 6.0f) * 0.60f
                    ArCharacterPose.JUMP -> 1.15f
                    ArCharacterPose.SALUTE -> -0.20f
                }
                val legSwing = when (state.pose) {
                    ArCharacterPose.DANCE -> sin(timeSec * 6.0f) * 0.28f
                    ArCharacterPose.JUMP -> -0.24f
                    else -> sin(timeSec * 2.8f) * 0.04f
                }

                // Plump Clay Legs & Rounded Boots
                val leftHip = Vec3(-0.22f, -0.34f, 0f)
                val leftFoot = Vec3(-0.24f, -0.78f + max(0f, legSwing * 0.2f), legSwing * 0.38f)
                val rightHip = Vec3(0.22f, -0.34f, 0f)
                val rightFoot = Vec3(0.24f, -0.78f + max(0f, -legSwing * 0.2f), -legSwing * 0.38f)

                primitives += RenderPrimitive.Capsule3D(
                    start = xform(leftHip),
                    end = xform(leftFoot),
                    radius = 0.145f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.35f)
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rightHip),
                    end = xform(rightFoot),
                    radius = 0.145f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.35f)
                )
                // Chubby rounded boots
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(leftFoot),
                    end = xform(Vec3(leftFoot.x, leftFoot.y - 0.03f, leftFoot.z + 0.10f)),
                    radius = 0.165f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rightFoot),
                    end = xform(Vec3(rightFoot.x, rightFoot.y - 0.03f, rightFoot.z + 0.10f)),
                    radius = 0.165f,
                    baseColor = pColor,
                    darkColor = sColor
                )

                // Power Backpack (behind torso)
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0f, 0.08f, -0.26f)),
                    end = xform(Vec3(0f, -0.18f, -0.26f)),
                    radius = 0.22f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.4f)
                )

                // Sculpted Chubby Clay Torso (rounded pill body + belly badge)
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0f, 0.14f, 0f)),
                    end = xform(Vec3(0f, -0.26f, 0f)),
                    radius = 0.36f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                // Glowing Chest Heart / Core Pill
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.07f, -0.02f, 0.33f)),
                    end = xform(Vec3(0.07f, -0.02f, 0.33f)),
                    radius = 0.095f,
                    baseColor = visorColor,
                    darkColor = SignalAmber,
                    isEmissive = true
                )

                // Left Arm & Chubby Mitt
                val leftShoulder = Vec3(-0.42f, 0.10f, 0f)
                val leftElbow = Vec3(
                    x = -0.58f,
                    y = 0.04f + sin(leftArmSwing) * 0.22f,
                    z = cos(leftArmSwing) * 0.14f
                )
                val leftHand = Vec3(
                    x = -0.66f,
                    y = -0.10f + sin(leftArmSwing) * 0.44f,
                    z = 0.16f + cos(leftArmSwing) * 0.18f
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(leftShoulder),
                    end = xform(leftElbow),
                    radius = 0.125f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(leftElbow),
                    end = xform(leftHand),
                    radius = 0.12f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.35f)
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(leftHand),
                    radius = 0.145f,
                    baseColor = visorColor,
                    darkColor = sColor
                )

                // Right Arm (waves / salutes)
                val rightShoulder = Vec3(0.42f, 0.10f, 0f)
                val rightElbow = Vec3(
                    x = 0.58f,
                    y = 0.14f + sin(rightArmWave) * 0.22f,
                    z = 0.10f
                )
                val rightHand = if (state.pose == ArCharacterPose.SALUTE) {
                    Vec3(0.32f, 0.62f, 0.30f)
                } else {
                    Vec3(
                        x = 0.66f + cos(timeSec * 7.5f) * 0.10f,
                        y = 0.36f + sin(rightArmWave) * 0.30f,
                        z = 0.24f
                    )
                }
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rightShoulder),
                    end = xform(rightElbow),
                    radius = 0.125f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rightElbow),
                    end = xform(rightHand),
                    radius = 0.12f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.35f)
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(rightHand),
                    radius = 0.145f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )

                // Sculpted Wide Pill Head (cute toy robot proportions)
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.08f, 0.56f, 0f)),
                    end = xform(Vec3(0.08f, 0.56f, 0f)),
                    radius = 0.34f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                // Cute Side Ear Pods
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(-0.41f, 0.56f, 0f)),
                    radius = 0.11f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(0.41f, 0.56f, 0f)),
                    radius = 0.11f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )
                // Glossy Recessed Faceplate Screen
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.11f, 0.56f, 0.25f)),
                    end = xform(Vec3(0.11f, 0.56f, 0.25f)),
                    radius = 0.19f,
                    baseColor = Color(0xFF0F172A),
                    darkColor = Color(0xFF020617)
                )
                // Expressive Glowing LED Eyes + Rosy Clay Cheeks
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(-0.11f, 0.58f, 0.42f)),
                    radius = 0.062f,
                    baseColor = visorColor,
                    darkColor = visorColor,
                    isEmissive = true
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(0.11f, 0.58f, 0.42f)),
                    radius = 0.062f,
                    baseColor = visorColor,
                    darkColor = visorColor,
                    isEmissive = true
                )
                // Cute warm cheek blushes
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(-0.20f, 0.50f, 0.38f)),
                    radius = 0.048f,
                    baseColor = blushColor,
                    darkColor = blushColor,
                    isCheekBlush = true
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(0.20f, 0.50f, 0.38f)),
                    radius = 0.048f,
                    baseColor = blushColor,
                    darkColor = blushColor,
                    isCheekBlush = true
                )

                // Bouncing Clay Antenna + Glowing Bobble Orb
                val antennaTip = Vec3(
                    x = sin(timeSec * 5f) * 0.05f,
                    y = 1.04f + sin(timeSec * 4f) * 0.03f,
                    z = 0f
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0f, 0.86f, 0f)),
                    end = xform(antennaTip),
                    radius = 0.045f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.3f)
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(antennaTip),
                    radius = 0.105f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep,
                    isEmissive = true
                )
            }

            ArCharacterType.ASTRO_EXPLORER -> {
                // Sculpted Clay/Glass Astronaut with puffy pressurized suit, gold dome visor & twin-thruster jetpack
                val rightWave = when (state.pose) {
                    ArCharacterPose.WAVE -> 0.85f + sin(timeSec * 6.8f) * 0.42f
                    ArCharacterPose.DANCE -> 0.5f + sin(timeSec * 5.8f) * 0.55f
                    ArCharacterPose.JUMP -> 1.25f
                    ArCharacterPose.SALUTE -> 1.32f
                }
                val leftWave = when (state.pose) {
                    ArCharacterPose.DANCE -> 0.5f - sin(timeSec * 5.8f) * 0.55f
                    ArCharacterPose.JUMP -> 1.25f
                    else -> -0.25f + sin(timeSec * 2.5f) * 0.08f
                }
                val legStride = when (state.pose) {
                    ArCharacterPose.DANCE -> sin(timeSec * 5.8f) * 0.25f
                    ArCharacterPose.JUMP -> 0.22f
                    else -> 0f
                }

                // Twin Jetpack Cylinders (behind suit)
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.17f, 0.24f, -0.28f)),
                    end = xform(Vec3(-0.17f, -0.22f, -0.28f)),
                    radius = 0.15f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.4f)
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0.17f, 0.24f, -0.28f)),
                    end = xform(Vec3(0.17f, -0.22f, -0.28f)),
                    radius = 0.15f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.4f)
                )
                // Thruster flame puffs when jumping or dancing
                if (state.pose == ArCharacterPose.JUMP || state.pose == ArCharacterPose.DANCE) {
                    val flamePulse = 0.09f + abs(sin(timeSec * 12f)) * 0.05f
                    primitives += RenderPrimitive.Sphere3D(
                        center = xform(Vec3(-0.17f, -0.36f, -0.28f)),
                        radius = flamePulse,
                        baseColor = SignalAmber,
                        darkColor = CrimsonAlert,
                        isEmissive = true
                    )
                    primitives += RenderPrimitive.Sphere3D(
                        center = xform(Vec3(0.17f, -0.36f, -0.28f)),
                        radius = flamePulse,
                        baseColor = SignalAmber,
                        darkColor = CrimsonAlert,
                        isEmissive = true
                    )
                }

                // Puffy Pressurized Space Legs & Orange Moon Boots
                val lFoot = Vec3(-0.22f, -0.78f, legStride)
                val rFoot = Vec3(0.22f, -0.78f, -legStride)
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.21f, -0.30f, 0f)),
                    end = xform(lFoot),
                    radius = 0.16f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0.21f, -0.30f, 0f)),
                    end = xform(rFoot),
                    radius = 0.16f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(lFoot),
                    end = xform(Vec3(lFoot.x, lFoot.y - 0.02f, lFoot.z + 0.11f)),
                    radius = 0.175f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rFoot),
                    end = xform(Vec3(rFoot.x, rFoot.y - 0.02f, rFoot.z + 0.11f)),
                    radius = 0.175f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )

                // Sculpted Puffy Spacesuit Torso + Chest Telemetry Pack
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0f, 0.16f, 0f)),
                    end = xform(Vec3(0f, -0.26f, 0f)),
                    radius = 0.37f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                // Waist Belt Ring
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.22f, -0.24f, 0.18f)),
                    end = xform(Vec3(0.22f, -0.24f, 0.18f)),
                    radius = 0.085f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )
                // Chest Life-Support Box
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.10f, 0.02f, 0.32f)),
                    end = xform(Vec3(0.10f, 0.02f, 0.32f)),
                    radius = 0.11f,
                    baseColor = TelemetryBlue,
                    darkColor = sColor,
                    isEmissive = true
                )

                // Left Space Arm & Glove
                val lShoulder = Vec3(-0.42f, 0.14f, 0f)
                val lHand = Vec3(
                    x = -0.66f,
                    y = -0.06f + sin(leftWave) * 0.42f,
                    z = 0.16f
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(lShoulder),
                    end = xform(lHand),
                    radius = 0.14f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(lHand),
                    radius = 0.15f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )

                // Right Space Arm & Glove
                val rShoulder = Vec3(0.42f, 0.14f, 0f)
                val rHand = if (state.pose == ArCharacterPose.SALUTE) {
                    Vec3(0.34f, 0.62f, 0.32f)
                } else {
                    Vec3(
                        x = 0.66f + cos(timeSec * 6.8f) * 0.08f,
                        y = 0.34f + sin(rightWave) * 0.30f,
                        z = 0.22f
                    )
                }
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rShoulder),
                    end = xform(rHand),
                    radius = 0.14f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(rHand),
                    radius = 0.15f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )

                // Oversized Chubby Astronaut Helmet + Reflective Gold/Amber Curved Visor
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(0f, 0.58f, 0f)),
                    radius = 0.38f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                // Gold Curved Visor Bubble
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.10f, 0.58f, 0.20f)),
                    end = xform(Vec3(0.10f, 0.58f, 0.20f)),
                    radius = 0.24f,
                    baseColor = visorColor,
                    darkColor = SignalAmberDeep,
                    isEmissive = true
                )
                // Cute Helmet Torch Pod on side
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(-0.37f, 0.62f, 0.08f)),
                    radius = 0.085f,
                    baseColor = CyberMint,
                    darkColor = sColor,
                    isEmissive = true
                )
            }

            ArCharacterType.FIELD_DRONE -> {
                // Sculpted Tactical Quadcopter Drone with central clay/glass pod, gimbal eye & 4 spinning rotors
                val hoverRoll = when (state.pose) {
                    ArCharacterPose.WAVE -> sin(timeSec * 5.0f) * 0.24f
                    ArCharacterPose.DANCE -> sin(timeSec * 7.0f) * 0.35f
                    ArCharacterPose.SALUTE -> -0.18f
                    else -> sin(timeSec * 2.5f) * 0.08f
                }

                fun droneXform(local: Vec3): Vec3 {
                    val rolled = rotateZ(local, hoverRoll)
                    return xform(rolled)
                }

                // Landing Skids underneath
                primitives += RenderPrimitive.Capsule3D(
                    start = droneXform(Vec3(-0.34f, -0.36f, -0.26f)),
                    end = droneXform(Vec3(-0.34f, -0.36f, 0.26f)),
                    radius = 0.055f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.4f)
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = droneXform(Vec3(0.34f, -0.36f, -0.26f)),
                    end = droneXform(Vec3(0.34f, -0.36f, 0.26f)),
                    radius = 0.055f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.4f)
                )

                // 4 Quadcopter Arms + Motor Pods + Spinning Rotor Disks
                val armOffsets = listOf(
                    Vec3(-0.56f, 0.08f, -0.46f),
                    Vec3(0.56f, 0.08f, -0.46f),
                    Vec3(-0.56f, 0.08f, 0.46f),
                    Vec3(0.56f, 0.08f, 0.46f)
                )
                armOffsets.forEachIndexed { idx, armEnd ->
                    primitives += RenderPrimitive.Capsule3D(
                        start = droneXform(Vec3(0f, 0.04f, 0f)),
                        end = droneXform(armEnd),
                        radius = 0.075f,
                        baseColor = pColor,
                        darkColor = sColor
                    )
                    primitives += RenderPrimitive.Sphere3D(
                        center = droneXform(armEnd),
                        radius = 0.135f,
                        baseColor = if (idx >= 2) SignalAmber else CyberMint,
                        darkColor = sColor,
                        isEmissive = true
                    )
                    val rotorCenter = droneXform(Vec3(armEnd.x, armEnd.y + 0.11f, armEnd.z))
                    primitives += RenderPrimitive.RotorDisk3D(
                        center = rotorCenter,
                        radius = 0.30f,
                        spinPhase = timeSec * 18f + idx * 1.4f,
                        glowColor = if (idx >= 2) SignalAmber else CyberMint
                    )
                }

                // Sculpted Chubby Central Drone Body Pod
                primitives += RenderPrimitive.Capsule3D(
                    start = droneXform(Vec3(-0.14f, 0.06f, 0f)),
                    end = droneXform(Vec3(0.14f, 0.06f, 0f)),
                    radius = 0.34f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                // Top Shell Crest
                primitives += RenderPrimitive.Sphere3D(
                    center = droneXform(Vec3(0f, 0.24f, -0.02f)),
                    radius = 0.24f,
                    baseColor = SignalAmber,
                    darkColor = SignalAmberDeep
                )
                // Front Optical Gimbal Eye
                primitives += RenderPrimitive.Sphere3D(
                    center = droneXform(Vec3(0f, 0.03f, 0.32f)),
                    radius = 0.19f,
                    baseColor = Color(0xFF0F172A),
                    darkColor = Color.Black
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = droneXform(Vec3(0f, 0.03f, 0.44f)),
                    radius = 0.11f,
                    baseColor = visorColor,
                    darkColor = TelemetryBlue,
                    isEmissive = true
                )
            }

            ArCharacterType.TOON_BUDDY -> {
                // Super cute cartoonish dancing buddy with chubby clay body, bouncy ears & joyful eyes
                val rightArmSwing = when (state.pose) {
                    ArCharacterPose.WAVE -> 0.95f + sin(timeSec * 8f) * 0.45f
                    ArCharacterPose.DANCE -> 0.60f + sin(timeSec * 7f) * 0.65f
                    ArCharacterPose.JUMP -> 1.30f
                    ArCharacterPose.SALUTE -> 1.35f
                }
                val leftArmSwing = when (state.pose) {
                    ArCharacterPose.DANCE -> 0.60f - sin(timeSec * 7f) * 0.65f
                    ArCharacterPose.JUMP -> 1.30f
                    else -> -0.20f + sin(timeSec * 3f) * 0.10f
                }
                val footTap = when (state.pose) {
                    ArCharacterPose.DANCE -> sin(timeSec * 7f) * 0.32f
                    ArCharacterPose.JUMP -> 0.25f
                    else -> sin(timeSec * 2.8f) * 0.05f
                }

                // Chubby Rounded Clay Legs & Sneakers
                val leftLegBase = Vec3(-0.20f, -0.32f, 0f)
                val leftFoot = Vec3(-0.22f, -0.76f + max(0f, footTap * 0.22f), footTap * 0.36f)
                val rightLegBase = Vec3(0.20f, -0.32f, 0f)
                val rightFoot = Vec3(0.22f, -0.76f + max(0f, -footTap * 0.22f), -footTap * 0.36f)

                primitives += RenderPrimitive.Capsule3D(
                    start = xform(leftLegBase),
                    end = xform(leftFoot),
                    radius = 0.14f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.3f)
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rightLegBase),
                    end = xform(rightFoot),
                    radius = 0.14f,
                    baseColor = sColor,
                    darkColor = darken(sColor, 0.3f)
                )
                // Cute Chunky Clay Sneakers
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(leftFoot),
                    end = xform(Vec3(leftFoot.x, leftFoot.y - 0.03f, leftFoot.z + 0.13f)),
                    radius = 0.17f,
                    baseColor = Color(0xFFFFFFFF),
                    darkColor = Color(0xFFE2E8F0)
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rightFoot),
                    end = xform(Vec3(rightFoot.x, rightFoot.y - 0.03f, rightFoot.z + 0.13f)),
                    radius = 0.17f,
                    baseColor = Color(0xFFFFFFFF),
                    darkColor = Color(0xFFE2E8F0)
                )

                // Pear-shaped Chubby Clay Torso
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0f, 0.16f, 0f)),
                    end = xform(Vec3(0f, -0.26f, 0f)),
                    radius = 0.38f,
                    baseColor = pColor,
                    darkColor = darken(pColor, 0.25f)
                )
                // Soft yellow tummy patch
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(0f, -0.06f, 0.28f)),
                    radius = 0.20f,
                    baseColor = sColor,
                    darkColor = sColor
                )
                // Cute Bowtie
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.11f, 0.16f, 0.32f)),
                    end = xform(Vec3(0.11f, 0.16f, 0.32f)),
                    radius = 0.07f,
                    baseColor = CrimsonAlert,
                    darkColor = Color(0xFFB91C1C),
                    isEmissive = true
                )

                // Cartoon Arms & White Puffy Mittens
                val leftShoulder = Vec3(-0.40f, 0.12f, 0f)
                val leftHand = Vec3(
                    x = -0.62f,
                    y = -0.05f + sin(leftArmSwing) * 0.42f,
                    z = 0.14f + cos(leftArmSwing) * 0.20f
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(leftShoulder),
                    end = xform(leftHand),
                    radius = 0.115f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(leftHand),
                    radius = 0.14f,
                    baseColor = Color(0xFFFFFFFF),
                    darkColor = Color(0xFFCBD5E1)
                )

                val rightShoulder = Vec3(0.40f, 0.12f, 0f)
                val rightHand = Vec3(
                    x = 0.62f,
                    y = -0.05f + sin(rightArmSwing) * 0.42f,
                    z = 0.14f + cos(rightArmSwing) * 0.20f
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(rightShoulder),
                    end = xform(rightHand),
                    radius = 0.115f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(rightHand),
                    radius = 0.14f,
                    baseColor = Color(0xFFFFFFFF),
                    darkColor = Color(0xFFCBD5E1)
                )

                // Chubby Round Head
                val headCenter = Vec3(0f, 0.58f, 0f)
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(headCenter),
                    radius = 0.42f,
                    baseColor = pColor,
                    darkColor = darken(pColor, 0.2f)
                )

                // Big Joyful Cartoon Eyes with Specular Highlights
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.14f, 0.64f, 0.36f)),
                    end = xform(Vec3(-0.14f, 0.58f, 0.36f)),
                    radius = 0.075f,
                    baseColor = Color(0xFF0F172A),
                    darkColor = Color.Black
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(-0.12f, 0.65f, 0.41f)),
                    radius = 0.030f,
                    baseColor = Color.White,
                    darkColor = Color.White,
                    isEmissive = true
                )

                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0.14f, 0.64f, 0.36f)),
                    end = xform(Vec3(0.14f, 0.58f, 0.36f)),
                    radius = 0.075f,
                    baseColor = Color(0xFF0F172A),
                    darkColor = Color.Black
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(0.16f, 0.65f, 0.41f)),
                    radius = 0.030f,
                    baseColor = Color.White,
                    darkColor = Color.White,
                    isEmissive = true
                )

                // Sweet Rosy Cheeks
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(-0.25f, 0.52f, 0.34f)),
                    radius = 0.055f,
                    baseColor = blushColor,
                    darkColor = blushColor,
                    isCheekBlush = true
                )
                primitives += RenderPrimitive.Sphere3D(
                    center = xform(Vec3(0.25f, 0.52f, 0.34f)),
                    radius = 0.055f,
                    baseColor = blushColor,
                    darkColor = blushColor,
                    isCheekBlush = true
                )

                // Cute Smiling Mouth
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.08f, 0.48f, 0.39f)),
                    end = xform(Vec3(0.08f, 0.48f, 0.39f)),
                    radius = 0.035f,
                    baseColor = Color(0xFFE11D48),
                    darkColor = Color(0xFFBE123C)
                )

                // Bouncy Wiggling Bunny/Chibi Ears
                val earWiggle = sin(timeSec * 7f) * 0.10f
                val leftEarTip = Vec3(-0.28f + earWiggle, 1.15f, 0f)
                val rightEarTip = Vec3(0.28f - earWiggle, 1.15f, 0f)

                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.20f, 0.88f, 0f)),
                    end = xform(leftEarTip),
                    radius = 0.12f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0.20f, 0.88f, 0f)),
                    end = xform(rightEarTip),
                    radius = 0.12f,
                    baseColor = pColor,
                    darkColor = sColor
                )
                // Inner ear soft pink accent
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(-0.20f, 0.90f, 0.04f)),
                    end = xform(Vec3(leftEarTip.x, leftEarTip.y - 0.04f, 0.04f)),
                    radius = 0.065f,
                    baseColor = blushColor,
                    darkColor = blushColor
                )
                primitives += RenderPrimitive.Capsule3D(
                    start = xform(Vec3(0.20f, 0.90f, 0.04f)),
                    end = xform(Vec3(rightEarTip.x, rightEarTip.y - 0.04f, 0.04f)),
                    radius = 0.065f,
                    baseColor = blushColor,
                    darkColor = blushColor
                )

                // Floating musical note orbs when Dancing
                if (state.pose == ArCharacterPose.DANCE) {
                    val noteY = 1.05f + ((timeSec * 0.5f) % 0.6f)
                    val noteX = sin(timeSec * 5f) * 0.35f
                    primitives += RenderPrimitive.Sphere3D(
                        center = xform(Vec3(noteX, noteY, 0.25f)),
                        radius = 0.075f,
                        baseColor = Color(0xFFFFD166),
                        darkColor = SignalAmber,
                        isEmissive = true
                    )
                }
            }
        }

        val sorted = primitives.sortedBy { it.sortDepth }
        return sorted to totalLiftY
    }

    /**
     * Draws a single 3D sphere on Compose Canvas with Clay 3D, Frosted Glass, or Liquid Glass shading.
     */
    private fun DrawScope.drawStyledSphere(
        sx: Float,
        sy: Float,
        rPx: Float,
        baseColor: Color,
        darkColor: Color,
        isEmissive: Boolean,
        isCheekBlush: Boolean,
        finish: ArMaterialFinish
    ) {
        if (isCheekBlush) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(baseColor.copy(alpha = 0.65f), Color.Transparent),
                    center = Offset(sx, sy),
                    radius = rPx * 1.2f
                ),
                radius = rPx * 1.2f,
                center = Offset(sx, sy)
            )
            return
        }

        if (isEmissive) {
            drawCircle(
                color = baseColor.copy(alpha = 0.30f),
                radius = rPx * 1.55f,
                center = Offset(sx, sy)
            )
        }

        when (finish) {
            ArMaterialFinish.CLAY_3D -> {
                // Warm Pixar-style Soft Matte Clay shading with subsurface warmth & soft rim
                val warmHighlight = mix(lighten(baseColor, 0.42f), Color(0xFFFFF7ED), 0.30f)
                val midClay = baseColor
                val deepClay = darken(darkColor, 0.18f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(warmHighlight, midClay, deepClay),
                        center = Offset(sx - rPx * 0.32f, sy - rPx * 0.34f),
                        radius = rPx * 1.38f
                    ),
                    radius = rPx,
                    center = Offset(sx, sy)
                )
                // Soft matte pill-shaped clay highlight (broad & diffused, not sharp plastic)
                drawCircle(
                    color = Color.White.copy(alpha = if (isEmissive) 0.70f else 0.24f),
                    radius = rPx * 0.28f,
                    center = Offset(sx - rPx * 0.28f, sy - rPx * 0.30f)
                )
                // Subtle warm rim contour
                drawCircle(
                    color = Color.Black.copy(alpha = 0.18f),
                    radius = rPx,
                    center = Offset(sx, sy),
                    style = Stroke(width = (rPx * 0.06f).coerceAtLeast(1.2f))
                )
            }

            ArMaterialFinish.FROSTED_GLASS -> {
                // Translucent Frosted Acrylic Glass with icy inner refraction & crisp frost rim
                val frostTop = lighten(baseColor, 0.55f).copy(alpha = 0.82f)
                val frostMid = baseColor.copy(alpha = 0.68f)
                val frostDeep = darkColor.copy(alpha = 0.78f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(frostTop, frostMid, frostDeep),
                        center = Offset(sx - rPx * 0.25f, sy - rPx * 0.28f),
                        radius = rPx * 1.3f
                    ),
                    radius = rPx,
                    center = Offset(sx, sy)
                )
                // Frosted inner core glow
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = rPx * 0.52f,
                    center = Offset(sx - rPx * 0.12f, sy - rPx * 0.14f)
                )
                // Crisp frosted ice rim
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.75f),
                            Color(0xFF7DD3FC).copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.25f)
                        ),
                        start = Offset(sx - rPx, sy - rPx),
                        end = Offset(sx + rPx, sy + rPx)
                    ),
                    radius = rPx,
                    center = Offset(sx, sy),
                    style = Stroke(width = (rPx * 0.09f).coerceAtLeast(1.5f))
                )
            }

            ArMaterialFinish.LIQUID_GLASS -> {
                // High-Gloss Liquid Glass with iridescent cyan/magenta/amber sheen & dual speculars
                val glossHighlight = Color.White
                val liquidMid = mix(baseColor, Color(0xFF38BDF8), 0.25f).copy(alpha = 0.88f)
                val liquidDeep = mix(darkColor, Color(0xFF1E1B4B), 0.45f).copy(alpha = 0.92f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glossHighlight, liquidMid, liquidDeep),
                        center = Offset(sx - rPx * 0.34f, sy - rPx * 0.36f),
                        radius = rPx * 1.32f
                    ),
                    radius = rPx,
                    center = Offset(sx, sy)
                )
                // Iridescent caustic rim
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF38BDF8).copy(alpha = 0.80f),
                            Color(0xFFE879F9).copy(alpha = 0.70f),
                            SignalAmber.copy(alpha = 0.75f)
                        ),
                        start = Offset(sx - rPx, sy - rPx),
                        end = Offset(sx + rPx, sy + rPx)
                    ),
                    radius = rPx,
                    center = Offset(sx, sy),
                    style = Stroke(width = (rPx * 0.08f).coerceAtLeast(1.5f))
                )
                // Sharp primary & secondary liquid glass specular dots
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = rPx * 0.22f,
                    center = Offset(sx - rPx * 0.32f, sy - rPx * 0.34f)
                )
                drawCircle(
                    color = Color(0xFFE879F9).copy(alpha = 0.45f),
                    radius = rPx * 0.14f,
                    center = Offset(sx + rPx * 0.28f, sy + rPx * 0.26f)
                )
            }
        }
    }

    /**
     * Draws the 3D character onto a Jetpack Compose [DrawScope].
     */
    fun DrawScope.drawCharacterOnComposeCanvas(
        state: ArCharacterState,
        timeSec: Float
    ) {
        if (!state.enabled) return

        val centerX = size.width * state.normalizedX.coerceIn(0.1f, 0.9f)
        val centerY = size.height * state.normalizedY.coerceIn(0.15f, 0.85f)
        val baseUnitPx = (min(size.width, size.height) * 0.22f * state.scale.coerceIn(0.5f, 2.2f))

        val (primitives, liftY) = buildCharacterScene(state, timeSec)
        val finish = state.materialFinish

        // 1. Ground Shadow & Sculpted Clay / Glass Plinth under the character
        val shadowScale = (1f - liftY * 0.65f).coerceIn(0.45f, 1.1f)
        val (_, groundY, _) = project(Vec3(0f, -0.86f, 0f), centerX, centerY, baseUnitPx)
        val shadowW = baseUnitPx * 1.48f * shadowScale
        val shadowH = baseUnitPx * 0.40f * shadowScale

        // Soft ambient ground shadow
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.56f * shadowScale),
                    finish.accentColor.copy(alpha = 0.20f * shadowScale),
                    Color.Transparent
                ),
                center = Offset(centerX, groundY),
                radius = shadowW * 0.65f
            ),
            topLeft = Offset(centerX - shadowW * 0.5f, groundY - shadowH * 0.5f),
            size = Size(shadowW, shadowH)
        )

        // Material-styled base ring
        drawOval(
            color = finish.accentColor.copy(alpha = 0.55f * shadowScale),
            topLeft = Offset(centerX - shadowW * 0.42f, groundY - shadowH * 0.38f),
            size = Size(shadowW * 0.84f, shadowH * 0.76f),
            style = Stroke(width = if (finish == ArMaterialFinish.CLAY_3D) 3.dp.toPx() else 2.dp.toPx())
        )

        // 2. Render Depth-Sorted 3D Primitives with selected finish (Clay 3D / Frosted Glass / Liquid Glass)
        primitives.forEach { prim ->
            when (prim) {
                is RenderPrimitive.Capsule3D -> {
                    val (sx1, sy1, p1) = project(prim.start, centerX, centerY, baseUnitPx)
                    val (sx2, sy2, p2) = project(prim.end, centerX, centerY, baseUnitPx)
                    val strokeW = (prim.radius * 2f * baseUnitPx * ((p1 + p2) * 0.5f)).coerceAtLeast(4f)

                    if (prim.isEmissive) {
                        drawLine(
                            color = prim.baseColor.copy(alpha = 0.34f),
                            start = Offset(sx1, sy1),
                            end = Offset(sx2, sy2),
                            strokeWidth = strokeW * 1.45f,
                            cap = StrokeCap.Round
                        )
                    }

                    when (finish) {
                        ArMaterialFinish.CLAY_3D -> {
                            // Soft Clay outer ambient contour
                            drawLine(
                                color = darken(prim.darkColor, 0.25f),
                                start = Offset(sx1, sy1),
                                end = Offset(sx2, sy2),
                                strokeWidth = strokeW * 1.04f,
                                cap = StrokeCap.Round
                            )
                            // Main warm sculpted clay body
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        lighten(prim.baseColor, 0.25f),
                                        prim.baseColor,
                                        prim.darkColor
                                    ),
                                    start = Offset(min(sx1, sx2) - strokeW * 0.4f, min(sy1, sy2) - strokeW * 0.4f),
                                    end = Offset(max(sx1, sx2) + strokeW * 0.4f, max(sy1, sy2) + strokeW * 0.4f)
                                ),
                                start = Offset(sx1, sy1),
                                end = Offset(sx2, sy2),
                                strokeWidth = strokeW * 0.96f,
                                cap = StrokeCap.Round
                            )
                            // Soft diffused clay ridge highlight
                            drawLine(
                                color = Color(0xFFFFF7ED).copy(alpha = if (prim.isEmissive) 0.65f else 0.24f),
                                start = Offset(sx1 - strokeW * 0.14f, sy1 - strokeW * 0.14f),
                                end = Offset(sx2 - strokeW * 0.14f, sy2 - strokeW * 0.14f),
                                strokeWidth = strokeW * 0.34f,
                                cap = StrokeCap.Round
                            )
                        }

                        ArMaterialFinish.FROSTED_GLASS -> {
                            // Frosted outer ice border
                            drawLine(
                                color = Color.White.copy(alpha = 0.55f),
                                start = Offset(sx1, sy1),
                                end = Offset(sx2, sy2),
                                strokeWidth = strokeW * 1.04f,
                                cap = StrokeCap.Round
                            )
                            // Translucent frosted body
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        lighten(prim.baseColor, 0.45f).copy(alpha = 0.78f),
                                        prim.baseColor.copy(alpha = 0.65f),
                                        prim.darkColor.copy(alpha = 0.74f)
                                    ),
                                    start = Offset(min(sx1, sx2) - strokeW * 0.3f, min(sy1, sy2) - strokeW * 0.3f),
                                    end = Offset(max(sx1, sx2) + strokeW * 0.3f, max(sy1, sy2) + strokeW * 0.3f)
                                ),
                                start = Offset(sx1, sy1),
                                end = Offset(sx2, sy2),
                                strokeWidth = strokeW * 0.92f,
                                cap = StrokeCap.Round
                            )
                            // Icy inner refraction core
                            drawLine(
                                color = Color.White.copy(alpha = 0.40f),
                                start = Offset(sx1 - strokeW * 0.12f, sy1 - strokeW * 0.12f),
                                end = Offset(sx2 - strokeW * 0.12f, sy2 - strokeW * 0.12f),
                                strokeWidth = strokeW * 0.26f,
                                cap = StrokeCap.Round
                            )
                        }

                        ArMaterialFinish.LIQUID_GLASS -> {
                            // Iridescent liquid glass outer sheen
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF38BDF8).copy(alpha = 0.85f),
                                        Color(0xFFE879F9).copy(alpha = 0.75f),
                                        SignalAmber.copy(alpha = 0.80f)
                                    ),
                                    start = Offset(sx1 - strokeW, sy1 - strokeW),
                                    end = Offset(sx2 + strokeW, sy2 + strokeW)
                                ),
                                start = Offset(sx1, sy1),
                                end = Offset(sx2, sy2),
                                strokeWidth = strokeW * 1.05f,
                                cap = StrokeCap.Round
                            )
                            // High-gloss liquid body
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.92f),
                                        mix(prim.baseColor, Color(0xFF38BDF8), 0.25f).copy(alpha = 0.86f),
                                        mix(prim.darkColor, Color(0xFF1E1B4B), 0.45f).copy(alpha = 0.92f)
                                    ),
                                    start = Offset(min(sx1, sx2) - strokeW * 0.4f, min(sy1, sy2) - strokeW * 0.4f),
                                    end = Offset(max(sx1, sx2) + strokeW * 0.4f, max(sy1, sy2) + strokeW * 0.4f)
                                ),
                                start = Offset(sx1, sy1),
                                end = Offset(sx2, sy2),
                                strokeWidth = strokeW * 0.90f,
                                cap = StrokeCap.Round
                            )
                            // Sharp liquid glass specular streak
                            drawLine(
                                color = Color.White.copy(alpha = 0.78f),
                                start = Offset(sx1 - strokeW * 0.18f, sy1 - strokeW * 0.18f),
                                end = Offset(sx2 - strokeW * 0.18f, sy2 - strokeW * 0.18f),
                                strokeWidth = strokeW * 0.20f,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }

                is RenderPrimitive.Sphere3D -> {
                    val (sx, sy, persp) = project(prim.center, centerX, centerY, baseUnitPx)
                    val rPx = (prim.radius * baseUnitPx * persp).coerceAtLeast(3f)
                    drawStyledSphere(
                        sx = sx,
                        sy = sy,
                        rPx = rPx,
                        baseColor = prim.baseColor,
                        darkColor = prim.darkColor,
                        isEmissive = prim.isEmissive,
                        isCheekBlush = prim.isCheekBlush,
                        finish = finish
                    )
                }

                is RenderPrimitive.RotorDisk3D -> {
                    val (sx, sy, persp) = project(prim.center, centerX, centerY, baseUnitPx)
                    val rx = prim.radius * baseUnitPx * persp
                    val ry = rx * 0.34f
                    drawOval(
                        color = prim.glowColor.copy(alpha = 0.28f),
                        topLeft = Offset(sx - rx, sy - ry),
                        size = Size(rx * 2f, ry * 2f)
                    )
                    drawOval(
                        color = prim.glowColor.copy(alpha = 0.75f),
                        topLeft = Offset(sx - rx, sy - ry),
                        size = Size(rx * 2f, ry * 2f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    val bx = cos(prim.spinPhase) * rx * 0.9f
                    val by = sin(prim.spinPhase) * ry * 0.9f
                    drawLine(
                        color = Color.White.copy(alpha = 0.88f),
                        start = Offset(sx - bx, sy - by),
                        end = Offset(sx + bx, sy + by),
                        strokeWidth = 2.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // 3. Optional Floating Speech Bubble above the character's head
        if (state.showSpeechBubble) {
            val (_, headTopY, _) = project(Vec3(0f, 1.18f + liftY, 0f), centerX, centerY, baseUnitPx)
            val bubbleW = (baseUnitPx * 2.15f).coerceIn(165.dp.toPx(), 265.dp.toPx())
            val bubbleH = 38.dp.toPx()
            val bubbleLeft = (centerX - bubbleW * 0.5f).coerceIn(12.dp.toPx(), size.width - bubbleW - 12.dp.toPx())
            val bubbleTop = (headTopY - bubbleH - 12.dp.toPx()).coerceAtLeast(76.dp.toPx())

            val bubbleBg = when (finish) {
                ArMaterialFinish.CLAY_3D -> Color(0xFF1E293B)
                ArMaterialFinish.FROSTED_GLASS -> Color(0xCC162436)
                ArMaterialFinish.LIQUID_GLASS -> Color(0xD9111827)
            }

            drawRoundRect(
                color = bubbleBg,
                topLeft = Offset(bubbleLeft, bubbleTop),
                size = Size(bubbleW, bubbleH),
                cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
            )
            drawRoundRect(
                color = finish.accentColor,
                topLeft = Offset(bubbleLeft, bubbleTop),
                size = Size(bubbleW, bubbleH),
                cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                style = Stroke(width = 1.8.dp.toPx())
            )

            // Draw speech text via native canvas for crisp monospace rendering
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 11.5.sp.toPx()
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val textY = bubbleTop + bubbleH * 0.64f
            nativeCanvas.drawText(
                state.activeSpeechText,
                bubbleLeft + bubbleW * 0.5f,
                textY,
                textPaint
            )
        }
    }

    /**
     * Composites the active 3D character (including its selected Clay 3D / Frosted Glass / Liquid Glass finish)
     * directly onto an Android [android.graphics.Canvas] for saving into the captured photo [Bitmap].
     */
    fun drawCharacterOnAndroidCanvas(
        canvas: android.graphics.Canvas,
        width: Int,
        height: Int,
        state: ArCharacterState,
        timeSec: Float
    ) {
        if (!state.enabled) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w * state.normalizedX.coerceIn(0.1f, 0.9f)
        val centerY = h * state.normalizedY.coerceIn(0.15f, 0.85f)
        val baseUnitPx = (min(w, h) * 0.22f * state.scale.coerceIn(0.5f, 2.2f))

        val (primitives, liftY) = buildCharacterScene(state, timeSec)
        val finish = state.materialFinish
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Ground Shadow
        val shadowScale = (1f - liftY * 0.65f).coerceIn(0.45f, 1.1f)
        val (_, groundY, _) = project(Vec3(0f, -0.86f, 0f), centerX, centerY, baseUnitPx)
        val shadowW = baseUnitPx * 1.48f * shadowScale
        val shadowH = baseUnitPx * 0.40f * shadowScale

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            centerX,
            groundY,
            (shadowW * 0.65f).coerceAtLeast(1f),
            intArrayOf(
                Color.Black.copy(alpha = 0.56f * shadowScale).toArgb(),
                finish.accentColor.copy(alpha = 0.20f * shadowScale).toArgb(),
                android.graphics.Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            RectF(
                centerX - shadowW * 0.5f,
                groundY - shadowH * 0.5f,
                centerX + shadowW * 0.5f,
                groundY + shadowH * 0.5f
            ),
            paint
        )
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(3f, baseUnitPx * 0.03f)
        paint.color = finish.accentColor.copy(alpha = 0.55f * shadowScale).toArgb()
        canvas.drawOval(
            RectF(
                centerX - shadowW * 0.42f,
                groundY - shadowH * 0.38f,
                centerX + shadowW * 0.42f,
                groundY + shadowH * 0.38f
            ),
            paint
        )

        // 2. Render Depth-Sorted 3D Primitives onto Bitmap
        primitives.forEach { prim ->
            when (prim) {
                is RenderPrimitive.Capsule3D -> {
                    val (sx1, sy1, p1) = project(prim.start, centerX, centerY, baseUnitPx)
                    val (sx2, sy2, p2) = project(prim.end, centerX, centerY, baseUnitPx)
                    val strokeW = (prim.radius * 2f * baseUnitPx * ((p1 + p2) * 0.5f)).coerceAtLeast(4f)

                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND

                    if (prim.isEmissive) {
                        paint.shader = null
                        paint.strokeWidth = strokeW * 1.45f
                        paint.color = prim.baseColor.copy(alpha = 0.34f).toArgb()
                        canvas.drawLine(sx1, sy1, sx2, sy2, paint)
                    }

                    // Outer rim
                    paint.shader = null
                    paint.strokeWidth = strokeW * 1.04f
                    paint.color = when (finish) {
                        ArMaterialFinish.CLAY_3D -> darken(prim.darkColor, 0.25f).toArgb()
                        ArMaterialFinish.FROSTED_GLASS -> Color.White.copy(alpha = 0.55f).toArgb()
                        ArMaterialFinish.LIQUID_GLASS -> Color(0xFF38BDF8).copy(alpha = 0.80f).toArgb()
                    }
                    canvas.drawLine(sx1, sy1, sx2, sy2, paint)

                    // Main shaded volume
                    paint.strokeWidth = strokeW * 0.94f
                    val cTop = when (finish) {
                        ArMaterialFinish.CLAY_3D -> lighten(prim.baseColor, 0.25f)
                        ArMaterialFinish.FROSTED_GLASS -> lighten(prim.baseColor, 0.45f).copy(alpha = 0.80f)
                        ArMaterialFinish.LIQUID_GLASS -> Color.White.copy(alpha = 0.92f)
                    }
                    val cMid = when (finish) {
                        ArMaterialFinish.CLAY_3D -> prim.baseColor
                        ArMaterialFinish.FROSTED_GLASS -> prim.baseColor.copy(alpha = 0.68f)
                        ArMaterialFinish.LIQUID_GLASS -> mix(prim.baseColor, Color(0xFF38BDF8), 0.25f)
                    }
                    paint.shader = LinearGradient(
                        min(sx1, sx2) - strokeW * 0.4f,
                        min(sy1, sy2) - strokeW * 0.4f,
                        max(sx1, sx2) + strokeW * 0.4f,
                        max(sy1, sy2) + strokeW * 0.4f,
                        intArrayOf(cTop.toArgb(), cMid.toArgb(), prim.darkColor.toArgb()),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawLine(sx1, sy1, sx2, sy2, paint)
                    paint.shader = null

                    // Specular / Clay highlight
                    paint.strokeWidth = strokeW * (if (finish == ArMaterialFinish.CLAY_3D) 0.32f else 0.22f)
                    paint.color = Color.White.copy(alpha = if (finish == ArMaterialFinish.LIQUID_GLASS) 0.78f else 0.30f).toArgb()
                    canvas.drawLine(
                        sx1 - strokeW * 0.14f,
                        sy1 - strokeW * 0.14f,
                        sx2 - strokeW * 0.14f,
                        sy2 - strokeW * 0.14f,
                        paint
                    )
                }

                is RenderPrimitive.Sphere3D -> {
                    val (sx, sy, persp) = project(prim.center, centerX, centerY, baseUnitPx)
                    val rPx = (prim.radius * baseUnitPx * persp).coerceAtLeast(3f)
                    paint.style = Paint.Style.FILL

                    if (prim.isCheekBlush) {
                        paint.shader = RadialGradient(
                            sx, sy, (rPx * 1.2f).coerceAtLeast(1f),
                            intArrayOf(prim.baseColor.copy(alpha = 0.65f).toArgb(), android.graphics.Color.TRANSPARENT),
                            floatArrayOf(0f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawCircle(sx, sy, rPx * 1.2f, paint)
                        paint.shader = null
                    } else {
                        if (prim.isEmissive) {
                            paint.shader = null
                            paint.color = prim.baseColor.copy(alpha = 0.30f).toArgb()
                            canvas.drawCircle(sx, sy, rPx * 1.55f, paint)
                        }

                        val highlightColor = when (finish) {
                            ArMaterialFinish.CLAY_3D -> mix(lighten(prim.baseColor, 0.42f), Color(0xFFFFF7ED), 0.30f)
                            ArMaterialFinish.FROSTED_GLASS -> lighten(prim.baseColor, 0.55f).copy(alpha = 0.85f)
                            ArMaterialFinish.LIQUID_GLASS -> Color.White
                        }
                        paint.shader = RadialGradient(
                            sx - rPx * 0.32f,
                            sy - rPx * 0.34f,
                            (rPx * 1.35f).coerceAtLeast(1f),
                            intArrayOf(
                                highlightColor.toArgb(),
                                prim.baseColor.toArgb(),
                                prim.darkColor.toArgb()
                            ),
                            floatArrayOf(0f, 0.55f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawCircle(sx, sy, rPx, paint)
                        paint.shader = null

                        // Specular highlight dot
                        paint.color = Color.White.copy(
                            alpha = when (finish) {
                                ArMaterialFinish.CLAY_3D -> 0.26f
                                ArMaterialFinish.FROSTED_GLASS -> 0.40f
                                ArMaterialFinish.LIQUID_GLASS -> 0.85f
                            }
                        ).toArgb()
                        canvas.drawCircle(sx - rPx * 0.28f, sy - rPx * 0.30f, rPx * 0.24f, paint)
                    }
                }

                is RenderPrimitive.RotorDisk3D -> {
                    val (sx, sy, persp) = project(prim.center, centerX, centerY, baseUnitPx)
                    val rx = prim.radius * baseUnitPx * persp
                    val ry = rx * 0.34f
                    val oval = RectF(sx - rx, sy - ry, sx + rx, sy + ry)

                    paint.style = Paint.Style.FILL
                    paint.shader = null
                    paint.color = prim.glowColor.copy(alpha = 0.28f).toArgb()
                    canvas.drawOval(oval, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = max(2.5f, baseUnitPx * 0.025f)
                    paint.color = prim.glowColor.copy(alpha = 0.78f).toArgb()
                    canvas.drawOval(oval, paint)

                    val bx = cos(prim.spinPhase) * rx * 0.9f
                    val by = sin(prim.spinPhase) * ry * 0.9f
                    paint.color = Color.White.copy(alpha = 0.88f).toArgb()
                    canvas.drawLine(sx - bx, sy - by, sx + bx, sy + by, paint)
                }
            }
        }

        // 3. Optional Speech Bubble on Saved Photo
        if (state.showSpeechBubble) {
            val (_, headTopY, _) = project(Vec3(0f, 1.18f + liftY, 0f), centerX, centerY, baseUnitPx)
            val bubbleW = (baseUnitPx * 2.15f).coerceIn(w * 0.36f, w * 0.72f)
            val bubbleH = baseUnitPx * 0.36f
            val bubbleLeft = (centerX - bubbleW * 0.5f).coerceIn(w * 0.04f, w - bubbleW - w * 0.04f)
            val bubbleTop = (headTopY - bubbleH - baseUnitPx * 0.12f).coerceAtLeast(h * 0.05f)
            val rect = RectF(bubbleLeft, bubbleTop, bubbleLeft + bubbleW, bubbleTop + bubbleH)
            val corner = bubbleH * 0.45f

            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = HudGlassOverlay.toArgb()
            canvas.drawRoundRect(rect, corner, corner, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(3f, baseUnitPx * 0.025f)
            paint.color = finish.accentColor.toArgb()
            canvas.drawRoundRect(rect, corner, corner, paint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = bubbleH * 0.38f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                state.activeSpeechText,
                bubbleLeft + bubbleW * 0.5f,
                bubbleTop + bubbleH * 0.64f,
                textPaint
            )
        }
    }

    /**
     * Loads the captured JPEG file, composites the 3D character at its exact screen position/pose/finish,
     * and overwrites the file with the composited image.
     */
    fun compositeCharacterOntoPhotoFile(
        photoFile: File,
        state: ArCharacterState,
        timeSec: Float,
        isFrontCamera: Boolean
    ) {
        if (!state.enabled || !photoFile.exists()) return
        try {
            val exif = ExifInterface(photoFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rawBitmap = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            }
            if (isFrontCamera) {
                matrix.postScale(-1f, 1f)
            }

            val orientedBitmap = Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true
            )
            val mutableBitmap = orientedBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val androidCanvas = android.graphics.Canvas(mutableBitmap)

            drawCharacterOnAndroidCanvas(
                canvas = androidCanvas,
                width = mutableBitmap.width,
                height = mutableBitmap.height,
                state = state,
                timeSec = timeSec
            )

            FileOutputStream(photoFile).use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
                out.flush()
            }

            val updatedExif = ExifInterface(photoFile.absolutePath)
            updatedExif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            updatedExif.saveAttributes()
        } catch (e: Exception) {
            android.util.Log.e("ArCharacterStudio", "Failed to composite 3D character onto photo: ${e.message}")
        }
    }
}

/**
 * Polished Clay 3D / Frosted Glass / Liquid Glass Studio Control Card displayed at the bottom of the Camera screen
 * ONLY when `state.enabled == true`.
 */
@Composable
fun ArCharacterControlPanel(
    state: ArCharacterState,
    onStateChange: (ArCharacterState) -> Unit,
    onHide3D: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeStyle = state.materialFinish.surfaceStyle
    val accent = state.materialFinish.accentColor

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .surfaceFinishOverlay(activeStyle, activeStyle.cornerRadiusDp.dp, accent)
            .testTag("ar_character_control_panel"),
        color = activeStyle.cardColor,
        shape = RoundedCornerShape(activeStyle.cornerRadiusDp.dp),
        border = BorderStroke(1.2.dp, activeStyle.borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Studio Badge + Speech Bubble Toggle + 1-Tap "Hide 3D"
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
                        color = accent.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, accent)
                    ) {
                        Text(
                            text = state.materialFinish.displayName.uppercase(),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Drag to place • Pinch to scale",
                        fontFamily = SpaceGroteskFontFamily,
                        fontSize = 11.sp,
                        color = HudTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Speech Bubble Toggle / Cycle Button
                Surface(
                    onClick = {
                        if (!state.showSpeechBubble) {
                            onStateChange(state.copy(showSpeechBubble = true))
                        } else if (state.speechIndex < 2) {
                            onStateChange(state.copy(speechIndex = state.speechIndex + 1))
                        } else {
                            onStateChange(state.copy(showSpeechBubble = false, speechIndex = 0))
                        }
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .testTag("ar_speech_bubble_toggle"),
                    shape = RoundedCornerShape(10.dp),
                    color = if (state.showSpeechBubble) CyberMint.copy(alpha = 0.20f) else HudSurfaceContainer,
                    border = BorderStroke(1.dp, if (state.showSpeechBubble) CyberMint else HudBorderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = "Speech Bubble",
                            tint = if (state.showSpeechBubble) CyberMint else HudTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (state.showSpeechBubble) "Speech ON" else "Speech",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = if (state.showSpeechBubble) HudTextPrimary else HudTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 1-Tap "Hide 3D" Button
                Surface(
                    onClick = onHide3D,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .testTag("ar_hide_3d_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = CrimsonAlert.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, CrimsonAlert)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Hide 3D",
                            tint = CrimsonAlert,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Hide 3D",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Row 1: Material Finish Selector (Clay 3D / Frosted Glass / Liquid Glass)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArMaterialFinish.entries.forEach { finish ->
                    val isSelected = state.materialFinish == finish
                    Surface(
                        onClick = { onStateChange(state.copy(materialFinish = finish)) },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .surfaceFinishOverlay(finish.surfaceStyle, 12.dp, finish.accentColor)
                            .testTag("ar_finish_${finish.id}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) finish.accentColor.copy(alpha = 0.24f) else finish.surfaceStyle.cardColor,
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 1.dp,
                            if (isSelected) finish.accentColor else finish.surfaceStyle.borderColor
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = when (finish) {
                                    ArMaterialFinish.CLAY_3D -> Icons.Default.AutoAwesome
                                    ArMaterialFinish.FROSTED_GLASS -> Icons.Default.BlurOn
                                    ArMaterialFinish.LIQUID_GLASS -> Icons.Default.WaterDrop
                                },
                                contentDescription = finish.displayName,
                                tint = if (isSelected) finish.accentColor else HudTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = finish.displayName,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isSelected) HudTextPrimary else HudTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Row 2: 3 Selectable 3D Characters (Cyber Bot, Astro Explorer, Field Drone)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArCharacterType.entries.forEach { character ->
                    val isSelected = state.character == character
                    Surface(
                        onClick = { onStateChange(state.copy(character = character)) },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .surfaceFinishOverlay(activeStyle, 12.dp, character.visorColor)
                            .testTag("ar_character_${character.id}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) character.visorColor.copy(alpha = 0.22f) else activeStyle.elevatedColor,
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 1.dp,
                            if (isSelected) character.visorColor else activeStyle.borderColor
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = character.badgeLabel,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = if (isSelected) character.visorColor else HudTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = character.displayName,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isSelected) HudTextPrimary else HudTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Row 3: 4 Interactive Poses (Wave, Dance, Jump, Salute)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArCharacterPose.entries.forEach { pose ->
                    val isSelected = state.pose == pose
                    Surface(
                        onClick = { onStateChange(state.copy(pose = pose)) },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .surfaceFinishOverlay(activeStyle, 12.dp, SignalAmber)
                            .testTag("ar_pose_${pose.id}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) SignalAmber.copy(alpha = 0.22f) else activeStyle.elevatedColor,
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 1.dp,
                            if (isSelected) SignalAmber else activeStyle.borderColor
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = pose.icon,
                                contentDescription = pose.displayName,
                                tint = if (isSelected) SignalAmber else HudTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = pose.displayName,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isSelected) HudTextPrimary else HudTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Row 4: Compact Rotation & Scale Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rotate Left/Right 45°
                Surface(
                    onClick = {
                        onStateChange(state.copy(rotationYDeg = (state.rotationYDeg + 45f) % 360f))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 42.dp)
                        .testTag("ar_rotate_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = activeStyle.elevatedColor,
                    border = BorderStroke(1.dp, activeStyle.borderColor)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RotateRight,
                            contentDescription = "Rotate 3D",
                            tint = TelemetryBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Turn ${state.rotationYDeg.toInt()}°",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Scale Smaller
                Surface(
                    onClick = {
                        onStateChange(state.copy(scale = (state.scale - 0.15f).coerceAtLeast(0.55f)))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 42.dp)
                        .testTag("ar_scale_down_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = activeStyle.elevatedColor,
                    border = BorderStroke(1.dp, activeStyle.borderColor)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Smaller",
                            tint = HudTextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Size -",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Scale Bigger
                Surface(
                    onClick = {
                        onStateChange(state.copy(scale = (state.scale + 0.15f).coerceAtMost(2.0f)))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 42.dp)
                        .testTag("ar_scale_up_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = activeStyle.elevatedColor,
                    border = BorderStroke(1.dp, activeStyle.borderColor)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Bigger",
                            tint = CyberMint,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Size +",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Reset Position
                Surface(
                    onClick = {
                        onStateChange(
                            state.copy(
                                normalizedX = 0.5f,
                                normalizedY = 0.54f,
                                scale = 1.05f,
                                rotationYDeg = 12f
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 42.dp)
                        .testTag("ar_reset_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = activeStyle.elevatedColor,
                    border = BorderStroke(1.dp, activeStyle.borderColor)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CenterFocusStrong,
                            contentDescription = "Center",
                            tint = SignalAmber,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Center",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
