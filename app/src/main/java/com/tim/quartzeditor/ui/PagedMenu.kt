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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
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
    /** Pages open beside the menu (the menu bar's way) instead of replacing it. */
    internal val cascade: Boolean = false,
    /** Cascading: the pages open, one column each beside the menu (a page within a page goes further right). */
    internal val path: androidx.compose.runtime.MutableState<List<String>>? = null,
    /** Cascading: which column these items are in (0 = the menu itself). */
    internal val column: Int = 0,
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
    /** Pages open in a menu of their own beside this one (tap, or hover with a pen or mouse). */
    cascade: Boolean = false,
    content: @Composable PagedMenuScope.() -> Unit,
) {
    var page by remember { mutableStateOf<String?>(null) }
    val path = remember { mutableStateOf<List<String>>(emptyList()) }
    // Opens on the first page each time
    LaunchedEffect(expanded) {
        if (!expanded) {
            page = null
            path.value = emptyList()
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset) {
        if (!cascade) {
            PagedMenuScope(page, { page = it }, onDismissRequest).content()
            return@DropdownMenu
        }
        // The menu, then each open page in a column beside it (all in the one menu, so they never overlap)
        androidx.compose.foundation.layout.Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
            androidx.compose.foundation.layout.Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                PagedMenuScope(null, {}, onDismissRequest, cascade = true, path = path, column = 0).content()
            }
            path.value.forEachIndexed { i, label ->
                androidx.compose.material3.VerticalDivider(Modifier.fillMaxHeight())
                androidx.compose.foundation.layout.Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                    PagedMenuScope(label, {}, onDismissRequest, cascade = true, path = path, column = i + 1).content()
                }
            }
        }
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
        leadingIcon = if (icon != null) ({ Icon(icon, contentDescription = null) }) else null,
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
    if (cascade) {
        if (visible) CascadeItem(label, icon)
        // (Built always, so its items show in the column of the page that's open)
        PagedMenuScope(page, go, dismiss, within = label, cascade = true, path = path, column = column).content()
        return
    }
    if (visible) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = if (icon != null) ({ Icon(icon, contentDescription = null) }) else null,
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
    }
    // Always built, so a page inside this one can show when it's open (items hide themselves otherwise)
    PagedMenuScope(page, go, dismiss, within = label).content()
}


/** A page's item in a cascading menu: opens it in the column to the right (tap, or hover with a pen or mouse). */
@Composable
private fun PagedMenuScope.CascadeItem(label: String, icon: ImageVector?) {
    val holder = path ?: return
    val open = holder.value.getOrNull(column) == label
    fun openIt() {
        if (!open) holder.value = holder.value.take(column) + label
    }
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = if (icon != null) ({ Icon(icon, contentDescription = null) }) else null,
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Q.textDim) },
        modifier = (if (open) Modifier.background(Q.text.copy(alpha = 0.10f)) else Modifier)
            .pointerInput(label, column) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: continue
                        val hoverer = c.type == PointerType.Stylus || c.type == PointerType.Mouse || c.type == PointerType.Eraser
                        if (hoverer && !c.pressed && (e.type == PointerEventType.Enter || e.type == PointerEventType.Move)) openIt()
                    }
                }
            },
        onClick = { openIt() },
    )
}
