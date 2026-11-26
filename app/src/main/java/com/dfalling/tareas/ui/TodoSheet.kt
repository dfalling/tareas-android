package com.dfalling.tareas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.dfalling.tareas.R
import com.dfalling.tareas.data.TodoEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val displayDate = DateTimeFormatter.ofPattern("dd-MM-yy")

/** Server limits (Appendix A), enforced while typing by rune (code point), not UTF-16 unit. */
internal const val MAX_TITLE_RUNES = 500
internal const val MAX_NOTES_RUNES = 10_000

/** Cuts a string to at most [max] code points. */
internal fun takeRunes(text: String, max: Int): String =
    if (text.codePointCount(0, text.length) <= max) {
        text
    } else {
        text.substring(0, text.offsetByCodePoints(0, max))
    }

private fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * Create/edit sheet (PLAN.md §7): title, notes, nullable due date via the
 * Material3 date picker. Saves are optimistic — they land in Room first and
 * the sync engine carries them over. In edit mode it also offers the complete
 * toggle and delete (with the "this is permanent, here and on the server"
 * confirmation the plan asks for). A share intent opens this sheet in create
 * mode with the intent's content passed as [prefillTitle]/[prefillNotes].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoSheet(
    initial: TodoEntity?,
    prefillTitle: String = "",
    prefillNotes: String? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String?, dueDate: LocalDate?) -> Unit,
    onToggleComplete: ((completed: Boolean) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val editing = initial != null
    // prefill* is the share-target path (create mode, initial == null): the
    // intent's subject/text. The remember keys make a share that lands while
    // the sheet is already open replace the fields.
    var title by remember(prefillTitle) { mutableStateOf(initial?.title ?: prefillTitle) }
    var notes by remember(prefillNotes) { mutableStateOf(initial?.notes ?: prefillNotes ?: "") }
    var dueDate by remember { mutableStateOf(initial?.dueDate) }
    var completed by remember { mutableStateOf(initial?.completed ?: false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(if (editing) R.string.todo_title else R.string.sheet_create_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = takeRunes(it, MAX_TITLE_RUNES) },
                label = { Text(stringResource(R.string.todo_title)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = takeRunes(it, MAX_NOTES_RUNES) },
                label = { Text(stringResource(R.string.todo_notes)) },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                    Spacer(Modifier.padding(start = 6.dp))
                    Text(
                        text =
                            dueDate?.let { displayDate.format(it) }
                                ?: stringResource(R.string.due_date_none),
                    )
                }
                if (dueDate != null) {
                    IconButton(onClick = { dueDate = null }) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.due_date_clear),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (editing && onToggleComplete != null) {
                    FilledTonalButton(
                        onClick = {
                            completed = !completed
                            onToggleComplete(completed)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(if (completed) R.string.mark_reopen else R.string.mark_complete),
                        )
                    }
                }
                Button(
                    enabled = title.isNotBlank(),
                    onClick = { onSave(title.trim(), notes.ifBlank { null }, dueDate) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
            if (editing && onDelete != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueDate?.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dueDate = pickerState.selectedDateMillis?.toPickerDate()
                        showDatePicker = false
                    },
                ) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text, title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete?.invoke()
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}
