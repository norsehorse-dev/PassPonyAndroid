package com.passpony.android.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.store.GeneratorPrefs
import com.passpony.android.store.GeneratorSettings
import com.passpony.android.ui.util.SecureScreenEffect
import kotlinx.coroutines.launch
import uniffi.pass_ffi.SymbolSet
import uniffi.pass_ffi.generatorSymbols

/** The slider covers the everyday range; the field reaches the core's ceiling. */
private const val SLIDER_MAX = 64

/**
 * The generator's options: length, character classes, and which symbols,
 * with a live preview that regenerates on every change. Use hands the
 * shown password to the caller and remembers the settings for the
 * one-tap Generate button (GeneratorPrefs, per device). The preview is
 * shown in the clear, since the point is to look at it, under
 * FLAG_SECURE; it is plain Compose state and goes with the sheet.
 *
 * Every rule about what the settings mean lives in pass-core's
 * generate.rs, reached through the FFI; this sheet only collects
 * choices and displays what the core produces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorSheet(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var settings by remember { mutableStateOf(GeneratorPrefs.snapshot(context)) }
    var lengthText by remember { mutableStateOf(settings.length.toString()) }
    var preview by remember { mutableStateOf("") }
    var regenerateTick by remember { mutableStateOf(0) }
    val basicSymbols = remember { generatorSymbols(SymbolSet.BASIC) }

    SecureScreenEffect()

    LaunchedEffect(settings, regenerateTick) {
        preview = if (settings.isValid) PasswordGenerator.generate(settings) else ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Text(stringResource(R.string.generator_sheet_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            // Preview first, so a change to anything below is visible at once.
            Text(
                stringResource(R.string.generator_preview),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { regenerateTick++ }, enabled = settings.isValid) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.generator_regenerate))
                }
            }
            if (!settings.hasAnyClass) {
                Text(
                    stringResource(R.string.generator_no_classes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.generator_length),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lengthText,
                    onValueChange = { text ->
                        val digits = text.filter { it.isDigit() }.take(3)
                        lengthText = digits
                        val parsed = digits.toIntOrNull()
                        if (parsed != null && parsed in GeneratorSettings.MIN_LENGTH..GeneratorSettings.MAX_LENGTH) {
                            settings = settings.copy(length = parsed)
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = lengthText.toIntOrNull()?.let {
                        it !in GeneratorSettings.MIN_LENGTH..GeneratorSettings.MAX_LENGTH
                    } ?: true,
                    modifier = Modifier.width(96.dp)
                )
            }
            Slider(
                value = settings.length.coerceAtMost(SLIDER_MAX).toFloat(),
                onValueChange = { value ->
                    val length = value.toInt()
                    settings = settings.copy(length = length)
                    lengthText = length.toString()
                },
                valueRange = GeneratorSettings.MIN_LENGTH.toFloat()..SLIDER_MAX.toFloat(),
                steps = SLIDER_MAX - GeneratorSettings.MIN_LENGTH - 1,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.generator_length_hint, GeneratorSettings.MIN_LENGTH, GeneratorSettings.MAX_LENGTH),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))

            ClassSwitch(
                label = stringResource(R.string.generator_lowercase),
                checked = settings.lowercase,
                onChange = { settings = settings.copy(lowercase = it) }
            )
            ClassSwitch(
                label = stringResource(R.string.generator_uppercase),
                checked = settings.uppercase,
                onChange = { settings = settings.copy(uppercase = it) }
            )
            ClassSwitch(
                label = stringResource(R.string.generator_digits),
                checked = settings.digits,
                onChange = { settings = settings.copy(digits = it) }
            )
            Spacer(Modifier.height(8.dp))

            Text(stringResource(R.string.generator_symbols), style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val options = GeneratorSettings.Symbols.entries
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = settings.symbols == option,
                        onClick = { settings = settings.copy(symbols = option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) { Text(symbolsLabel(option)) }
                }
            }
            Text(
                when (settings.symbols) {
                    GeneratorSettings.Symbols.NONE -> stringResource(R.string.generator_symbols_none_hint)
                    GeneratorSettings.Symbols.BASIC -> stringResource(R.string.generator_symbols_basic_hint, basicSymbols)
                    GeneratorSettings.Symbols.FULL -> stringResource(R.string.generator_symbols_full_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val chosen = preview
                        val chosenSettings = settings
                        // Save first, then hand the password over: the scope
                        // ends with the sheet, so the save has to finish before
                        // the caller closes it.
                        scope.launch {
                            GeneratorPrefs.save(context, chosenSettings)
                            onUse(chosen)
                        }
                    },
                    enabled = settings.isValid && preview.isNotEmpty()
                ) { Text(stringResource(R.string.generator_use)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ClassSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun symbolsLabel(symbols: GeneratorSettings.Symbols): String = stringResource(
    when (symbols) {
        GeneratorSettings.Symbols.NONE -> R.string.generator_symbols_none
        GeneratorSettings.Symbols.BASIC -> R.string.generator_symbols_basic
        GeneratorSettings.Symbols.FULL -> R.string.generator_symbols_full
    }
)
