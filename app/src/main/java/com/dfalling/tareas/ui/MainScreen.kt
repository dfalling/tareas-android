package com.dfalling.tareas.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dfalling.tareas.R
import com.dfalling.tareas.data.TodoEntity
import com.dfalling.tareas.domain.DayGroup
import com.dfalling.tareas.domain.Tab
import com.dfalling.tareas.domain.Tabs
import com.dfalling.tareas.sync.SyncState
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Tab.labelRes: Int
    get() =
        when (this) {
            Tab.TODAY -> R.string.tab_today
            Tab.UPCOMING -> R.string.tab_upcoming
            Tab.UNSCHEDULED -> R.string.tab_unscheduled
            Tab.COMPLETED -> R.string.tab_completed
        }

/**
 * The logged-in screen: top bar (title, relative last-synced line, tiny sync
 * spinner), four swipeable tabs over grouped LazyColumns, pull-to-refresh, and
 * the create/edit sheet. State flows down from [MainViewModel]; taps flow back
 * as repository calls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val todos by viewModel.todos.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(message.resolve(resources)) }
    }

    // "Today" is the device timezone's date, recomputed on every resume (§6).
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }

    // Relative "Synced …" text, ticked every 30 s — and immediately whenever a
    // sync lands, so it never shows a stale bucket right after one completes.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(settings?.lastSyncedAt) { tick = System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick = System.currentTimeMillis()
        }
    }

    // null = no sheet; a TodoEntity = editing it; CREATE = composing a new one.
    var editing by remember { mutableStateOf<TodoEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    val syncingDescription = stringResource(R.string.sync_state_syncing)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            text = subtitleText(syncState, settings?.lastSyncedAt, tick),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (syncState is SyncState.Error) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    if (syncState is SyncState.Syncing) {
                        // Tiny top-bar spinner; the subtitle keeps the synced age.
                        Box(
                            modifier =
                                Modifier
                                    .padding(end = 12.dp)
                                    .semantics { contentDescription = syncingDescription },
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    IconButton(onClick = { showAccount = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.account))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sheet_create_title))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val pagerState = rememberPagerState { Tab.entries.size }
            TabRow(selectedTabIndex = pagerState.currentPage) {
                Tab.entries.forEach { tab ->
                    Tab(
                        selected = pagerState.currentPage == tab.ordinal,
                        onClick = { scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
                        text = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
            PullToRefreshBox(
                isRefreshing = syncState is SyncState.Syncing,
                onRefresh = { scope.launch { viewModel.syncNow() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    TabPage(
                        tab = Tab.entries[page],
                        todos = todos,
                        today = today,
                        onOpen = { editing = it },
                        onToggleComplete = { todo -> viewModel.setCompleted(todo.localId, !todo.completed) },
                    )
                }
            }
        }
    }

    if (creating) {
        TodoSheet(
            initial = null,
            onDismiss = { creating = false },
            onSave = { title, notes, dueDate ->
                creating = false
                viewModel.create(title, notes, dueDate)
            },
        )
    }
    editing?.let { todo ->
        TodoSheet(
            initial = todo,
            onDismiss = { editing = null },
            onSave = { title, notes, dueDate ->
                editing = null
                viewModel.save(todo.localId, title, notes, dueDate)
            },
            onToggleComplete = { completed ->
                viewModel.setCompleted(todo.localId, completed)
                editing = todo.copy(completed = completed)
            },
            onDelete = {
                editing = null
                viewModel.delete(todo)
            },
        )
    }
    if (showAccount) {
        settings?.let {
            AccountSheet(
                settings = it,
                onDismiss = { showAccount = false },
                onLogout = {
                    showAccount = false
                    viewModel.logout()
                },
            )
        }
    }
}

/** Subtitle under the app name. The relative synced age stays put while the
 * spinner runs (the spinner IS the syncing signal); errors and sign-outs take
 * over the line so they're never missed. */
@Composable
private fun subtitleText(state: SyncState, lastSyncedAt: Long?, tick: Long): String =
    when (state) {
        is SyncState.Error -> state.reason.asString()
        SyncState.NeedsAuth -> stringResource(R.string.sync_state_needs_auth)
        SyncState.Idle, SyncState.Syncing -> syncAgeText(lastSyncedAt, tick).asString()
    }

@Composable
private fun TabPage(
    tab: Tab,
    todos: List<TodoEntity>,
    today: LocalDate,
    onOpen: (TodoEntity) -> Unit,
    onToggleComplete: (TodoEntity) -> Unit,
) {
    // Re-slicing the full list per page is trivial at todo scale and keeps the
    // grouping logic in one pure place (Tabs.partition, unit-tested).
    val sections = Tabs.partition(todos, today)
    when (tab) {
        Tab.TODAY -> TodoList(sections.today, R.string.empty_today, today, showDue = true, onOpen, onToggleComplete)
        Tab.UNSCHEDULED ->
            TodoList(sections.unscheduled, R.string.empty_unscheduled, today, showDue = true, onOpen, onToggleComplete)
        Tab.COMPLETED ->
            TodoList(sections.completed, R.string.empty_completed, today, showDue = true, onOpen, onToggleComplete)
        Tab.UPCOMING -> UpcomingList(sections.upcoming, today, onOpen, onToggleComplete)
    }
}

@Composable
private fun TodoList(
    items: List<TodoEntity>,
    emptyRes: Int,
    today: LocalDate,
    showDue: Boolean,
    onOpen: (TodoEntity) -> Unit,
    onToggleComplete: (TodoEntity) -> Unit,
) {
    // Always a LazyColumn, empty state included: PullToRefreshBox pulls via the
    // nested scroll of a scrollable child, so a plain centered Box on empty
    // tabs would swallow nothing but also never trigger a refresh.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (items.isEmpty()) {
            item(key = "empty") {
                EmptyState(emptyRes, Modifier.fillParentMaxSize())
            }
        } else {
            items(items, key = { it.localId }) { todo ->
                TodoRow(
                    todo = todo,
                    today = today,
                    showDue = showDue,
                    onToggleComplete = { onToggleComplete(todo) },
                    onOpen = { onOpen(todo) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpcomingList(
    groups: List<DayGroup>,
    today: LocalDate,
    onOpen: (TodoEntity) -> Unit,
    onToggleComplete: (TodoEntity) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (groups.isEmpty()) {
            item(key = "empty") {
                EmptyState(R.string.empty_upcoming, Modifier.fillParentMaxSize())
            }
        } else {
            groups.forEach { group ->
                stickyHeader(key = group.date.toEpochDay()) {
                    Text(
                        text = Tabs.headerLabel(group.date, today, locale),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                    )
                }
                items(group.todos, key = { it.localId }) { todo ->
                    // The day header already carries the date — no per-row chip.
                    TodoRow(
                        todo = todo,
                        today = today,
                        showDue = false,
                        onToggleComplete = { onToggleComplete(todo) },
                        onOpen = { onOpen(todo) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(messageRes: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
