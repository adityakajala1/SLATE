package app.slate.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.slate.convert.ConvertScreen
import app.slate.core.DocKind
import app.slate.data.Settings
import app.slate.files.FilesScreen
import app.slate.search.SearchScreen
import app.slate.ui.theme.Slate

enum class MainTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME("Home", Icons.Outlined.Home, Icons.Filled.Home),
    FILES("Files", Icons.Outlined.Folder, Icons.Filled.Folder),
    SEARCH("Search", Icons.Outlined.Search, Icons.Filled.Search),
    CONVERT("Convert", Icons.Outlined.SwapHoriz, Icons.Filled.SwapHoriz),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
fun SlateRoot(settings: Settings) {
    var activeTab by remember { mutableStateOf(MainTab.HOME) }
    var filesInitialKindFilter by remember { mutableStateOf<DocKind?>(null) }
    var showFollowDialog by rememberSaveable { mutableStateOf(true) }
    var showDeveloperScreen by rememberSaveable { mutableStateOf(false) }

    if (showDeveloperScreen) {
        DeveloperScreen(onBack = { showDeveloperScreen = false })
        return
    }

    Scaffold(
        containerColor = Slate.colors.background,
        bottomBar = {
            Column {
                HorizontalDivider(color = Slate.colors.border)
                NavigationBar(
                    containerColor = Slate.colors.surface,
                    tonalElevation = 0.dp,
                ) {
                    MainTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { activeTab = tab },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Slate.colors.text,
                                selectedTextColor = Slate.colors.text,
                                indicatorColor = Slate.colors.surfaceAlt,
                                unselectedIconColor = Slate.colors.textSecondary,
                                unselectedTextColor = Slate.colors.textSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            Crossfade(
                targetState = activeTab,
                animationSpec = tween(durationMillis = 180),
                label = "main_tab_crossfade",
            ) { tab ->
                when (tab) {
                    MainTab.HOME -> HomeScreen(
                        onOpenFilesWithFilter = { kind ->
                            filesInitialKindFilter = kind
                            activeTab = MainTab.FILES
                        },
                        onNavigateSearch = { activeTab = MainTab.SEARCH },
                        onNavigateConvert = { activeTab = MainTab.CONVERT },
                        onNavigateSettings = { activeTab = MainTab.SETTINGS },
                        onOpenDeveloper = { showDeveloperScreen = true },
                    )
                    MainTab.FILES -> FilesScreen(
                        settings = settings,
                        initialKindFilter = filesInitialKindFilter,
                    )
                    MainTab.SEARCH -> SearchScreen()
                    MainTab.CONVERT -> ConvertScreen()
                    MainTab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onOpenDeveloper = { showDeveloperScreen = true },
                    )
                }
            }
        }
    }

    if (showFollowDialog) {
        FollowDeveloperDialog(
            onDismiss = { showFollowDialog = false },
            onOpenDeveloperPage = {
                showFollowDialog = false
                showDeveloperScreen = true
            },
        )
    }
}
