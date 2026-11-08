package com.js8firefly.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.js8firefly.android.AppModel
import com.js8firefly.android.GridMath
import com.js8firefly.android.R
import kotlinx.coroutines.delay

private val FireflyColors = darkColorScheme(
    primary = Theme.accent,
    onPrimary = Theme.bg,
    secondary = Theme.accent2,
    background = Theme.bg,
    onBackground = Theme.text,
    surface = Theme.bg2,
    onSurface = Theme.text,
    surfaceVariant = Theme.cardHi,
    onSurfaceVariant = Theme.dim,
    surfaceContainer = Theme.cardHi,
    error = Theme.tx,
)

/** Entry point: the whole Firefly UI. */
@Composable
fun FireflyRoot(model: AppModel) {
    MaterialTheme(colorScheme = FireflyColors) {
        // MainActivity calls model.start() after the permission prompt.
        BoxWithConstraints(Modifier.fillMaxSize().background(Theme.backgroundGradient)) {
            // iPad-style regular width gets the console; phones get tabs.
            if (maxWidth >= 600.dp) {
                DesktopLayout(model, maxWidth, maxHeight)
            } else {
                PhoneLayout(model)
            }
            BannerView(model, Modifier.align(Alignment.TopCenter))
            if (!model.settings.hasOnboarded) OnboardingView(model)
        }
    }
}

@Composable
private fun imeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

private data class TabItem(val title: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("Live", Icons.Filled.GraphicEq),
    TabItem("Chat", Icons.Filled.Forum),
    TabItem("Stations", Icons.Filled.Groups),
    TabItem("Inbox", Icons.Filled.Inbox),
    TabItem("Settings", Icons.Filled.Settings),
)

/** Phone: tabbed layout. */
@Composable
fun PhoneLayout(model: AppModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = tab != 0) { tab = 0 }
    val keyboard = imeVisible()
    Column(Modifier.fillMaxSize().statusBarsPadding().then(if (keyboard) Modifier.imePadding() else Modifier.navigationBarsPadding())) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> LiveTab(model)
                1 -> ChatTab(model)
                2 -> TitledPage("Stations") {
                    StationsView(model, Modifier.fillMaxSize().padding(horizontal = 16.dp)) { tab = 1 }
                }
                3 -> TitledPage("Inbox") {
                    InboxView(model, Modifier.fillMaxSize().padding(horizontal = 16.dp))
                }
                else -> SettingsView(model, Modifier.fillMaxSize())
            }
        }
        if (!keyboard) {
            TabBar(tab, model.inbox.count { it.type == "UNREAD" }) { tab = it }
        }
    }
}

@Composable
private fun TitledPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, style = Theme.rounded(34, Bold), color = Theme.text, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A floating capsule tab bar like the iOS one in the screenshots. */
@Composable
private fun TabBar(selected: Int, unread: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .shadow(12.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1F2330))
            .border(1.dp, Theme.stroke, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Theme.bg2 else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(t.icon, t.title, tint = if (on) Theme.accent else Theme.text, modifier = Modifier.size(24.dp))
                    if (i == 3 && unread > 0) Badge(unread, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp))
                }
                Text(t.title, style = Theme.rounded(11, SemiBold), color = if (on) Theme.accent else Theme.text, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Badge(n: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(17.dp).background(Theme.tx, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text("$n", style = Theme.rounded(10, Bold), color = Color.White) }
}

@Composable
fun LiveTab(model: AppModel) {
    Column(Modifier.fillMaxSize()) {
        HeaderView(model, Modifier.dismissesKeyboardOnTap())
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).padding(top = 8.dp).dismissesKeyboardOnTap(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WaterfallView(model, Modifier.fillMaxWidth().height(190.dp))
            BandActivityView(model, Modifier.weight(1f))
        }
        ComposeBar(model, Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp))
    }
}

@Composable
fun ChatTab(model: AppModel) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).dismissesKeyboardOnTap()) {
            HeaderView(model)
            WaterfallView(model, Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp).fillMaxWidth().height(70.dp), showSpectrum = false)
            ChatPane(model, Modifier.padding(top = 8.dp).weight(1f))
        }
        ComposeBar(model, Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp))
    }
}

/**
 * Tablet / large screen: JS8Call-style console with waterfall, band activity,
 * conversation and heard stations side by side.
 */
@Composable
fun DesktopLayout(model: AppModel, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    var showSettings by remember { mutableStateOf(false) }
    var showInbox by remember { mutableStateOf(false) }
    val compactHeight = height < 480.dp
    val isShort = !compactHeight && height < 700.dp
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            HeaderView(model, Modifier.weight(1f))
            Row(
                Modifier.fillMaxHeight().background(Theme.bg2.copy(alpha = 0.92f)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val n = model.inbox.count { it.type == "UNREAD" }
                Box(Modifier.clickable { showInbox = true }) {
                    Icon(Icons.Filled.Inbox, "Inbox", tint = Theme.accent, modifier = Modifier.size(24.dp))
                    if (n > 0) Badge(n, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp))
                }
                Icon(Icons.Filled.Settings, "Settings", tint = Theme.accent, modifier = Modifier.size(24.dp).clickable { showSettings = true })
            }
        }
        WaterfallView(model, Modifier.fillMaxWidth().padding(10.dp).height(if (isShort) 150.dp else 210.dp).dismissesKeyboardOnTap())
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            val rowWidth = maxWidth
            if (rowWidth < 930.dp && !compactHeight) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width((rowWidth - 10.dp) * 0.45f).fillMaxHeight().dismissesKeyboardOnTap(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Card(Modifier.weight(1f).fillMaxWidth()) { BandActivityView(model) }
                        Card(Modifier.weight(1f).fillMaxWidth()) { StationsView(model) }
                    }
                    Conversation(model, Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(Modifier.weight(0.9f).widthIn(min = 280.dp).fillMaxHeight().dismissesKeyboardOnTap()) { BandActivityView(model) }
                    Conversation(model, Modifier.weight(1.2f).widthIn(min = 380.dp))
                    Card(Modifier.weight(0.75f).widthIn(min = 250.dp).fillMaxHeight().dismissesKeyboardOnTap()) { StationsView(model) }
                }
            }
        }
    }
    if (showSettings) {
        FireflySheet("Settings", { showSettings = false }, fullHeight = true) {
            SettingsView(model, Modifier.fillMaxWidth(), title = false)
        }
    }
    if (showInbox) {
        FireflySheet("Inbox", { showInbox = false }, fullHeight = true) {
            InboxView(model, Modifier.fillMaxWidth().padding(16.dp).height(560.dp))
        }
    }
}

@Composable
private fun Conversation(model: AppModel, modifier: Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.weight(1f).fillMaxWidth().dismissesKeyboardOnTap(), padding = 4.dp) {
            ChatPane(model, Modifier.padding(top = 6.dp))
        }
        ComposeBar(model)
    }
}

@Composable
fun BannerView(model: AppModel, modifier: Modifier = Modifier) {
    val banner = model.banner
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(3000)
            model.banner = null
        }
    }
    Column(
        modifier.statusBarsPadding().padding(top = 50.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val err = model.lastError
        AnimatedVisibility(err != null, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
            BannerLabel(err ?: "", Icons.Filled.Warning, Theme.tx) { model.dismissError() }
        }
        AnimatedVisibility(banner != null, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
            BannerLabel(banner ?: "", Icons.Filled.Info, Theme.accent, null)
        }
    }
}

@Composable
private fun BannerLabel(text: String, icon: ImageVector, color: Color, onTap: (() -> Unit)?) {
    Row(
        Modifier
            .shadow(8.dp, RoundedCornerShape(50))
            .background(color.copy(alpha = 0.92f), RoundedCornerShape(50))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Theme.bg, modifier = Modifier.size(16.dp))
        Text(text, style = Theme.rounded(13, SemiBold), color = Theme.bg)
    }
}

@Composable
fun OnboardingView(model: AppModel) {
    var call by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf("") }
    fun formatGrid(g: String): String {
        val t = g.trim()
        if (t.length < 4) return t.uppercase()
        return t.take(4).uppercase() + t.drop(4).lowercase()
    }
    fun finish() {
        model.updateSettings { it.copy(hasOnboarded = true) }
        model.requestNotificationPermission()
    }
    BackHandler { /* Onboarding can't be dismissed, as on iOS. */ }
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme.backgroundGradient)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        ) {
            Spacer(Modifier.height(24.dp))
            Image(
                painterResource(R.drawable.logo), "JS8 Firefly",
                Modifier
                    .size(128.dp)
                    .shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = Theme.rgb(0.84, 1.0, 0.36), spotColor = Theme.rgb(0.84, 1.0, 0.36))
                    .clip(RoundedCornerShape(28.dp)),
            )
            Text("JS8 Firefly", style = Theme.rounded(38, Heavy), color = Theme.text)
            Text(
                "Keyboard-to-keyboard weak-signal messaging.\nFully compatible with JS8Call stations.",
                style = Theme.rounded(15), color = Theme.dim, textAlign = TextAlign.Center,
            )
            Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlainField(
                    call, { call = it.uppercase() }, "Your callsign",
                    Modifier.fillMaxWidth().background(Theme.cardHi, RoundedCornerShape(12.dp)).padding(12.dp),
                    style = Theme.mono(20, Bold), align = TextAlign.Center, uppercase = true,
                )
                PlainField(
                    grid, { grid = it }, "Grid locator (e.g. EM73)",
                    Modifier.fillMaxWidth().background(Theme.cardHi, RoundedCornerShape(12.dp)).padding(12.dp),
                    style = Theme.mono(18), align = TextAlign.Center, uppercase = true,
                )
            }
            val ok = call.length >= 3 && GridMath.isValid(grid)
            Box(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Theme.accent.copy(alpha = if (ok) 1f else 0.5f))
                    .clickable(enabled = ok) {
                        val c = call.uppercase().trim()
                        val g = formatGrid(grid)
                        model.updateSettings { it.copy(station = it.station.copy(callsign = c, grid = g)) }
                        finish()
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Get on the air", style = Theme.rounded(17, Bold), color = Theme.bg) }
            Text(
                "Just listen for now", style = Theme.rounded(14), color = Theme.dim,
                modifier = Modifier.clickable { finish() }.padding(8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
