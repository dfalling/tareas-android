package com.dfalling.tareas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dfalling.tareas.R
import com.dfalling.tareas.data.TodoEntity
import com.dfalling.tareas.domain.Tabs
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val displayDate = DateTimeFormatter.ofPattern("dd-MM-yy")

/**
 * One todo line, mirroring the web row: complete-toggle checkbox, title,
 * one-line notes preview, and the due info — red "overdue · dd-MM-yy" when
 * overdue (web UI rule: due today is NOT overdue), a plain dd-MM-yy chip
 * otherwise ([showDue] = false on Upcoming, where the group header owns the
 * date). Queued rows carry a faint cloud-upload marker until their sync lands.
 */
@Composable
fun TodoRow(
    todo: TodoEntity,
    today: LocalDate,
    showDue: Boolean,
    onToggleComplete: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overdue = Tabs.isOverdue(todo, today)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = todo.completed,
            onCheckedChange = { onToggleComplete() },
            modifier = Modifier.padding(end = 4.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = todo.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
                color =
                    if (todo.completed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            )
            todo.notes?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showDue && todo.dueDate != null) {
                Text(
                    text =
                        if (overdue) stringResource(R.string.overdue_label, displayDate.format(todo.dueDate))
                        else displayDate.format(todo.dueDate),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (todo.isPending) {
            Icon(
                painter = painterResource(R.drawable.ic_pending_sync),
                contentDescription = stringResource(R.string.pending_sync),
                tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .size(16.dp),
            )
        }
    }
}
