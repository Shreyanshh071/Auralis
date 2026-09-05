package com.auralis.music.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.auralis.music.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.viewmodel.AuthUiState

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val ONBOARDING_PEACH = Color(0xFFFFB67A)

/**
 * High-performance, hardware-accelerated dynamic atmospheric mesh background:
 * Fluidly moves signature Auralis brand colors (Warm Amber/Peach, Neon Royal Violet, Coral Rose, Electric Cyan)
 * across the canvas with dual non-repeating orbital phases and a deep pitch-black anchor for perfect text readability.
 */
@Composable
private fun DynamicAtmosphericMeshBackground(
    modifier: Modifier = Modifier,
    content: @Composable (pulse: Float) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "AtmosphericMeshTransition")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(23000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Palette tokens in sync with Auralis signature brand & theme
    val amberPeach = Color(0xFFFF9E54)
    val warmAmber = ONBOARDING_PEACH
    val royalViolet = Color(0xFF7C3AED)
    val electricIndigo = Color(0xFF8B5CF6)
    val roseCoral = Color(0xFFF43F5E)
    val cyanAccent = Color(0xFF06B6D4)
    val deepCanvas = Color(0xFF070806)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val width = size.width
                val height = size.height

                // 1. Deep solid foundation canvas
                drawRect(color = deepCanvas)

                // 2. Orb 1: Warm Amber / Sunset Peach Bloom (orbiting around top-left & headline)
                val angle1 = (phase1 * 2 * PI).toFloat()
                val center1 = Offset(
                    x = width * (0.36f + 0.22f * cos(angle1)),
                    y = height * (0.22f + 0.12f * sin(angle1))
                )
                val radius1 = width * 1.10f * pulse
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            amberPeach.copy(alpha = 0.42f),
                            warmAmber.copy(alpha = 0.22f),
                            warmAmber.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = center1,
                        radius = radius1
                    ),
                    center = center1,
                    radius = radius1
                )

                // 3. Orb 2: Neon Royal Violet Bloom (drifting through right-center)
                val angle2 = (phase2 * 2 * PI).toFloat()
                val center2 = Offset(
                    x = width * (0.76f + 0.18f * sin(angle2)),
                    y = height * (0.45f + 0.16f * cos(angle2))
                )
                val radius2 = width * 1.18f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            royalViolet.copy(alpha = 0.36f),
                            electricIndigo.copy(alpha = 0.18f),
                            royalViolet.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        center = center2,
                        radius = radius2
                    ),
                    center = center2,
                    radius = radius2
                )

                // 4. Orb 3: Coral / Rose Bloom (drifting in the lower-mid region)
                val center3 = Offset(
                    x = width * (0.22f + 0.24f * sin(angle1 + 1.6f)),
                    y = height * (0.64f + 0.14f * cos(angle2 + 0.7f))
                )
                val radius3 = width * 1.05f * (2.05f - pulse)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            roseCoral.copy(alpha = 0.28f),
                            roseCoral.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = center3,
                        radius = radius3
                    ),
                    center = center3,
                    radius = radius3
                )

                // 5. Orb 4: Subtle Electric Cyan Highlight (orbiting near top-right edge)
                val center4 = Offset(
                    x = width * (0.84f + 0.14f * cos(angle2 + 2.4f)),
                    y = height * (0.12f + 0.10f * sin(angle1 + 1.2f))
                )
                val radius4 = width * 0.82f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            cyanAccent.copy(alpha = 0.24f),
                            cyanAccent.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        center = center4,
                        radius = radius4
                    ),
                    center = center4,
                    radius = radius4
                )

                // 6. Global Multi-Stop Contrast & Vignette Ramp
                drawRect(
                    brush = Brush.verticalGradient(
                        0.00f to Color(0xFF070806).copy(alpha = 0.30f),
                        0.22f to Color.Transparent,
                        0.58f to Color(0xFF070806).copy(alpha = 0.40f),
                        0.78f to Color(0xFF070806).copy(alpha = 0.85f),
                        1.00f to Color(0xFF070806)
                    )
                )
            }
    ) {
        content(pulse)
    }
}

/**
 * Pixel-Perfect Welcome & Onboarding Screen with full-screen Email Auth screen.
 * Fully functional with real Firebase Google Sign-In & Email/Password Authentication.
 * Features living dynamic atmospheric color aura in sync with Auralis design system.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(
    authUiState: AuthUiState,
    onContinueWithGoogle: () -> Unit,
    onSignUpWithEmail: (String, String, String) -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEmailAuthOpen by remember { mutableStateOf(false) }

    DynamicAtmosphericMeshBackground(modifier = modifier) { pulse ->
        AnimatedContent(
            targetState = isEmailAuthOpen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "OnboardingTransition"
        ) { openEmailAuth ->
            if (openEmailAuth) {
                // ── FULL-SCREEN EMAIL AUTHENTICATION ──
                EmailAuthFullScreen(
                    authUiState = authUiState,
                    onBack = { isEmailAuthOpen = false },
                    onSignUp = onSignUpWithEmail,
                    onSignIn = onSignInWithEmail
                )
            } else {
                // ── MAIN ONBOARDING SCREEN ──
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // ── TOP BRANDING & HEADLINE SECTION ──
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp)
                        ) {
                            // App Logo Box with synchronized ambient breathing halo
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 28.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    // Ambient breathing glow halo behind logo
                                    Box(
                                        modifier = Modifier
                                            .size((72.dp * pulse).coerceIn(60.dp, 80.dp))
                                            .background(
                                                Brush.radialGradient(
                                                    listOf(
                                                        ONBOARDING_PEACH.copy(alpha = 0.42f),
                                                        Color(0xFF7C3AED).copy(alpha = 0.16f),
                                                        Color.Transparent
                                                    )
                                                ),
                                                shape = CircleShape
                                            )
                                    )
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_auralis_logo),
                                        contentDescription = "Auralis Logo",
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Text(
                                    text = "Auralis",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    fontSize = 32.sp
                                )
                            }

                            // Big Headline
                            Text(
                                text = "You. Music.\nLet it happen",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                fontSize = 40.sp,
                                lineHeight = 46.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Subtitle
                            Text(
                                text = "Stream, discover, and vibe — all in one place. Free, forever.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            )

                            Spacer(modifier = Modifier.height(28.dp))

                            // Feature Pills (FlowRow)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FeaturePill(icon = Icons.Default.MusicNote, text = "Millions of songs")
                                FeaturePill(icon = Icons.Default.GraphicEq, text = "Live lyrics")
                                FeaturePill(icon = Icons.AutoMirrored.Filled.QueueMusic, text = "Smart queue")
                                FeaturePill(icon = Icons.Default.ElectricBolt, text = "No ads")
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        // ── BOTTOM ACTION BUTTONS ──
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 1. Continue with Google
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CircleShape)
                                    .background(Color(0xCC181614))
                                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                                    .tactileBounce(scaleDown = 0.96f) {
                                        onContinueWithGoogle()
                                    }
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (authUiState.isSyncing) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = Color(0xFF4285F4),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "Connecting to Google...",
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp
                                        )
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        GoogleLogoIcon(modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "Continue with Google",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }

                            // 2. Sign up / in with Email (Warm Peach Gradient Button)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CircleShape)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                ONBOARDING_PEACH,
                                                Color(0xFFFFAA66)
                                            )
                                        )
                                    )
                                    .tactileBounce(scaleDown = 0.96f) {
                                        isEmailAuthOpen = true
                                    }
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sign up / in with Email",
                                    color = Color(0xFF140D05),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }

                            if (!authUiState.syncMessage.isNullOrBlank()) {
                                val isError = authUiState.syncMessage.contains("error", ignoreCase = true) ||
                                              authUiState.syncMessage.contains("failed", ignoreCase = true)
                                Text(
                                    text = authUiState.syncMessage,
                                    color = if (isError) Color(0xFFFF6B6B) else Color(0xFFFFB67A),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-Screen Email Authentication (Welcome back / Log in / Sign up).
 * Matches user's exact dark layout with Username, Email, and Password fields.
 */
@Composable
private fun EmailAuthFullScreen(
    authUiState: AuthUiState,
    onBack: () -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onSignIn: (String, String) -> Unit
) {
    var authMode by remember { mutableIntStateOf(0) } // 0 = Sign up, 1 = Log in
    var usernameInput by remember { mutableStateOf("") }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Back Navigation Button
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_auralis_logo),
                        contentDescription = "Auralis Logo",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Auralis",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // 2. Headline & Subtitle
                Text(
                    text = "Welcome back",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 32.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Sign in to your account or create a new one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(28.dp))

                // 3. Segmented Tab Selector (Log in | Sign up)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF181818))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (authMode == 1) Color(0xFF333333) else Color.Transparent)
                            .clickable { authMode = 1 }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Log in",
                            fontWeight = FontWeight.Bold,
                            color = if (authMode == 1) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (authMode == 0) Color(0xFF333333) else Color.Transparent)
                            .clickable { authMode = 0 }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Sign up",
                            fontWeight = FontWeight.Bold,
                            color = if (authMode == 0) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 4. Form Fields
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Username (only for Sign up)
                    if (authMode == 0) {
                        DarkInputField(
                            value = usernameInput,
                            onValueChange = { usernameInput = it },
                            placeholder = "Username",
                            icon = Icons.Default.Person
                        )
                    }

                    // Email Field
                    DarkInputField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        placeholder = "Email",
                        icon = Icons.Default.Email,
                        keyboardType = KeyboardType.Email
                    )

                    // Password Field
                    DarkInputField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        placeholder = "Password",
                        icon = Icons.Default.Lock,
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                        isPasswordVisible = isPasswordVisible,
                        onTogglePasswordVisibility = { isPasswordVisible = !isPasswordVisible }
                    )
                }

                if (authUiState.syncMessage != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = authUiState.syncMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = ONBOARDING_PEACH,
                        fontSize = 13.sp
                    )
                }
            }

            // 5. Primary CTA Button (Create account / Log in)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp, top = 20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(ONBOARDING_PEACH)
                        .tactileBounce(scaleDown = 0.96f) {
                            if (authMode == 0) {
                                if (emailInput.isNotBlank() && passwordInput.isNotBlank()) {
                                    onSignUp(emailInput.trim(), passwordInput, usernameInput.trim())
                                }
                            } else {
                                if (emailInput.isNotBlank() && passwordInput.isNotBlank()) {
                                    onSignIn(emailInput.trim(), passwordInput)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (authUiState.isSyncing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (authMode == 0) "Creating account..." else "Logging in...",
                                color = Color(0xFF140D05),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    } else {
                        Text(
                            text = if (authMode == 0) "Create account" else "Log in",
                            color = Color(0xFF140D05),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sleek Rounded Capsule Form Field with Icon & Eye Toggle.
 */
@Composable
private fun DarkInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    isPasswordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xBF181614))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 15.sp
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(ONBOARDING_PEACH),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (isPassword && !isPasswordVisible) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isPassword && onTogglePasswordVisibility != null) {
                IconButton(onClick = onTogglePasswordVisibility) {
                    Icon(
                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle Password",
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturePill(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x991E1813))
            .border(1.dp, ONBOARDING_PEACH.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ONBOARDING_PEACH,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = Color(0xFFF3E7DC),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun GoogleLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height

        drawRect(
            color = Color(0xFF4285F4),
            topLeft = Offset(w * 0.45f, h * 0.40f),
            size = Size(w * 0.55f, h * 0.20f)
        )
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 180f,
            sweepAngle = 140f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.20f)
        )
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 120f,
            sweepAngle = 120f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.20f)
        )
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 0f,
            sweepAngle = 120f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.20f)
        )
    }
}
