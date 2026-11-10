package com.example.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.UserDto
import com.example.data.repository.FirebaseManager
import com.example.data.repository.RegisterResult
import com.example.ui.components.CountryCodePickerModal
import com.example.ui.components.surfaceFinishOverlay
import com.example.ui.theme.*
import com.example.util.CountryCodes
import com.example.util.DeviceUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class OnboardingSlideData(
    val imageRes: Int,
    val badgeTag: String,
    val title: String,
    val subtitle: String,
    val accentColor: Color,
    val secondaryGlow: Color,
    val floatingPills: List<String>
)

private val onboardingSlides = listOf(
    OnboardingSlideData(
        imageRes = R.drawable.img_rb_voip_call,
        badgeTag = "ULTRA HD OPUS VOIP",
        title = "Crystal-Clear Calls, Zero Dropouts",
        subtitle = "Instant 0ms connection with 48kHz Opus HD voice, acoustic noise shield, and smart dual-SIM routing.",
        accentColor = SignalAmber,
        secondaryGlow = Color(0xFF38BDF8),
        floatingPills = listOf("⚡ 0ms Connection", "🎙️ 48kHz Opus HD", "📶 Dual-SIM Ready")
    ),
    OnboardingSlideData(
        imageRes = R.drawable.img_rb_video_call,
        badgeTag = "4K E2E ENCRYPTED VIDEO",
        title = "Peer-to-Peer 4K Video Calls",
        subtitle = "Zero-latency 4K video streams with DTLS-SRTP cryptographic security and translucent glass controls.",
        accentColor = CyberMint,
        secondaryGlow = TelemetryBlue,
        floatingPills = listOf("🔒 DTLS-SRTP E2EE", "📹 4K HDR Video", "🎛️ Glass Controls")
    ),
    OnboardingSlideData(
        imageRes = R.drawable.img_rb_secure_chat,
        badgeTag = "INSTANT DIRECT P2P CHAT",
        title = "Fast & Private Messaging",
        subtitle = "Direct peer-to-peer end-to-end encrypted messaging with zero server tracking and instant media sharing.",
        accentColor = TelemetryBlue,
        secondaryGlow = Color(0xFFE879F9),
        floatingPills = listOf("🛡️ E2EE Verified", "⚡ Instant Sync", "💬 Private Roster")
    )
)

@Composable
private fun OnboardingStepHeader(
    currentStep: Int,
    totalSteps: Int = 3,
    stepLabel: String,
    accentColor: Color = SignalAmber,
    onBackClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBackClick != null) {
                    Surface(
                        onClick = onBackClick,
                        shape = CircleShape,
                        color = HudSurfaceContainer,
                        border = BorderStroke(1.dp, HudBorderSubtle),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("onboarding_back_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = HudTextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0A101E),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_rb_app_icon),
                            contentDescription = "RB DIALER Logo",
                            alignment = Alignment.Center,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column {
                    Text(
                        text = "STEP 0$currentStep OF 0$totalSteps",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stepLabel,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = HudTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (trailingContent != null) {
                trailingContent()
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Segmented step progress bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (step in 1..totalSteps) {
                val isCompletedOrActive = step <= currentStep
                val barColor by animateColorAsState(
                    targetValue = if (step == currentStep) accentColor
                    else if (isCompletedOrActive) CyberMint
                    else HudBorderSubtle.copy(alpha = 0.45f),
                    label = "step_bar_color_$step"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(barColor)
                )
            }
        }
    }
}

@Composable
fun WelcomeScreen(
    onGetStartedClick: () -> Unit
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val currentSurfaceStyle by themeManager.currentSurfaceStyle.collectAsState()

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { onboardingSlides.size })
    var lastUserInteractionMs by remember { mutableLongStateOf(0L) }

    // Reset auto-scroll timer whenever a scroll gesture or animation is active
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            lastUserInteractionMs = System.currentTimeMillis()
        }
    }

    // Smooth, non-cancelling auto-advance loop so pages never stop halfway during scroll
    LaunchedEffect(Unit) {
        while (true) {
            delay(4500L)
            val idleTimeMs = System.currentTimeMillis() - lastUserInteractionMs
            if (!pagerState.isScrollInProgress && idleTimeMs >= 4200L) {
                val nextPage = (pagerState.settledPage + 1) % onboardingSlides.size
                pagerState.animateScrollToPage(
                    page = nextPage,
                    animationSpec = tween(
                        durationMillis = 500,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        }
    }

    val currentSlide = onboardingSlides[pagerState.currentPage.coerceIn(0, onboardingSlides.lastIndex)]
    val activeAccent by animateColorAsState(
        targetValue = currentSlide.accentColor,
        animationSpec = tween(400),
        label = "slide_accent"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Ambient radial stage glow behind the still studio artwork
        Canvas(modifier = Modifier.fillMaxSize()) {
            val glowCenter = Offset(size.width * 0.5f, size.height * 0.36f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = 0.20f),
                        currentSlide.secondaryGlow.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = glowCenter,
                    radius = size.minDimension * 0.70f
                ),
                center = glowCenter,
                radius = size.minDimension * 0.70f
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top Header with Step Progress + SKIP Pill
            OnboardingStepHeader(
                currentStep = 1,
                totalSteps = 3,
                stepLabel = "RB DIALER • NEXT-GEN TELEPHONY",
                accentColor = activeAccent,
                trailingContent = {
                    Surface(
                        onClick = onGetStartedClick,
                        shape = RoundedCornerShape(50),
                        color = HudSurfaceContainer,
                        border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("onboarding_skip_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SKIP",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = HudTextPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Skip to Login",
                                tint = activeAccent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            )

            // 2. Center Still 3D Studio Visual Showcase Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                HorizontalPager(
                    state = pagerState,
                    pageSpacing = 16.dp,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    val slide = onboardingSlides[pageIndex]

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Framed 3D Still Studio Render Card
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.92f)
                                .shadow(
                                    elevation = 18.dp,
                                    shape = RoundedCornerShape(28.dp),
                                    spotColor = slide.accentColor
                                ),
                            shape = RoundedCornerShape(28.dp),
                            color = Color(0xFF0B1120),
                            border = BorderStroke(1.5.dp, slide.accentColor.copy(alpha = 0.55f))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Centered 3D Studio Artwork Stage
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF0B1120)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = slide.imageRes),
                                        contentDescription = slide.title,
                                        alignment = Alignment.Center,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    // Subtle bottom gradient blend into the pill bar
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .align(Alignment.BottomCenter)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        Color(0xFF0B1120)
                                                    )
                                                )
                                            )
                                    )
                                }

                                // Centered Feature Pill Dock below the artwork
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0B1120))
                                        .padding(horizontal = 10.dp, vertical = 12.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(
                                        8.dp,
                                        Alignment.CenterHorizontally
                                    ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    slide.floatingPills.forEachIndexed { pillIdx, pillText ->
                                        val pillAccent = when (pillIdx) {
                                            0 -> slide.accentColor
                                            1 -> slide.secondaryGlow
                                            else -> CyberMint
                                        }
                                        FloatingFeaturePill(
                                            text = pillText,
                                            accentColor = pillAccent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Sculpted Bottom Story & Action Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .surfaceFinishOverlay(
                        style = currentSurfaceStyle,
                        cornerRadius = 24.dp,
                        accentColor = activeAccent
                    ),
                shape = RoundedCornerShape(24.dp),
                color = HudSurfaceContainer,
                border = BorderStroke(1.2.dp, activeAccent.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Animated Pager Indicator Dots + Feature Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = activeAccent.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = null,
                                    tint = activeAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = currentSlide.badgeTag,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = activeAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Animated Pill Dots
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            onboardingSlides.indices.forEach { idx ->
                                val isCurrent = pagerState.currentPage == idx
                                val dotWidth by animateDpAsState(
                                    targetValue = if (isCurrent) 22.dp else 7.dp,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                    label = "dot_w_$idx"
                                )
                                Box(
                                    modifier = Modifier
                                        .height(7.dp)
                                        .width(dotWidth)
                                        .clip(CircleShape)
                                        .background(
                                            if (isCurrent) activeAccent else HudBorderSubtle
                                        )
                                        .clickable {
                                            lastUserInteractionMs = System.currentTimeMillis()
                                            scope.launch {
                                                pagerState.animateScrollToPage(
                                                    page = idx,
                                                    animationSpec = tween(
                                                        durationMillis = 450,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            }
                                        }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = currentSlide.title,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = HudTextPrimary,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = currentSlide.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Primary CTA Row: Next Slide + Get Started
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (pagerState.currentPage < onboardingSlides.lastIndex) {
                            OutlinedButton(
                                onClick = {
                                    lastUserInteractionMs = System.currentTimeMillis()
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            page = (pagerState.currentPage + 1).coerceAtMost(onboardingSlides.lastIndex),
                                            animationSpec = tween(
                                                durationMillis = 450,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .weight(0.36f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.2.dp, activeAccent.copy(alpha = 0.65f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = HudTextPrimary
                                )
                            ) {
                                Text(
                                    text = "NEXT",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        Button(
                            onClick = onGetStartedClick,
                            modifier = Modifier
                                .weight(if (pagerState.currentPage < onboardingSlides.lastIndex) 0.64f else 1f)
                                .height(52.dp)
                                .testTag("initialize_terminal_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = activeAccent,
                                contentColor = Color(0xFF0B1118)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "GET STARTED",
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingFeaturePill(
    text: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color(0xD90F172A),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.70f)),
        shadowElevation = 6.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneInputScreen(
    firebaseManager: FirebaseManager,
    onLoginSuccess: (UserDto) -> Unit,
    onNewUser: (phoneNumber: String, deviceId: String) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val surfaceStyle = LocalSurfaceStyle.current

    val currentDeviceId = remember { DeviceUtils.getDeviceId(context) }
    val detectedSimNumber = remember { DeviceUtils.detectSimPhoneNumber(context) }

    var selectedCountry by remember { mutableStateOf(CountryCodes.defaultCountry) }
    var phoneNumberInput by remember {
        mutableStateOf(detectedSimNumber?.takeLast(10) ?: "")
    }
    var showCountryPicker by remember { mutableStateOf(false) }
    var isCheckingSecurity by remember { mutableStateOf(false) }
    var blockedErrorMessage by remember { mutableStateOf<String?>(null) }

    if (showCountryPicker) {
        CountryCodePickerModal(
            selectedCountry = selectedCountry,
            onCountrySelected = { selectedCountry = it },
            onDismissRequest = { showCountryPicker = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        OnboardingStepHeader(
            currentStep = 2,
            totalSteps = 3,
            stepLabel = "VERIFY YOUR PHONE NUMBER",
            accentColor = SignalAmber,
            onBackClick = onBackClick
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val cardAccent = when {
                blockedErrorMessage != null -> CrimsonAlert
                phoneNumberInput.length >= 7 -> CyberMint
                else -> SignalAmber
            }

            // Still Studio Hero Banner for Phone Verification
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .surfaceFinishOverlay(
                        style = surfaceStyle,
                        cornerRadius = 22.dp,
                        accentColor = cardAccent
                    ),
                shape = RoundedCornerShape(22.dp),
                color = HudSurfaceContainer,
                border = BorderStroke(1.2.dp, cardAccent.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(84.dp),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, cardAccent.copy(alpha = 0.5f))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_rb_app_icon),
                            contentDescription = "RB DIALER Security",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            color = cardAccent.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, cardAccent.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = when {
                                    blockedErrorMessage != null -> "NUMBER PROTECTED"
                                    isCheckingSecurity -> "VERIFYING..."
                                    phoneNumberInput.length >= 10 -> "READY TO CONNECT"
                                    detectedSimNumber != null -> "SIM AUTO-DETECTED"
                                    else -> "E2EE PROTECTED"
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = cardAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Sign In with Phone Number",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Your account is protected with end-to-end encryption so only you can access your calls and messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = HudTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // End-to-End Encryption Verification Chip (No internal Hardware ID exposed)
            Surface(
                color = HudSurfaceContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, HudBorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .surfaceFinishOverlay(
                        style = surfaceStyle,
                        cornerRadius = 16.dp,
                        accentColor = CyberMint
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = CyberMint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "END-TO-END ENCRYPTED LINE",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = CyberMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Anti-Hijack Account Protection Active",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            color = HudTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        color = CyberMint.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "VERIFIED",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = CyberMint,
                            maxLines = 1
                        )
                    }
                }
            }

            // Country Picker Box
            Surface(
                onClick = { showCountryPicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .surfaceFinishOverlay(
                        style = surfaceStyle,
                        cornerRadius = 16.dp,
                        accentColor = SignalAmber
                    ),
                shape = RoundedCornerShape(16.dp),
                color = HudSurfaceContainer,
                border = BorderStroke(1.dp, HudBorderSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = selectedCountry.flagEmoji, fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${selectedCountry.countryName} (${selectedCountry.dialCode})",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = HudTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "CHANGE",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = SignalAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Phone Number Input
            OutlinedTextField(
                value = phoneNumberInput,
                onValueChange = {
                    blockedErrorMessage = null
                    if (it.length <= 15) phoneNumberInput = it.filter { ch -> ch.isDigit() }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("phone_number_input"),
                placeholder = {
                    Text(
                        "Enter 10-digit mobile number",
                        color = HudTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = HudTextPrimary
                ),
                leadingIcon = {
                    Text(
                        text = selectedCountry.dialCode,
                        modifier = Modifier.padding(start = 12.dp),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = SignalAmber
                    )
                },
                trailingIcon = {
                    if (phoneNumberInput.isNotEmpty()) {
                        IconButton(onClick = { phoneNumberInput = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear number",
                                tint = HudTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = HudSurfaceContainer,
                    unfocusedContainerColor = HudSurfaceContainer,
                    focusedBorderColor = SignalAmber,
                    unfocusedBorderColor = HudBorderSubtle
                )
            )

            // Detected SIM Banner
            Surface(
                color = CyberMint.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SimCard,
                        contentDescription = null,
                        tint = CyberMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (detectedSimNumber != null)
                            "SIM DETECTED: $detectedSimNumber"
                        else if (phoneNumberInput.isNotBlank())
                            "NUMBER READY: ${selectedCountry.dialCode} $phoneNumberInput"
                        else
                            "ENTER YOUR PHONE NUMBER ABOVE",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = CyberMint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Security Error Card if number is locked to another device
            AnimatedVisibility(visible = blockedErrorMessage != null) {
                blockedErrorMessage?.let { errMsg ->
                    Surface(
                        color = CrimsonAlert.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, CrimsonAlert),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = CrimsonAlert,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "NUMBER ALREADY LINKED",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = CrimsonAlert,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = errMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HudTextPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Docked CTA Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        blockedErrorMessage = null
                        isCheckingSecurity = true
                        delay(350)

                        val fullNumber = CountryCodes.formatPhoneNumber(selectedCountry.dialCode, phoneNumberInput)
                        val result = firebaseManager.verifyAndRegisterNumberAsync(fullNumber, currentDeviceId)

                        isCheckingSecurity = false

                        when (result) {
                            is RegisterResult.Success -> {
                                onLoginSuccess(result.user)
                            }
                            is RegisterResult.NewUser -> {
                                onNewUser(result.phoneNumber, currentDeviceId)
                            }
                            is RegisterResult.DeviceBlocked -> {
                                blockedErrorMessage = "This phone number (${result.phoneNumber}) is already registered on another device. For your security, another device cannot sign in with this number."
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("verify_bind_button"),
                enabled = phoneNumberInput.length >= 7 && !isCheckingSecurity,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SignalAmber,
                    contentColor = Color(0xFF0B1118)
                )
            ) {
                if (isCheckingSecurity) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color(0xFF0B1118),
                        strokeWidth = 2.2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "VERIFYING ACCOUNT...",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CONTINUE & VERIFY",
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileSetupScreen(
    phoneNumber: String,
    deviceId: String,
    onProfileComplete: (name: String, status: String) -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    val surfaceStyle = LocalSurfaceStyle.current

    var nameInput by remember { mutableStateOf("") }
    var statusMessageInput by remember { mutableStateOf("Available on RB DIALER") }

    val statusPresets = remember {
        listOf(
            "Available on RB DIALER",
            "Online • Ready for Calls",
            "In a Meeting • Text Only",
            "Busy • Urgent Calls Only"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        OnboardingStepHeader(
            currentStep = 3,
            totalSteps = 3,
            stepLabel = "COMPLETE YOUR PROFILE",
            accentColor = CyberMint,
            onBackClick = onBackClick
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Sleek Profile Preview Card (WhatsApp / Telegram style)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .surfaceFinishOverlay(
                        style = surfaceStyle,
                        cornerRadius = 22.dp,
                        accentColor = CyberMint
                    ),
                shape = RoundedCornerShape(22.dp),
                color = HudSurfaceContainer,
                border = BorderStroke(1.2.dp, CyberMint.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Surface(
                            modifier = Modifier.size(84.dp),
                            shape = CircleShape,
                            color = CyberMint.copy(alpha = 0.16f),
                            border = BorderStroke(2.dp, CyberMint)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = nameInput.trim().firstOrNull()?.uppercase() ?: "R",
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 34.sp,
                                    color = CyberMint
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier.size(26.dp),
                            shape = CircleShape,
                            color = CyberMint,
                            border = BorderStroke(2.dp, HudSurfaceContainer)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Verified",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = nameInput.ifBlank { "Your Display Name" },
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = HudTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = phoneNumber,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = CyberMint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = CyberMint.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = CyberMint,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "E2EE VERIFIED • RB DIALER",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = CyberMint,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Display Name Field
            OutlinedTextField(
                value = nameInput,
                onValueChange = { if (it.length <= 40) nameInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("operator_name_input"),
                label = { Text("Your Name") },
                placeholder = { Text("Enter your display name") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = SignalAmber
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = HudSurfaceContainer,
                    unfocusedContainerColor = HudSurfaceContainer,
                    focusedBorderColor = SignalAmber,
                    unfocusedBorderColor = HudBorderSubtle
                )
            )

            // Status Message Field
            OutlinedTextField(
                value = statusMessageInput,
                onValueChange = { if (it.length <= 80) statusMessageInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("operator_status_input"),
                label = { Text("About / Status Message") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = CyberMint
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = HudSurfaceContainer,
                    unfocusedContainerColor = HudSurfaceContainer,
                    focusedBorderColor = CyberMint,
                    unfocusedBorderColor = HudBorderSubtle
                )
            )

            // 1-Tap Quick Status Preset Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statusPresets.forEach { preset ->
                    val isSelected = statusMessageInput == preset
                    Surface(
                        onClick = { statusMessageInput = preset },
                        shape = RoundedCornerShape(50),
                        color = if (isSelected) CyberMint.copy(alpha = 0.20f) else HudSurfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) CyberMint else HudBorderSubtle
                        )
                    ) {
                        Text(
                            text = preset,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = if (isSelected) HudTextPrimary else HudTextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Bottom Docked CTA Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Button(
                onClick = { onProfileComplete(nameInput.trim(), statusMessageInput.trim()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("save_profile_button"),
                enabled = nameInput.trim().isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberMint,
                    contentColor = Color(0xFF0B1118)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.RocketLaunch,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "COMPLETE & LAUNCH RB DIALER",
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
