package app.lurk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lurk.data.Account
import app.lurk.data.CommunityActivity
import app.lurk.data.DayStats
import app.lurk.data.FeedBudgetChoices
import app.lurk.data.Health
import app.lurk.data.HealthCheck
import app.lurk.data.LurkPrefs
import app.lurk.data.MergedCommunity
import app.lurk.data.MuteFilter
import app.lurk.data.ReadingSession
import app.lurk.data.ReadingStats
import app.lurk.data.ServiceKind
import app.lurk.data.SettingsPage
import app.lurk.data.SettingsScreen
import java.time.format.TextStyle as DayStyle
import java.util.Locale

/** What Settings can ask the app to do. */
class SettingsActions(
    val open: (SettingsPage) -> Unit,
    val setNsfw: (Boolean) -> Unit,
    val setHideSeen: (Boolean) -> Unit,
    val setPin: () -> Unit,
    val removePin: () -> Unit,
    val setMutes: (List<String>) -> Unit,
    val pickDefaultLayout: () -> Unit,
    val setStandardReddit: (Boolean) -> Unit,
    val checkUpdates: (() -> Unit)?,
    val openDebug: () -> Unit,
    val openCommunity: (String) -> Unit,
    /** Joins [String] and leaves the other: "switch to the busier copy". */
    val switchCommunity: (from: String, to: String) -> Unit,
    val merge: (label: String, members: List<String>) -> Unit,
    val unmerge: (String) -> Unit,
)

/** Everything a Settings page shows. */
class SettingsState(
    val account: Account,
    val prefs: LurkPrefs,
    val stats: ReadingStats,
    val activity: CommunityActivity,
    val health: HealthCheck,
    val multiProfile: Boolean,
    val standardReddit: Boolean,
    val build: String?,
)

@Composable
fun SettingsView(screen: SettingsScreen, state: SettingsState, actions: SettingsActions) {
    when (screen.page) {
        SettingsPage.Main -> SettingsMain(screen, state, actions)
        SettingsPage.Mutes -> MutesPage(screen, state.account.mutes, state.stats, actions.setMutes)
        SettingsPage.Stats -> StatsPage(screen, state.stats)
        SettingsPage.Health -> HealthPage(screen, state, actions)
        SettingsPage.Merged -> MergedPage(screen, state.account, actions)
    }
}

// --- Main ---

@Composable
private fun SettingsMain(screen: SettingsScreen, state: SettingsState, actions: SettingsActions) {
    val account = state.account
    val prefs = state.prefs
    val week = state.stats.lastDays(7).also { state.stats.version } // recompose on change
    val readThisWeek = week.sumOf { it.read }
    val isLemmy = account.kind == ServiceKind.Lemmy
    LazyColumn(state = screen.listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item(key = "reading") {
            SectionLabel("Reading")
            RowGroup {
                GroupRow(
                    title = "Default layout",
                    subtitle = "${prefs.defaultLayout.label}. Feeds can each have their own, from ⋮ › Layout.",
                    leading = { IconTile(prefs.defaultLayout.icon()) },
                    trailing = { Chevron() },
                    onClick = actions.pickDefaultLayout,
                )
                GroupDivider()
                SettingSwitch(
                    LurkIcons.Book, "Open articles in reader view",
                    "Just the text and pictures, read on your phone. Any link tile also has a Reader button.",
                    prefs.readerByDefault, prefs::changeReaderByDefault,
                )
                GroupDivider()
                SettingSwitch(
                    LurkIcons.Sparkle, "Clean tracking from links",
                    "Takes utm_, fbclid and similar off links you open and share",
                    prefs.cleanLinks, prefs::changeCleanLinks,
                )
            }
        }
        item(key = "budget") {
            SectionLabel("Wellbeing")
            RowGroup {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(LurkIcons.Hourglass)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text("Feed budget", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (prefs.feedBudget == 0) "Feeds go on as long as you scroll"
                                else "Feeds pause after ${prefs.feedBudget} posts. ${ReadingSession.read} read this sitting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        FeedBudgetChoices.forEachIndexed { i, n ->
                            SegmentedButton(
                                selected = prefs.feedBudget == n,
                                onClick = { prefs.changeFeedBudget(n) },
                                shape = SegmentedButtonDefaults.itemShape(i, FeedBudgetChoices.size),
                                icon = {},
                            ) { Text(if (n == 0) "Off" else "$n") }
                        }
                    }
                }
            }
        }
        item(key = "account") {
            SectionLabel(account.label?.let { "This account · $it" } ?: "This account")
            RowGroup {
                GroupRow(
                    title = "Muted words",
                    subtitle = when (account.mutes.size) {
                        0 -> "Keep posts about some things out of your feeds"
                        1 -> "1 word muted"
                        else -> "${account.mutes.size} words muted"
                    },
                    leading = { IconTile(LurkIcons.MuteWords) },
                    trailing = { Chevron() },
                    onClick = { actions.open(SettingsPage.Mutes) },
                )
                GroupDivider()
                SettingSwitch(LurkIcons.EyeOff, "Hide posts I've seen", "Feeds show only posts you haven't scrolled past", account.hideSeen, actions.setHideSeen)
                GroupDivider()
                SettingSwitch(LurkIcons.Eye, "Show NSFW without tapping", "Also deals NSFW communities in Discover. Spoilers stay hidden", account.showNsfw, actions.setNsfw)
                if (account.label != null) {
                    GroupDivider()
                    SettingSwitch(LurkIcons.Lock, "Require PIN", "Ask for it when switching to this account", account.locked) { on ->
                        if (on) actions.setPin() else actions.removePin()
                    }
                }
            }
        }
        item(key = "communities") {
            SectionLabel("Communities")
            RowGroup {
                GroupRow(
                    title = "Community health",
                    subtitle = if (isLemmy) "Quiet communities, and busier copies to switch to" else "Which of your communities have gone quiet",
                    leading = { IconTile(LurkIcons.Pulse) },
                    trailing = { Chevron() },
                    onClick = { actions.open(SettingsPage.Health) },
                )
                if (isLemmy || account.merged.isNotEmpty()) {
                    GroupDivider()
                    GroupRow(
                        title = "Merged communities",
                        subtitle = when (account.merged.size) {
                            0 -> "Read a community's copies on several instances as one feed"
                            1 -> "1 merged community"
                            else -> "${account.merged.size} merged communities"
                        },
                        leading = { IconTile(LurkIcons.Merge) },
                        trailing = { Chevron() },
                        onClick = { actions.open(SettingsPage.Merged) },
                    )
                }
            }
        }
        item(key = "activity") {
            SectionLabel("Your activity")
            RowGroup {
                GroupRow(
                    title = "Your reading",
                    subtitle = if (readThisWeek == 0) "Posts read, time in Lurk and more. Kept on this phone." else "$readThisWeek posts read this week",
                    leading = { IconTile(LurkIcons.Chart) },
                    trailing = { Sparkline(week) },
                    onClick = { actions.open(SettingsPage.Stats) },
                )
            }
        }
        item(key = "lurk") {
            SectionLabel("Lurk")
            RowGroup {
                if (state.multiProfile) {
                    SettingSwitch(
                        LurkIcons.Globe, "Standard Reddit accounts",
                        if (state.standardReddit) "Signed in on Reddit's site. Reddit blocks Lurk's browser right now."
                        else "Hidden while off. Reddit blocks Lurk's browser right now.",
                        state.standardReddit, actions.setStandardReddit,
                    )
                    GroupDivider()
                }
                actions.checkUpdates?.let { check ->
                    GroupRow(
                        title = "Check for updates",
                        subtitle = state.build?.let { "You have build $it" },
                        leading = { IconTile(LurkIcons.Download) },
                        trailing = { Chevron() },
                        onClick = check,
                    )
                    GroupDivider()
                }
                GroupRow(
                    title = "Engine debug",
                    subtitle = "Logs and tests, for when something's not working",
                    leading = { IconTile(LurkIcons.Terminal) },
                    trailing = { Chevron() },
                    onClick = actions.openDebug,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    GroupRow(
        title = title,
        subtitle = subtitle,
        leading = { IconTile(icon) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun Chevron() {
    Icon(LurkIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
}

/** The chart color: the accent, a step deeper in dark mode so it sits in the readable band. */
@Composable
private fun chartColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFEE5F3C) else Color(0xFFE5401C)

/** Seven tiny columns: the week's reading at a glance. */
@Composable
private fun Sparkline(week: List<DayStats>) {
    val max = week.maxOfOrNull { it.read }?.coerceAtLeast(1) ?: 1
    val color = chartColor()
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(24.dp)) {
        week.forEach { d ->
            val h = (24f * d.read / max).coerceAtLeast(2f)
            Box(
                Modifier
                    .width(5.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(if (d.read == 0) MaterialTheme.colorScheme.outlineVariant else color),
            )
        }
    }
}

// --- Muted words ---

@Composable
private fun MutesPage(screen: SettingsScreen, rules: List<String>, stats: ReadingStats, onChange: (List<String>) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val valid = typed.isBlank() || MuteFilter.isValid(typed)
    val hiddenWeek = stats.lastDays(7).also { stats.version }.sumOf { it.hiddenMuted }
    fun add() {
        val rule = typed.trim()
        if (rule.isEmpty() || !MuteFilter.isValid(rule)) return
        if (rules.none { it.equals(rule, ignoreCase = true) }) onChange(listOf(rule) + rules)
        typed = ""
    }
    LazyColumn(state = screen.listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item(key = "add") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Posts whose title, text or flair mention a muted word stay out of this account's feeds. You can always peek at what was hidden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    TextField(
                        value = typed,
                        onValueChange = { typed = it },
                        placeholder = { Text("Add a word or phrase") },
                        singleLine = true,
                        isError = !valid,
                        shape = CircleShape,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            errorContainerColor = MaterialTheme.colorScheme.errorContainer,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f).clip(CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { add() }, enabled = typed.isNotBlank() && valid, modifier = Modifier.height(52.dp)) { Text("Mute") }
                }
                Text(
                    if (!valid) "That expression has a mistake in it." else "Whole words, any case. For patterns, write /an expression/.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!valid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }
        }
        if (rules.isEmpty()) {
            item(key = "empty") {
                EmptyState(LurkIcons.MuteWords, "No muted words", "Try a show you haven't caught up on, or a topic you need a break from.")
            }
        } else {
            item(key = "list") {
                SectionLabel(if (hiddenWeek > 0) "Muted · $hiddenWeek posts hidden this week" else "Muted")
                RowGroup {
                    rules.forEachIndexed { i, rule ->
                        if (i > 0) GroupDivider(inset = 16.dp)
                        val regex = MuteFilter.isRegex(rule)
                        GroupRow(
                            title = rule,
                            subtitle = if (regex) "Expression" else null,
                            trailing = {
                                IconButton(onClick = { onChange(rules - rule) }) {
                                    Icon(LurkIcons.Close, "Unmute $rule", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// --- Stats ---

@Composable
private fun StatsPage(screen: SettingsScreen, stats: ReadingStats) {
    stats.version // recompose on change
    var span by rememberSaveable { mutableStateOf(7) }
    val days = stats.lastDays(span)
    val read = days.sumOf { it.read }
    val threads = days.sumOf { it.threads }
    val active = days.sumOf { it.activeMs }
    val hidden = days.sumOf { it.hiddenSeen + it.hiddenMuted }
    val saved = hidden * ReadingStats.SECONDS_PER_HIDDEN_POST * 1000L
    val top = stats.topCommunities(days)
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(state = screen.listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item(key = "span") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf(7 to "Week", 30 to "30 days", 90 to "90 days").forEachIndexed { i, (n, label) ->
                    SegmentedButton(
                        selected = span == n,
                        onClick = { span = n },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                        icon = {},
                    ) { Text(label) }
                }
            }
        }
        item(key = "tiles") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Posts read", compact(read), perDay(read, span), Modifier.weight(1f))
                    StatTile("Time in Lurk", duration(active), perDayTime(active, span), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Threads opened", compact(threads), null, Modifier.weight(1f))
                    StatTile("Time saved", "≈ " + duration(saved), "$hidden posts hidden", Modifier.weight(1f))
                }
            }
        }
        item(key = "chart") {
            SectionLabel("Posts read each day")
            Surface(shape = PanelShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                DayChart(days, Modifier.padding(16.dp))
            }
        }
        item(key = "top") {
            SectionLabel("Where you read most")
            if (top.isEmpty()) {
                Text(
                    "Nothing yet. Posts count once they've been on screen for a moment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            } else {
                RowGroup {
                    val most = top.first().second.coerceAtLeast(1)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        top.forEach { (name, n) -> TopRow(name, n, n.toFloat() / most) }
                    }
                }
            }
        }
        item(key = "privacy") {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "These numbers never leave your phone. Time saved is a guess: about ${ReadingStats.SECONDS_PER_HIDDEN_POST} seconds for each post Lurk hid.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Clear reading history", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear reading history?") },
            text = { Text("Every count on this page starts again from zero. Posts you've seen stay hidden if you hide them.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; stats.clear() }) { Text("Clear", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, note: String?, modifier: Modifier) {
    Surface(shape = PanelShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            Text(
                note ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Posts read per day as thin columns from one baseline, with a hairline at the top value.
 * Tapping a column shows its day and count; the busiest day is labeled.
 */
@Composable
private fun DayChart(days: List<DayStats>, modifier: Modifier) {
    val max = days.maxOfOrNull { it.read } ?: 0
    var picked by remember(days.size) { mutableStateOf<Int?>(null) }
    val color = chartColor()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val chartHeight = 140.dp
    Column(modifier) {
        val shown = picked?.let { days.getOrNull(it) } ?: days.lastOrNull { it.read == max && max > 0 }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                shown?.let { "${it.read}" } ?: "0",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                shown?.let { (if (picked == null) "  most, " else "  on ") + dayName(it) } ?: "  posts",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(chartHeight)) {
            // The top value's line, recessive.
            Box(Modifier.fillMaxWidth().height(1.dp).background(grid))
            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomStart).background(grid))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(if (days.size > 40) 1.dp else 2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                days.forEachIndexed { i, d ->
                    val share = if (max == 0) 0f else d.read.toFloat() / max
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(onClickLabel = "${d.read} posts, ${dayName(d)}") { picked = if (picked == i) null else i }
                            .semantics { contentDescription = "${dayName(d)}: ${d.read} posts" },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val barColor = if (picked == null || picked == i) color else color.copy(alpha = 0.35f)
                        Box(
                            Modifier
                                .fillMaxWidth(if (days.size > 40) 1f else 0.62f)
                                .widthIn(max = 24.dp)
                                .height((chartHeight.value * share).coerceAtLeast(if (d.read > 0) 3f else 1.5f).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (d.read == 0) grid else barColor),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(dayName(days.first()), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
            Text("Today", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun TopRow(name: String, count: Int, share: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name.substringAfter('/'), 22.dp)
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier
                .padding(start = 32.dp, top = 6.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(chartColor()))
        }
    }
}

private fun dayName(d: DayStats): String {
    val today = java.time.LocalDate.now()
    return when (d.date) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> d.date.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault()) + " " + d.date.dayOfMonth +
            " " + d.date.month.getDisplayName(DayStyle.SHORT, Locale.getDefault())
    }
}

private fun perDay(n: Int, days: Int): String = "${"%.0f".format(n.toFloat() / days)} a day"

private fun perDayTime(ms: Long, days: Int): String = duration(ms / days) + " a day"

/** "45 s", "12 min", "3 h 20 min". */
private fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms < 60_000 -> "${ms / 1000} s"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h" + (minutes % 60).let { if (it > 0) " $it min" else "" }
    }
}

// --- Community health ---

@Composable
private fun HealthPage(screen: SettingsScreen, state: SettingsState, actions: SettingsActions) {
    val account = state.account
    val health = state.health
    val communities = account.communities
    LaunchedEffect(communities) { health.loadIndex(communities) }
    state.activity.version // recompose as feeds and checks learn more
    val now = System.currentTimeMillis() / 1000
    data class Row3(val name: String, val health: Health, val newest: Long?, val busier: List<app.lurk.data.CommunityInfo>, val note: String?)
    val rows = communities.map { name ->
        val index = health.index[name]
        val known = state.activity.of(name)
        val fromIndex = index?.info
        val h = when {
            fromIndex != null && fromIndex.activeMonth == 0 -> Health.Quiet
            fromIndex != null && fromIndex.activeMonth > 0 -> if (CommunityActivity.health(known, now) == Health.Quiet) Health.Quiet else Health.Active
            else -> CommunityActivity.health(known, now)
        }
        val note = when {
            fromIndex != null -> "${compact(fromIndex.activeMonth)} active this month"
            known != null && known.newestPost > 0 -> "Last post ${ago(known.newestPost, now)} ago"
            known?.ownFeed == true -> "No posts"
            else -> null
        }
        Row3(name, h, known?.newestPost, index?.busier.orEmpty(), note)
    }
    val quiet = rows.filter { it.health == Health.Quiet }.sortedBy { it.newest ?: 0L }
    val unknown = rows.filter { it.health == Health.Unknown }
    val active = rows.filter { it.health == Health.Active }
    val betterCopies = rows.filter { it.health != Health.Quiet && it.busier.isNotEmpty() }

    LazyColumn(state = screen.listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item(key = "summary") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HealthCount("Active", active.size, Health.Active, Modifier.weight(1f))
                HealthCount("Quiet", quiet.size, Health.Quiet, Modifier.weight(1f))
                HealthCount("Unknown", unknown.size, Health.Unknown, Modifier.weight(1f))
            }
        }
        if (communities.isEmpty()) {
            item(key = "none") { EmptyState(LurkIcons.Pulse, "No communities yet", "Join some, and their health shows here.") }
        }
        if (unknown.isNotEmpty()) {
            item(key = "check") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (health.checking) {
                        val (done, total) = health.progress
                        Text("Checking $done of $total…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape),
                        )
                    } else {
                        OutlinedButton(onClick = { health.check(communities) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (unknown.size == 1) "Check 1 community" else "Check ${unknown.size} communities")
                        }
                        Text(
                            if (account.kind == ServiceKind.RedditRss) "Reads each feed once, slowly, to stay inside Reddit's limits."
                            else "Reads each one's newest posts once. Feeds you open are checked as you go.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                        )
                    }
                }
            }
        }
        if (quiet.isNotEmpty()) {
            item(key = "quiet") {
                SectionLabel("Gone quiet · nothing in the last month")
                RowGroup {
                    quiet.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider()
                        HealthRow(r.name, r.health, r.note, r.busier, actions)
                    }
                }
            }
        }
        if (betterCopies.isNotEmpty()) {
            item(key = "busier") {
                SectionLabel("Busier copies elsewhere")
                RowGroup {
                    betterCopies.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider()
                        HealthRow(r.name, r.health, r.note, r.busier, actions)
                    }
                }
            }
        }
        if (active.isNotEmpty()) {
            item(key = "active") {
                SectionLabel("Active")
                RowGroup {
                    active.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider()
                        HealthRow(r.name, r.health, r.note, emptyList(), actions)
                    }
                }
            }
        }
        if (unknown.isNotEmpty()) {
            item(key = "unknown") {
                SectionLabel("Not checked yet")
                RowGroup {
                    unknown.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider()
                        HealthRow(r.name, r.health, r.note, r.busier, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun healthColor(h: Health): Color = when (h) {
    Health.Active -> Color(0xFF1F9D6B)
    Health.Quiet -> Color(0xFFD98E04)
    Health.Unknown -> MaterialTheme.colorScheme.outline
}

@Composable
private fun HealthCount(label: String, n: Int, h: Health, modifier: Modifier) {
    Surface(shape = PanelShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthDot(h)
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
            Text("$n", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Status is never color alone: each dot sits beside its word. */
@Composable
private fun HealthDot(h: Health) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(healthColor(h)))
}

@Composable
private fun HealthRow(name: String, h: Health, note: String?, busier: List<app.lurk.data.CommunityInfo>, actions: SettingsActions) {
    val naming = LocalNaming.current
    var open by rememberSaveable(name) { mutableStateOf(false) }
    Column {
        GroupRow(
            title = naming.community(name),
            subtitle = listOfNotNull(
                when (h) { Health.Active -> "Active"; Health.Quiet -> "Quiet"; Health.Unknown -> "Not checked" },
                note,
            ).joinToString(" · "),
            leading = {
                Box {
                    Avatar(naming.label(name), 34.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(2.dp),
                    ) { HealthDot(h) }
                }
            },
            trailing = if (busier.isEmpty()) ({ Chevron() }) else ({
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "${busier.size} busier") }
            }),
            onClick = { if (busier.isEmpty()) actions.openCommunity(name) else open = !open },
        )
        AnimatedVisibility(open && busier.isNotEmpty()) {
            Column(Modifier.padding(start = 64.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                busier.forEach { copy ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(copy.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${compact(copy.activeUsers ?: 0)} active this week · ${compact(copy.subscribers ?: 0)} members",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { actions.switchCommunity(name, copy.name) }) { Text("Switch") }
                        }
                    }
                }
                TextButton(onClick = { actions.merge(name.substringBefore('@'), listOf(name) + busier.map { it.name }) }) {
                    Icon(LurkIcons.Merge, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Merge all ${busier.size + 1} into one feed")
                }
            }
        }
    }
}

// --- Merged communities ---

@Composable
private fun MergedPage(screen: SettingsScreen, account: Account, actions: SettingsActions) {
    val naming = LocalNaming.current
    LazyColumn(state = screen.listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item(key = "about") {
            Text(
                "A merged community reads the same community on several instances as one feed, with each post shown once. Make one from a community's copies in Discover or Community health.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
        if (account.merged.isEmpty()) {
            item(key = "none") { EmptyState(LurkIcons.Merge, "Nothing merged yet", "Look for “copies” on Discover cards.") }
        }
        account.merged.forEach { m ->
            item(key = m.key) { MergedCard(m, naming, actions) }
        }
    }
}

@Composable
private fun MergedCard(m: MergedCommunity, naming: app.lurk.data.Naming, actions: SettingsActions) {
    Column {
        SectionLabel(naming.community(m.label))
        RowGroup {
            GroupRow(
                title = "Open the merged feed",
                subtitle = "${m.members.size} copies, read together",
                leading = { IconTile(LurkIcons.Merge, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer) },
                trailing = { Chevron() },
                onClick = { actions.openCommunity(m.key) },
            )
            m.members.forEach { member ->
                GroupDivider()
                GroupRow(
                    title = naming.community(member),
                    leading = { Avatar(naming.label(member), 30.dp) },
                    trailing = if (m.members.size > 2) ({
                        IconButton(onClick = { actions.merge(m.label, m.members - member) }) {
                            Icon(LurkIcons.Close, "Take ${naming.community(member)} out", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }) else null,
                    onClick = { actions.openCommunity(member) },
                )
            }
            GroupDivider(inset = 0.dp)
            GroupRow(
                title = "Unmerge",
                titleColor = MaterialTheme.colorScheme.error,
                subtitle = "You stay joined to each copy",
                onClick = { actions.unmerge(m.key) },
            )
        }
    }
}
