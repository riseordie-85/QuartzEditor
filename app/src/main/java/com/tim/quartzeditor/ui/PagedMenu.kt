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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
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
    /** Cascading: which page on this menu is open beside it. */
    internal val openSub: androidx.compose.runtime.MutableState<String?>? = null,
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
    val openSub = remember { mutableStateOf<String?>(null) }
    // Opens on the first page each time
    LaunchedEffect(expanded) {
        if (!expanded) {
            page = null
            openSub.value = null
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset) {
        PagedMenuScope(page, { page = it }, onDismissRequest, cascade = cascade, openSub = openSub).content()
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
        CascadePage(label, icon, content)
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


/** A page that opens beside its menu, at the height of its item (one at a time on each menu). */
@Composable
private fun PagedMenuScope.CascadePage(label: String, icon: ImageVector?, content: @Composable PagedMenuScope.() -> Unit) {
    val holder = openSub ?: return
    val open = holder.value == label
    var itemSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inner = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) { if (!open) inner.value = null }
    androidx.compose.foundation.layout.Box(
        Modifier
            .onSizeChanged { itemSize = it }
            // A pen or mouse hovering over it opens it
            .pointerInput(label) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: continue
                        val hoverer = c.type == PointerType.Stylus || c.type == PointerType.Mouse || c.type == PointerType.Eraser
                        if (hoverer && !c.pressed && (e.type == PointerEventType.Enter || e.type == PointerEventType.Move)) holder.value = label
                    }
                }
            },
    ) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = if (icon != null) ({ Icon(icon, contentDescription = null) }) else null,
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Q.textDim) },
            modifier = if (open) Modifier.background(Q.text.copy(alpha = 0.10f)) else Modifier,
            onClick = { holder.value = label },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { holder.value = null },
            // Not focusable: touches and hovers still reach the menu it came from (another page, an item)
            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
            offset = with(density) { DpOffset(itemSize.width.toDp(), -itemSize.height.toDp()) },
            modifier = Modifier.heightIn(max = 520.dp),
        ) {
            PagedMenuScope(null, go, dismiss, within = null, cascade = true, openSub = inner).content()
        }
    }
}
