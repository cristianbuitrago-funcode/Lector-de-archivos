package com.funcodestudiocb.analyzer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

/** Cabecera de marca: nombre del producto con acento dorado y estudio. */
@Composable
fun BrandHeader(modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(if (compact) 28.dp else 40.dp)
                    .background(
                        Brush.linearGradient(listOf(FuncodeColors.Electric, FuncodeColors.Navy2)),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("</>", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "FUNCODE",
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
                    color = FuncodeColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "CODE ANALYZER",
                    style = MaterialTheme.typography.labelSmall,
                    color = FuncodeColors.ElectricSoft,
                )
            }
        }
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.brand_studio),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Panel con borde sutil, base de las tarjetas de la app. */
@Composable
fun FuncodePanel(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.outline,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Etiqueta de estado con color. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Campo de contraseña con botón para mostrarla u ocultarla. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    isError: Boolean = false,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        isError = isError,
        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        trailingIcon = {
            val description = stringResource(if (visible) R.string.action_hide_password else R.string.action_show_password)
            IconButton(
                onClick = { visible = !visible },
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Text(
                    stringResource(if (visible) R.string.action_hide else R.string.action_show),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        supportingText = supportingText?.let { { Text(it) } },
    )
}

/**
 * Hace visibles los caracteres invisibles o de control que puedan venir en textos del motor (p. ej.
 * U+202E, que invierte el sentido del texto y se usa para disfrazar nombres de archivo).
 */
fun String.revealControlCharacters(): String = buildString {
    for (ch in this@revealControlCharacters) {
        val invisible = Character.isISOControl(ch) ||
            Character.getType(ch) == Character.FORMAT.toInt() ||
            ch in ' '..' '
        if (invisible && ch != '\n') append("\\u%04X".format(ch.code)) else append(ch)
    }
}
