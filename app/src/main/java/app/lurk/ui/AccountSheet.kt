package app.lurk.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lurk.data.Account
import kotlinx.coroutines.launch

/** What the account sheet can do. Each closes the sheet first. */
class AccountActions(
    val signIn: () -> Unit,
    val retrySync: () -> Unit,
    val switchTo: (Account) -> Unit,
    /** Switches to a signed-out account and opens its sign-in. */
    val signInTo: (Account) -> Unit,
    val addReddit: () -> Unit,
    val addRedditRss: () -> Unit,
    val addLemmy: () -> Unit,
    val addBluesky: () -> Unit,
    val setNsfw: (Boolean) -> Unit,
    val setPin: () -> Unit,
    val removePin: () -> Unit,
    val signOut: () -> Unit,
    /** Offer standard Reddit accounts (signed in on Reddit's site), or hide them. */
    val setStandardReddit: (Boolean) -> Unit = {},
    /** Leave posts already seen out of this account's feeds. */
    val setHideSeen: (Boolean) -> Unit = {},
)

/**
 * The account switcher: the current account and how its sign-in is doing, its settings,
 * then the other accounts and adding one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    current: Account,
    accounts: List<Account>,
    /** True when an account needs its PIN before its name may show. */
    hidden: (Account) -> Boolean,
    multiProfile: Boolean,
    syncing: Boolean,
    syncProblem: String?,
    authNote: String?,
    actions: AccountActions,
    /** Standard Reddit accounts are offered. */
    standardReddit: Boolean,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); action() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { SheetHandle() },
    ) {
        AccountSheetContent(current, accounts, hidden, multiProfile, syncing, syncProblem, authNote, actions, ::pick, standardReddit)
    }
}

/** The sheet's contents. [pick] closes the sheet, then runs the action. */
@Composable
internal fun AccountSheetContent(
    current: Account,
    accounts: List<Account>,
    hidden: (Account) -> Boolean,
    multiProfile: Boolean,
    syncing: Boolean,
    syncProblem: String?,
    authNote: String?,
    actions: AccountActions,
    pick: (() -> Unit) -> Unit,
    standardReddit: Boolean = true,
) {
    val others = accounts.filter { it.id != current.id }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        CurrentAccountCard(
            current, syncing, syncProblem, authNote,
            onSignIn = { pick(actions.signIn) },
            onRetry = actions.retrySync,
        )

        SectionLabel("This account")
        RowGroup {
            SwitchRow(
                icon = LurkIcons.Eye,
                title = "Show NSFW without tapping",
                subtitle = "Spoilers still stay hidden",
                checked = current.showNsfw,
                onChange = actions.setNsfw,
            )
            GroupDivider()
            SwitchRow(
                icon = LurkIcons.EyeOff,
                title = "Hide posts I've seen",
                subtitle = "Feeds show only posts you haven't scrolled past",
                checked = current.hideSeen,
                onChange = actions.setHideSeen,
            )
            if (current.label != null) {
                GroupDivider()
                SwitchRow(
                    icon = LurkIcons.Lock,
                    title = "Require PIN",
                    subtitle = "Ask for it when switching to this account",
                    checked = current.locked,
                    onChange = { on -> pick(if (on) actions.setPin else actions.removePin) },
                )
            }
        }

        if (others.isNotEmpty()) {
            SectionLabel("Switch account")
            RowGroup {
                others.forEachIndexed { i, a ->
                    if (i > 0) GroupDivider()
                    OtherAccountRow(
                        a, hidden(a),
                        onClick = { pick { actions.switchTo(a) } },
                        onSignIn = { pick { actions.signInTo(a) } },
                    )
                }
            }
        }

        if (multiProfile) {
            SectionLabel("Add account")
            RowGroup {
                AddRow("Reddit", "Front page and subreddits, from RSS", LurkIcons.Rss) { pick(actions.addRedditRss) }
                if (standardReddit) {
                    GroupDivider()
                    AddRow("Reddit (website)", "Signed in on Reddit's site", LurkIcons.Globe) { pick(actions.addReddit) }
                }
                GroupDivider()
                AddRow("Lemmy", "Any instance, or PieFed", LurkIcons.Plus) { pick(actions.addLemmy) }
                GroupDivider()
                AddRow("Bluesky", "Your handle and an app password", LurkIcons.Plus) { pick(actions.addBluesky) }
            }

            SectionLabel("Lurk")
            RowGroup {
                SwitchRow(
                    icon = LurkIcons.Globe,
                    title = "Standard Reddit accounts",
                    subtitle = if (standardReddit) "Signed in on Reddit's site. Reddit blocks Lurk's browser right now."
                        else "Hidden while off. Reddit blocks Lurk's browser right now.",
                    checked = standardReddit,
                    onChange = { on -> pick { actions.setStandardReddit(on) } },
                )
            }
        }

        val canRemove = current.signedIn || current.expired || (multiProfile && accounts.size > 1)
        if (canRemove) {
            Spacer(Modifier.height(20.dp))
            RowGroup {
                GroupRow(
                    title = when {
                        current.signedIn -> "Sign out of ${current.label}"
                        current.label != null -> "Remove ${current.label}"
                        else -> "Remove this account"
                    },
                    titleColor = MaterialTheme.colorScheme.error,
                    leading = { IconTile(LurkIcons.LogOut, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer) },
                    onClick = { pick(actions.signOut) },
                )
            }
        }
        if (!multiProfile) {
            Text(
                "More than one account needs a newer Android System WebView. Update it from the Play Store.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun CurrentAccountCard(
    account: Account,
    syncing: Boolean,
    syncProblem: String?,
    authNote: String?,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(account, 52.dp, attention = account.expired)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        account.label ?: "Not signed in",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusLine(account, syncing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (account.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when {
                account.expired -> {
                    Notice(
                        authNote ?: "The site ended this sign-in. Your subscriptions and settings are kept.",
                        warning = false,
                    )
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text("Sign in again as ${account.label}")
                    }
                }
                !account.signedIn -> {
                    Text(
                        "Sign in to see your subscriptions, vote and save.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text("Sign in to ${account.siteName}")
                    }
                }
                else -> authNote?.let { Notice(it, warning = true) }
            }

            if (syncProblem != null && !syncing) {
                Notice(
                    "Couldn't check the sign-in, so nothing was changed. $syncProblem",
                    warning = true,
                )
                FilledTonalButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Try again")
                }
            }
        }
    }
}

private fun statusLine(account: Account, syncing: Boolean): String = buildString {
    append(account.siteName)
    when {
        syncing -> append(" · Checking…")
        account.expired -> append(" · Signed out")
        account.signedIn && account.syncedAt > 0 -> append(" · Checked ").append(ago(account.syncedAt).lowercase())
    }
}

private fun ago(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** A tinted box with an icon: [warning] for "can't tell", otherwise an error. */
@Composable
private fun Notice(text: String, warning: Boolean) {
    val (bg, fg) = if (warning) {
        MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(12.dp),
    ) {
        Icon(if (warning) LurkIcons.Info else LurkIcons.Alert, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.padding(start = 10.dp))
    }
}

/** The account's initial (or a generic icon), with a dot when it needs attention. */
@Composable
fun AccountAvatar(account: Account, size: Dp, attention: Boolean = false, hidden: Boolean = false) {
    val name = (account.username ?: account.knownAs)?.takeIf { !hidden }
    Box {
        if (name != null) {
            Avatar(name, size)
        } else {
            Icon(
                if (hidden) Icons.Default.Lock else Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size).padding(if (hidden) size / 5 else 0.dp),
            )
        }
        if (attention) AttentionDot(Modifier.align(Alignment.BottomEnd), size / 3.5f)
    }
}

@Composable
fun AttentionDot(modifier: Modifier, size: Dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(2.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
    )
}

@Composable
private fun OtherAccountRow(account: Account, hidden: Boolean, onClick: () -> Unit, onSignIn: () -> Unit) {
    GroupRow(
        title = if (hidden) "Private account" else account.label ?: "Not signed in",
        subtitle = when {
            hidden -> "${account.siteName} · PIN needed"
            account.expired -> "${account.siteName} · Signed out"
            else -> account.siteName
        },
        subtitleColor = if (account.expired && !hidden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        leading = { AccountAvatar(account, 36.dp, attention = account.expired && !hidden, hidden = hidden) },
        trailing = when {
            account.expired && !hidden -> ({ TextButton(onClick = onSignIn) { Text("Sign in") } })
            account.locked && !hidden -> ({
                Icon(LurkIcons.Lock, "Needs PIN", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            })
            else -> null
        },
        onClick = onClick,
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    GroupRow(
        title = title,
        subtitle = subtitle,
        leading = { IconTile(icon) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun AddRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    GroupRow(
        title = title,
        subtitle = subtitle,
        leading = { IconTile(icon) },
        trailing = { Icon(LurkIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

/** The top-bar button that opens [AccountSheet]. */
@Composable
fun AccountButton(current: Account, attention: Boolean, onClick: () -> Unit) {
    val description = buildString {
        append("Accounts")
        current.label?.let { append(", ").append(it) }
        if (attention) append(", needs attention")
    }
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        AccountAvatar(current, 32.dp, attention = attention)
    }
}
