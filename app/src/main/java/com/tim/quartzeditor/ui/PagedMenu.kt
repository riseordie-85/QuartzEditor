package com.tim.quartzeditor.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Menus that stay short: related items sit on a page of their own (tap "Image ›" and the menu shows
// those, with ← to go back), instead of one long list.

/** What a paged menu's items are built with. [within] is the page these items belong to (null = the first). */
class PagedMenuScope internal constructor(
    internal val page: String?,
    internal val go: (String?) -> Unit,
    val dismiss: () -> Unit,
    internal val within: String? = null,
) {
    /** These items are on the page being shown. */
    val visible: Boolean get() = within == page
}

@Composable
fun PagedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable PagedMenuScope.() -> Unit,
) {
    var page by remember { mutableStateOf<String?>(null) }
    // Opens on the first page each time
    LaunchedEffect(expanded) { if (!expanded) page = null }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset) {
        PagedMenuScope(page, { page = it }, onDismissRequest).content()
    }
}

/** One item. It closes the menu unless [keepOpen]. */
@Composable
fun PagedMenuScope.Item(
    label: String,
    icon: ImageVector? = null,
    checked: Boolean = false,
    enabled: Boolean = true,
    keepOpen: Boolean = false,
    onClick: () -> Unit,
) {
    if (!visible) return
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = if (checked) ({ Icon(Icons.Outlined.Check, contentDescription = "On", tint = Q.accent) }) else null,
        enabled = enabled,
        onClick = {
            if (!keepOpen) dismiss()
            onClick()
        },
    )
}

@Composable
fun PagedMenuScope.Divider() {
    if (visible) HorizontalDivider()
}

@Composable
fun PagedMenuScope.Label(text: String) {
    if (visible) Text(text, color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** Anything else (sliders, chips), only on its page. */
@Composable
fun PagedMenuScope.Custom(content: @Composable () -> Unit) {
    if (visible) content()
}

/** A page of its own: shown as "[label] ›" on the page it's on; its [content] replaces the menu when tapped. */
@Composable
fun PagedMenuScope.Page(label: String, icon: ImageVector? = null, content: @Composable PagedMenuScope.() -> Unit) {
    if (visible) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Q.textDim) },
            onClick = { go(label) },
        )
        return
    }
    if (page == label) {
        DropdownMenuItem(
            text = { Text(label, color = Q.textDim) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Q.textDim) },
            onClick = { go(within) },
        )
        HorizontalDivider()
        PagedMenuScope(page, go, dismiss, within = label).content()
    }
}

