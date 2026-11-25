package com.dfalling.tareas.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dfalling.tareas.BuildConfig
import com.dfalling.tareas.R
import com.dfalling.tareas.data.Settings

/**
 * The top-bar icon's sheet: which account, which server, which build — and the
 * way out. Logout revokes the token on the server best-effort, keeps Room data
 * and the prefill, and returns to the login screen (MainActivity flips on
 * hasToken).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    settings: Settings,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.account),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(settings.email ?: "") },
                supportingContent = {
                    Text(
                        text =
                            settings.serverUrl?.ifBlank { null } ?: stringResource(R.string.account_server),
                    )
                },
                leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.version)) },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(
                onClick = onLogout,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.log_out),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
