package com.example.people.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.people.designsystem.components.EmptyState
import com.example.people.designsystem.components.ErrorState
import com.example.people.designsystem.components.PeopleIcons
import com.example.people.designsystem.components.UserRow
import com.example.people.designsystem.components.UserAvatar
import com.example.people.designsystem.components.UsersLoadingSkeleton
import com.example.people.designsystem.components.UsersTopAppBar
import com.example.people.designsystem.theme.PeopleSpacing
import com.example.people.presentation.model.UserItemUiState
import com.example.people.presentation.model.UsersUiState
import org.koin.compose.getKoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val AddFabSize = 64.dp
private val AddFabShape = RoundedCornerShape(20.dp)

private data class DeleteViewportSnapshot(
    val userId: Long,
    val expanded: Boolean,
    val wasFirstVisible: Boolean,
    val firstVisibleOffset: Int,
    val userScrolledAfterDelete: Boolean = false,
)

private data class UndoViewportState(
    val inProgressUserIds: Set<Long> = emptySet(),
    val revealUserIds: List<Long> = emptyList(),
)

private data class VisibleUndo(val transactionId: String, val userId: Long)

/** Lifecycle-owned on both platforms; recomposition and width changes do not fetch again. */
@Composable
fun UsersRoute(modifier: Modifier = Modifier) {
    val koin = getKoin()
    val usersViewModel = viewModel<UsersViewModel> { koin.get<UsersViewModel>() }
    val uiState by usersViewModel.uiState.collectAsState()
    val form by usersViewModel.addForm.collectAsState()
    val createdUserId by usersViewModel.createdUserId.collectAsState()
    val deleteState by usersViewModel.deleteState.collectAsState()
    val isOnline by usersViewModel.isOnline.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val undoActionScope = rememberCoroutineScope()
    var revealedCreatedUserId by remember { mutableStateOf<Long?>(null) }
    var undoViewport by remember { mutableStateOf(UndoViewportState()) }
    val undoTransactionId = deleteState.undoTransactionId
    val activeUndoUserId = deleteState.activeUndoUserId
    var visibleUndo by remember { mutableStateOf<VisibleUndo?>(null) }
    val undoTransition = remember { MutableTransitionState(false) }
    val pendingDeleteUserId = deleteState.selectedUser?.id
    LaunchedEffect(createdUserId, revealedCreatedUserId, undoTransactionId) {
        val id = createdUserId
        if (id != null && revealedCreatedUserId == id && undoTransactionId == null) {
            snapshotFlow { visibleUndo == null && undoTransition.isIdle }.first { it }
            snackbar.showSnackbar("User added", duration = SnackbarDuration.Short)
            usersViewModel.consumeCreatedUser(id)
        }
    }
    LaunchedEffect(undoTransactionId, activeUndoUserId) {
        val next = if (undoTransactionId != null && activeUndoUserId != null) {
            VisibleUndo(undoTransactionId, activeUndoUserId)
        } else null
        if (visibleUndo != next) {
            if (visibleUndo != null) {
                undoTransition.targetState = false
                snapshotFlow { undoTransition.isIdle && !undoTransition.currentState }.first { it }
            }
            visibleUndo = next
            if (next != null) {
                snackbar.currentSnackbarData?.dismiss()
                undoTransition.targetState = true
            }
        }
    }
    LaunchedEffect(deleteState.errorVersion) {
        deleteState.errorMessage?.let { message ->
            snapshotFlow { visibleUndo == null && undoTransition.isIdle }.first { it }
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
            usersViewModel.consumeDeleteError()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= PeopleSpacing.adaptiveWidthThreshold
        val phoneTransition = remember(expanded) { MutableTransitionState(false) }
        phoneTransition.targetState = !expanded && form.isVisible
        val phoneFormActive = !expanded &&
            (phoneTransition.currentState || phoneTransition.targetState || !phoneTransition.isIdle)
        // Hold the last visible state through the exit so the form does not clear mid-flight.
        var presentedForm by remember { mutableStateOf(form) }
        var wasFormVisible by remember { mutableStateOf(false) }
        var formSession by remember { mutableIntStateOf(0) }
        if (form.isVisible) {
            if (!wasFormVisible) formSession++
            presentedForm = form
        }
        wasFormVisible = form.isVisible
        val slideDistance = with(LocalDensity.current) { 32.dp.roundToPx() }
        UsersScreen(
            uiState = uiState,
            modifier = if (phoneFormActive) {
                // Keep the list mounted, but remove it from accessibility and touch while covered.
                Modifier.fillMaxSize().clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
            } else Modifier.fillMaxSize(),
            onRefresh = usersViewModel::refresh,
            onRetry = usersViewModel::retry,
            onAddUser = usersViewModel::openAddUser,
            isOnline = isOnline,
            onUserLongClick = usersViewModel::selectUserForDelete,
            createdUserId = createdUserId,
            canRevealCreatedUser = !phoneFormActive && !form.isVisible,
            revealCreatedUser = createdUserId == revealedCreatedUserId,
            onCreatedUserReady = { revealedCreatedUserId = it },
            pendingDeleteUserId = pendingDeleteUserId,
            activeUndoUserId = activeUndoUserId,
            acceptingUserIds = deleteState.acceptingUserIds,
            undoRevealIds = undoViewport.revealUserIds,
            undoInProgressUserIds = undoViewport.inProgressUserIds,
            onUndoRevealHandled = { id ->
                undoViewport = undoViewport.copy(revealUserIds = undoViewport.revealUserIds - id)
            },
            snackbarHost = {
                if (visibleUndo == null && undoTransition.isIdle) {
                    SnackbarHost(snackbar, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { data ->
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            data.visuals.actionLabel?.let { label ->
                                TextButton(onClick = data::performAction, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                                    Text(label, color = MaterialTheme.colorScheme.inversePrimary)
                                }
                            }
                        }
                    }
                    }
                }
                AnimatedVisibility(
                    visibleState = undoTransition,
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(220)) + fadeIn(tween(220)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(180)) + fadeOut(tween(180)),
                ) {
                    visibleUndo?.let { target ->
                        Surface(
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 8.dp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("User removed", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        if (undoTransactionId == target.transactionId &&
                                            target.userId !in undoViewport.inProgressUserIds) {
                                            undoViewport = undoViewport.copy(
                                                inProgressUserIds = undoViewport.inProgressUserIds + target.userId,
                                            )
                                            undoActionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                var restoredUserId: Long? = null
                                                try {
                                                    restoredUserId = usersViewModel.undoDelete(target.transactionId)
                                                } finally {
                                                    undoViewport = undoViewport.copy(
                                                        inProgressUserIds = undoViewport.inProgressUserIds - target.userId,
                                                        revealUserIds = if (restoredUserId != null && restoredUserId !in undoViewport.revealUserIds) {
                                                            undoViewport.revealUserIds + restoredUserId
                                                        } else undoViewport.revealUserIds,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    enabled = undoTransactionId == target.transactionId &&
                                        target.userId !in undoViewport.inProgressUserIds && undoTransition.targetState,
                                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                                ) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
                            }
                        }
                    }
                }
            },
        )
        if (expanded) {
            if (form.isVisible) AddUserModal(
                state = form,
                expanded = true,
                onNameChange = usersViewModel::editName,
                onEmailChange = usersViewModel::editEmail,
                onGenderChange = usersViewModel::selectGender,
                onBlur = usersViewModel::blurField,
                onSubmit = usersViewModel::submitAddUser,
                onDismiss = usersViewModel::dismissAddUser,
            )
        } else {
            AnimatedVisibility(
                visibleState = phoneTransition,
                enter = slideInVertically(
                    initialOffsetY = { slideDistance },
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                ) + fadeIn(animationSpec = tween(280)),
                exit = slideOutVertically(
                    targetOffsetY = { slideDistance },
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(260)),
            ) {
                key(formSession) {
                    AddUserModal(
                        state = presentedForm,
                        expanded = false,
                        onNameChange = usersViewModel::editName,
                        onEmailChange = usersViewModel::editEmail,
                        onGenderChange = usersViewModel::selectGender,
                        onBlur = usersViewModel::blurField,
                        onSubmit = usersViewModel::submitAddUser,
                        onDismiss = usersViewModel::dismissAddUser,
                        dismissEnabled = form.isVisible,
                    )
                }
            }
        }
        deleteState.selectedUser?.let { selected ->
            Dialog(
                onDismissRequest = usersViewModel::cancelDelete,
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        UserAvatar(user = selected)
                        Text(
                            "Remove ${selected.name}?",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            "They'll be removed from the directory. You can undo this for a few seconds.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = usersViewModel::cancelDelete, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = usersViewModel::confirmDelete,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.padding(start = 8.dp).sizeIn(minHeight = 48.dp),
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Adaptive Users screen foundation that supports single-column (< 600dp) and
 * two-column grid (>= 600dp) layouts.
 */
@Composable
fun UsersScreen(
    uiState: UsersUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit = onRefresh,
    modifier: Modifier = Modifier,
    onUserClick: ((UserItemUiState) -> Unit)? = null,
    onUserLongClick: ((UserItemUiState) -> Unit)? = null,
    onAddUser: () -> Unit = {},
    isOnline: Boolean = true,
    createdUserId: Long? = null,
    canRevealCreatedUser: Boolean = true,
    revealCreatedUser: Boolean = true,
    onCreatedUserReady: (Long) -> Unit = {},
    pendingDeleteUserId: Long? = null,
    activeUndoUserId: Long? = null,
    acceptingUserIds: Set<Long> = emptySet(),
    undoRevealIds: List<Long> = emptyList(),
    undoInProgressUserIds: Set<Long> = emptySet(),
    onUndoRevealHandled: (Long) -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
) {
    val pullToRefreshState = rememberPullToRefreshState()
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val snackbarLift by animateDpAsState(
        targetValue = if (snackbarHeight > 0) {
            with(LocalDensity.current) { snackbarHeight.toDp() } + PeopleSpacing.default
        } else 0.dp,
        animationSpec = tween(250),
        label = "FAB lift for Snackbar",
    )
    Box(modifier.fillMaxSize()) {
        Scaffold(
            topBar = { UsersTopAppBar(peopleCount = (uiState as? UsersUiState.Success)?.users?.size) },
            // The scroll viewport reaches the bottom; rows supply their own FAB and safe-area clearance.
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onRefresh,
                state = pullToRefreshState,
                enabled = uiState != UsersUiState.Loading && !uiState.isRefreshing,
                indicator = {
                    if (uiState != UsersUiState.Loading) {
                        PeopleRefreshIndicator(
                            state = pullToRefreshState,
                            isRefreshing = uiState.isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                },
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                when (uiState) {
                    is UsersUiState.Loading -> {
                        UsersLoadingSkeleton()
                    }

                    is UsersUiState.Empty -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { EmptyState(modifier = Modifier.fillParentMaxSize()) }
                        }
                    }

                    is UsersUiState.Error -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                ErrorState(
                                    modifier = Modifier.fillParentMaxSize(),
                                    title = uiState.title,
                                    message = uiState.message,
                                    onRetry = if (uiState.isRefreshing) null else onRetry,
                                )
                            }
                        }
                    }

                    is UsersUiState.Success -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            uiState.refreshError?.let { error ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = 16.dp,
                                            vertical = PeopleSpacing.small,
                                        )
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            horizontal = PeopleSpacing.default,
                                            vertical = PeopleSpacing.small,
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(PeopleSpacing.default),
                                    ) {
                                        Icon(
                                            imageVector = PeopleIcons.CloudOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = error.title,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = "${error.message} Showing saved users.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        TextButton(
                                            onClick = onRetry,
                                            enabled = !uiState.isRefreshing,
                                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                UsersContent(
                                    users = uiState.users,
                                    createdUserId = createdUserId,
                                    canRevealCreatedUser = canRevealCreatedUser,
                                    revealCreatedUser = revealCreatedUser,
                                    onCreatedUserReady = onCreatedUserReady,
                                    pendingDeleteUserId = pendingDeleteUserId,
                                    activeUndoUserId = activeUndoUserId,
                                    acceptingUserIds = acceptingUserIds,
                                    undoRevealIds = undoRevealIds,
                                    undoInProgressUserIds = undoInProgressUserIds,
                                    onUndoRevealHandled = onUndoRevealHandled,
                                    onUserClick = onUserClick,
                                    onUserLongClick = onUserLongClick
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(bottom = PeopleSpacing.default),
        ) {
            // Measure the Snackbar itself, excluding the platform inset and bottom margin.
            Box(Modifier.fillMaxWidth().onSizeChanged { snackbarHeight = it.height }) {
                snackbarHost()
            }
        }
        if (isOnline) FloatingActionButton(
            onClick = onAddUser,
            shape = AddFabShape,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(end = PeopleSpacing.default, bottom = PeopleSpacing.default)
                .offset(y = -snackbarLift)
                .shadow(
                    elevation = 3.dp,
                    shape = AddFabShape,
                    ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f),
                    spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f),
                )
                .size(AddFabSize)
                .semantics { contentDescription = "Add user" },
        ) {
            Icon(
                imageVector = PeopleIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSecondary,
            )
        }
    }
}

/** A compact version of the original prototype's tonal refresh badge. */
@Composable
private fun PeopleRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val distance = state.distanceFraction
    val progress = distance.coerceIn(0f, 1f)
    val spin = if (isRefreshing) {
        val transition = rememberInfiniteTransition(label = "Refresh icon spin")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "Refresh rotation",
        )
        rotation
    } else 0f
    val accessibility = if (isRefreshing || distance > 0f) {
        Modifier.semantics {
            contentDescription = when {
                isRefreshing -> "Refreshing users"
                progress >= 1f -> "Release to refresh users"
                else -> "Pull to refresh users"
            }
            progressBarRangeInfo = if (isRefreshing) {
                ProgressBarRangeInfo.Indeterminate
            } else {
                ProgressBarRangeInfo(progress, 0f..1f, 0)
            }
        }
    } else Modifier

    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier.size(36.dp).then(accessibility),
        maxDistance = 52.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        elevation = 1.dp,
    ) {
        Icon(
            imageVector = PeopleIcons.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp).graphicsLayer {
                alpha = if (isRefreshing) 1f else progress
                rotationZ = if (isRefreshing) 224f + spin else (distance * 224f).coerceAtMost(360f)
            },
        )
    }
}

/**
 * Adaptive list/grid layout rendering user rows based on available content width.
 */
@Composable
private fun UsersContent(
    users: List<UserItemUiState>,
    createdUserId: Long?,
    canRevealCreatedUser: Boolean,
    revealCreatedUser: Boolean,
    onCreatedUserReady: (Long) -> Unit,
    pendingDeleteUserId: Long? = null,
    activeUndoUserId: Long? = null,
    acceptingUserIds: Set<Long> = emptySet(),
    undoRevealIds: List<Long>,
    undoInProgressUserIds: Set<Long>,
    onUndoRevealHandled: (Long) -> Unit,
    onUserClick: ((UserItemUiState) -> Unit)?,
    onUserLongClick: ((UserItemUiState) -> Unit)?
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Keep the final row above the floating FAB while the viewport extends through the safe area.
        val bottomClearance = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() +
            AddFabSize + PeopleSpacing.default + PeopleSpacing.small
        val isExpandedWidth = maxWidth >= PeopleSpacing.adaptiveWidthThreshold
        val listState = rememberLazyListState()
        val gridState = rememberLazyGridState()
        var deleteViewports by remember { mutableStateOf<Map<Long, DeleteViewportSnapshot>>(emptyMap()) }
        val undoRevealId = undoRevealIds.firstOrNull()
        val eligibleViewportUserIds = remember(
            pendingDeleteUserId, activeUndoUserId, undoRevealIds, acceptingUserIds, undoInProgressUserIds,
        ) {
            acceptingUserIds + undoInProgressUserIds + undoRevealIds +
                setOfNotNull(pendingDeleteUserId, activeUndoUserId)
        }
        LaunchedEffect(eligibleViewportUserIds, deleteViewports.keys) {
            if (deleteViewports.isNotEmpty()) {
                val staleIds = deleteViewports.keys - eligibleViewportUserIds
                if (staleIds.isNotEmpty()) {
                    deleteViewports = deleteViewports - staleIds
                }
            }
        }
        val createdRowVisible = users.any { it.id == createdUserId }
        val displayedUsers = if (createdUserId != null && !revealCreatedUser) {
            users.filterNot { it.id == createdUserId }
        } else users
        val currentDisplayedUsers by rememberUpdatedState(displayedUsers)
        LaunchedEffect(deleteViewports.isNotEmpty(), isExpandedWidth) {
            if (deleteViewports.isNotEmpty()) {
                snapshotFlow { if (isExpandedWidth) gridState.isScrollInProgress else listState.isScrollInProgress }
                    .collect { scrolling ->
                        if (scrolling) {
                            deleteViewports = deleteViewports.mapValues { (_, snapshot) ->
                                snapshot.copy(userScrolledAfterDelete = true)
                            }
                        }
                    }
            }
        }
        val restoredIsDisplayed = undoRevealId != null && displayedUsers.any { it.id == undoRevealId }
        LaunchedEffect(undoRevealId, restoredIsDisplayed, isExpandedWidth) {
            val id = undoRevealId ?: return@LaunchedEffect
            if (!restoredIsDisplayed) return@LaunchedEffect
            val snapshot = deleteViewports[id]
            try {
                if (snapshot != null && snapshot.expanded == isExpandedWidth && !snapshot.userScrolledAfterDelete) {
                    // The Room-backed row is present in the displayed list. Wait for its lazy
                    // layout to measure the insertion before testing visibility or scrolling.
                    snapshotFlow {
                        val currentUsers = currentDisplayedUsers
                        val itemCount = if (isExpandedWidth) gridState.layoutInfo.totalItemsCount
                        else listState.layoutInfo.totalItemsCount
                        itemCount == currentUsers.size && currentUsers.any { it.id == id }
                    }.first { it }
                    if (deleteViewports[id]?.userScrolledAfterDelete != true) {
                        val isVisible = if (isExpandedWidth) {
                            gridState.layoutInfo.visibleItemsInfo.any { it.key == id }
                        } else {
                            listState.layoutInfo.visibleItemsInfo.any { it.key == id }
                        }
                        if (!isVisible) {
                            val index = currentDisplayedUsers.indexOfFirst { it.id == id }
                            if (index >= 0) {
                                val offset = if (snapshot.wasFirstVisible) snapshot.firstVisibleOffset else 0
                                if (isExpandedWidth) gridState.animateScrollToItem(index, offset)
                                else listState.animateScrollToItem(index, offset)
                            }
                        }
                    }
                }
            } finally {
                deleteViewports = deleteViewports - id
                onUndoRevealHandled(id)
            }
        }
        LaunchedEffect(createdUserId, createdRowVisible, isExpandedWidth, canRevealCreatedUser, revealCreatedUser) {
            if (createdUserId != null && createdRowVisible && canRevealCreatedUser && !revealCreatedUser) {
                if (isExpandedWidth) gridState.scrollToItem(0) else listState.scrollToItem(0)
                onCreatedUserReady(createdUserId)
                // Override lazy layout key anchoring on the insertion remeasure. Otherwise
                // the old first row stays pinned and the new index-zero row can be offscreen.
                if (isExpandedWidth) gridState.requestScrollToItem(0) else listState.requestScrollToItem(0)
            }
        }

        val onRowLongClick: (UserItemUiState) -> Unit = { user ->
            val visibleKeys = if (isExpandedWidth) {
                gridState.layoutInfo.visibleItemsInfo.map { it.key }
            } else {
                listState.layoutInfo.visibleItemsInfo.map { it.key }
            }
            if (user.id in visibleKeys) {
                val snapshot = DeleteViewportSnapshot(
                    userId = user.id,
                    expanded = isExpandedWidth,
                    wasFirstVisible = visibleKeys.firstOrNull() == user.id,
                    firstVisibleOffset = if (isExpandedWidth) gridState.firstVisibleItemScrollOffset
                    else listState.firstVisibleItemScrollOffset,
                )
                deleteViewports = deleteViewports + (user.id to snapshot)
            }
            onUserLongClick?.invoke(user)
        }

        if (isExpandedWidth) {
            // Two-column grid layout for wide screens / tablets (>= 600dp)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = PeopleSpacing.small,
                    bottom = bottomClearance,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    count = displayedUsers.size,
                    key = { index -> displayedUsers[index].id },
                ) { index ->
                    val user = displayedUsers[index]
                    UserRow(
                        user = user,
                        contentPadding = PaddingValues(
                            horizontal = 16.dp,
                            vertical = PeopleSpacing.medium,
                        ),
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(260),
                            placementSpec = tween(260, easing = FastOutSlowInEasing),
                            fadeOutSpec = tween(260),
                        ),
                        showDivider = index + 2 < displayedUsers.size,
                        onClick = onUserClick?.let { { it(user) } },
                        onLongClick = onUserLongClick?.let { { onRowLongClick(user) } }
                    )
                }
            }
        } else {
            // Single-column clean list layout for phone screens (< 600dp)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottomClearance),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = displayedUsers,
                    key = { _, user -> user.id }
                ) { index, user ->
                    UserRow(
                        user = user,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(260),
                            placementSpec = tween(260, easing = FastOutSlowInEasing),
                            fadeOutSpec = tween(260),
                        ),
                        showDivider = (index < displayedUsers.size - 1),
                        onClick = onUserClick?.let { { it(user) } },
                        onLongClick = onUserLongClick?.let { { onRowLongClick(user) } }
                    )
                }
            }
        }
    }
}
