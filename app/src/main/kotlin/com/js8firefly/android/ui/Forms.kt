package com.js8firefly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * Grouped-form building blocks that stand in for SwiftUI's Form / Section /
 * LabeledContent / Toggle / Picker / Stepper in the Firefly dark style.
 */

val FormRowBg = Color(0xFF1C1F2A)

@Composable
fun FormSection(
    title: String? = null,
    icon: ImageVector? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) {
            Row(
                Modifier.padding(start = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon != null) Icon(icon, null, tint = Theme.dim, modifier = Modifier.size(15.dp))
                Text(title.uppercase(), style = Theme.rounded(12), color = Theme.dim)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Theme.card.copy(alpha = 0.95f)),
        ) {
            content()
        }
        if (footer != null) {
            Text(footer, style = Theme.rounded(12), color = Theme.dim, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
fun FormRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Column {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Theme.stroke)
    }
}

@Composable
fun FormText(text: String, color: Color = Theme.dim, style: TextStyle = Theme.rounded(12)) {
    FormRow { Text(text, style = style, color = color, modifier = Modifier.weight(1f)) }
}

@Composable
fun LabeledValue(label: String, value: String, valueStyle: TextStyle = Theme.rounded(16)) {
    FormRow {
        Text(label, style = Theme.rounded(16), color = Theme.text)
        Spacer(Modifier.weight(1f))
        Text(value, style = valueStyle, color = Theme.dim, textAlign = TextAlign.End)
    }
}

@Composable
fun FormToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    FormRow(onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Text(label, style = Theme.rounded(16), color = Theme.text, modifier = Modifier.weight(1f).dimIf(!enabled))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Theme.rx,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Theme.cardHi,
                uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** A Picker in menu style: label on the left, current value and a chevron on the right. */
@Composable
fun <T> FormPicker(label: String, selection: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FormRow(onClick = { open = true }) {
        Text(label, style = Theme.rounded(16), color = Theme.text)
        Spacer(Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    options.firstOrNull { it.first == selection }?.second ?: "",
                    style = Theme.rounded(15),
                    color = Theme.dim,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
                Icon(Icons.Filled.UnfoldMore, null, tint = Theme.dim, modifier = Modifier.size(16.dp))
            }
            FireflyMenu(open, { open = false }) {
                options.forEach { (value, title) ->
                    MenuItem(title, checked = value == selection) {
                        onSelect(value)
                        open = false
                    }
                }
            }
        }
    }
}

@Composable
fun FireflyMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Theme.cardHi,
        shape = RoundedCornerShape(12.dp),
        content = content,
    )
}

@Composable
fun MenuItem(
    title: String,
    checked: Boolean = false,
    enabled: Boolean = true,
    color: Color = Theme.text,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title, style = Theme.rounded(15), maxLines = 1) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = if (checked) ({ Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)) })
        else if (icon != null) ({ Icon(icon, null, modifier = Modifier.size(18.dp)) }) else null,
        colors = MenuDefaults.itemColors(
            textColor = color,
            leadingIconColor = color,
            disabledTextColor = Theme.faint,
            disabledLeadingIconColor = Theme.faint,
        ),
    )
}

@Composable
fun MenuHeader(title: String) {
    Text(title, style = Theme.rounded(12), color = Theme.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

/** SwiftUI Stepper: label and value, with − / + buttons. */
@Composable
fun FormStepper(label: String, value: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    FormRow {
        Text(label, style = Theme.rounded(16), color = Theme.text)
        Spacer(Modifier.weight(1f))
        Text(value, style = Theme.rounded(16), color = Theme.dim)
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Theme.cardHi),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable(onClick = onDecrement).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Filled.Remove, "Decrement", tint = Theme.text, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.width(0.5.dp).heightIn(min = 18.dp).background(Theme.faint))
            Box(Modifier.clickable(onClick = onIncrement).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Filled.Add, "Increment", tint = Theme.text, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Integer stepper over a range with a step. */
@Composable
fun IntStepper(label: String, value: Int, range: IntRange, step: Int = 1, display: String, onChange: (Int) -> Unit) {
    FormStepper(label, display,
        onDecrement = { onChange((value - step).coerceIn(range)) },
        onIncrement = { onChange((value + step).coerceIn(range)) })
}

@Composable
fun DoubleStepper(label: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, display: String, onChange: (Double) -> Unit) {
    fun snap(v: Double) = (Math.round(v / step) * step).coerceIn(range.start, range.endInclusive)
    FormStepper(label, display, onDecrement = { onChange(snap(value - step)) }, onIncrement = { onChange(snap(value + step)) })
}

@Composable
fun FireflySlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Theme.accent,
            inactiveTrackColor = Theme.cardHi,
        ),
    )
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    FormRow {
        Text(label, style = Theme.rounded(16), color = Theme.text)
        FireflySlider(value, onChange, range, Modifier.weight(1f))
    }
}

/** A plain, borderless text field like SwiftUI's TextField in a Form. */
@Composable
fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Theme.rounded(16),
    color: Color = Theme.text,
    align: TextAlign = TextAlign.Start,
    uppercase: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    onSubmit: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(if (uppercase) it.uppercase() else it) },
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = style.copy(color = color, textAlign = align),
        cursorBrush = SolidColor(Theme.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = if (uppercase) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences,
            autoCorrectEnabled = !uppercase,
            keyboardType = keyboardType,
            imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        decorationBox = { inner ->
            Box(contentAlignment = if (align == TextAlign.End) Alignment.CenterEnd else if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = Theme.faint, textAlign = align, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    style: TextStyle = Theme.rounded(16),
    color: Color = Theme.text,
    uppercase: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    FormRow {
        Text(label, style = Theme.rounded(16), color = Theme.text)
        PlainField(value, onChange, placeholder, Modifier.weight(1f), style, color, TextAlign.End, uppercase, keyboardType, enabled)
    }
}

@Composable
fun FormButton(title: String, color: Color = Theme.accent, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    FormRow(onClick = if (enabled) onClick else null) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp).dimIf(!enabled))
        Text(title, style = Theme.rounded(16), color = color, modifier = Modifier.dimIf(!enabled))
    }
}

/** A bottom sheet with an inline title bar and a Done button, like a NavigationStack sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireflySheet(
    title: String,
    onDismiss: () -> Unit,
    fullHeight: Boolean = false,
    dismissLabel: String = "Done",
    content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Theme.bg,
        contentColor = Theme.text,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            Box(Modifier.padding(top = 8.dp).size(36.dp, 5.dp).clip(CircleShape).background(Theme.faint))
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(title, style = Theme.rounded(17, SemiBold), color = Theme.text, modifier = Modifier.align(Alignment.Center))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(dismissLabel, style = Theme.rounded(17, SemiBold), color = Theme.accent)
            }
        }
        Column(Modifier.fillMaxWidth(), content = content)
    }
}
