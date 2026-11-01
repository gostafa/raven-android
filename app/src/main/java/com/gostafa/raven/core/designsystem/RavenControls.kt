package com.gostafa.raven.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions

@Composable
fun RavenButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier.heightIn(min = 48.dp), enabled, shape,
    colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF242424), disabledContentColor = Color(0xFFA3A3A3)),
    contentPadding = contentPadding, content = content)

@Composable
fun RavenOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled, shape,
    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black),
    border = border, contentPadding = contentPadding, content = content)

@Composable
fun RavenAlertDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Black,
) {
    AlertDialog(
        onDismissRequest, confirmButton,
        modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        dismissButton = dismissButton, title = title, text = text,
        shape = RoundedCornerShape(12.dp), containerColor = containerColor, tonalElevation = 0.dp,
    )
}

@Composable
fun RavenDropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit,
    containerColor: Color = Color.Black, content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp), containerColor = containerColor, tonalElevation = 0.dp, shadowElevation = 0.dp,
        content = content)
}

@Composable
fun RavenTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default, singleLine: Boolean = false,
    minLines: Int = 1, maxLines: Int = Int.MAX_VALUE, shape: Shape = RoundedCornerShape(8.dp),
) {
    OutlinedTextField(value, onValueChange, modifier, enabled = enabled, label = label,
        placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        supportingText = supportingText, isError = isError, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        shape = shape, colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF555555), unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = Color.Black, unfocusedContainerColor = Color.Black,
            disabledContainerColor = Color.Black, errorContainerColor = Color.Black,
        ))
}
