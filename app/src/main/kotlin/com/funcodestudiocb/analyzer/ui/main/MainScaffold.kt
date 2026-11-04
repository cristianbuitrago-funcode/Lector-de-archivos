package com.funcodestudiocb.analyzer.ui.main

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.ui.components.BrandHeader
import com.funcodestudiocb.analyzer.ui.analysis.AnalysisDetailScreen
import com.funcodestudiocb.analyzer.ui.analysis.AnalysisDetailViewModel
import com.funcodestudiocb.analyzer.ui.analysis.NewAnalysisScreen
import com.funcodestudiocb.analyzer.ui.analysis.NewAnalysisViewModel
import com.funcodestudiocb.analyzer.ui.diagnostics.DiagnosticsScreen
import com.funcodestudiocb.analyzer.ui.diagnostics.DiagnosticsViewModel
import com.funcodestudiocb.analyzer.ui.explorer.ExplorerScreen
import com.funcodestudiocb.analyzer.ui.explorer.ExplorerViewModel
import com.funcodestudiocb.analyzer.ui.settings.SettingsScreen
import com.funcodestudiocb.analyzer.ui.settings.SettingsViewModel
import com.funcodestudiocb.analyzer.ui.containerViewModel
import kotlinx.coroutines.launch

/** Pantallas principales. [phase] indica en qué fase se implementa cada una. */
enum class Destination(
    val route: String,
    @StringRes val title: Int,
    val icon: ImageVector,
    val phase: Int,
    @ArrayRes val planned: Int?,
) {
    Dashboard("panel", R.string.nav_dashboard, Icons.Filled.Home, 10, R.array.placeholder_dashboard),
    NewAnalysis("nuevo", R.string.nav_new_analysis, Icons.Filled.AddCircle, 2, R.array.placeholder_new_analysis),
    Explorer("explorador", R.string.nav_explorer, Icons.AutoMirrored.Filled.List, 3, R.array.placeholder_explorer),
    Architecture("arquitectura", R.string.nav_architecture, Icons.Filled.Build, 8, R.array.placeholder_architecture),
    Security("seguridad", R.string.nav_security, Icons.Filled.Lock, 6, R.array.placeholder_security),
    Reports("informes", R.string.nav_reports, Icons.Filled.DateRange, 9, R.array.placeholder_reports),
    Settings("ajustes", R.string.nav_settings, Icons.Filled.Settings, 1, null),
}

const val ROUTE_DIAGNOSTICS = "ajustes/diagnostico"
const val ROUTE_ANALYSIS_DETAIL = "analisis/{id}"
const val ROUTE_EXPLORER_ANALYSIS = "explorador/{id}?archivo={archivo}"

/** Ruta del Explorador de un análisis, abriendo opcionalmente la ficha de un archivo. */
fun explorerRoute(id: String, path: String? = null): String =
    "explorador/${android.net.Uri.encode(id)}" + (path?.let { "?archivo=${android.net.Uri.encode(it)}" } ?: "")

private enum class NavLayout { Drawer, Rail, Permanent }

@Composable
fun MainScaffold(container: AppContainer, widthDp: Int) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Destination.Dashboard.route
    val current = Destination.entries.firstOrNull { it.route == currentRoute } ?: when (currentRoute) {
        ROUTE_DIAGNOSTICS -> Destination.Settings
        ROUTE_ANALYSIS_DETAIL -> Destination.NewAnalysis
        ROUTE_EXPLORER_ANALYSIS -> Destination.Explorer
        else -> Destination.Dashboard
    }
    val titleOverride = if (currentRoute == ROUTE_ANALYSIS_DETAIL) R.string.analysis_detail_title else null

    // Otra app abrió o compartió un comprimido con Funcode: se va a "Nuevo análisis".
    val openRequested by container.coordinator.openRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openRequested) {
        if (openRequested) {
            navController.navigate(Destination.NewAnalysis.route) { launchSingleTop = true }
            container.coordinator.consumeOpenRequest()
        }
    }
    val layout = when {
        widthDp >= 1000 -> NavLayout.Permanent
        widthDp >= 600 -> NavLayout.Rail
        else -> NavLayout.Drawer
    }
    val onLock = { container.session.lock() }

    fun navigate(destination: Destination) {
        navController.navigate(destination.route) {
            popUpTo(Destination.Dashboard.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val content: @Composable (Modifier, (@Composable () -> Unit)?) -> Unit = { modifier, navigationIcon ->
        Scaffold(
            modifier = modifier,
            topBar = { MainTopBar(titleOverride ?: current.title, navigationIcon, onLock) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            MainNavHost(navController, container, Modifier.padding(padding))
        }
    }

    when (layout) {
        NavLayout.Drawer -> {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        DrawerItems(current) {
                            scope.launch { drawerState.close() }
                            navigate(it)
                        }
                    }
                },
            ) {
                content(Modifier) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.testTag("open_menu")) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.nav_open_menu))
                    }
                }
            }
        }
        NavLayout.Rail -> Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Spacer(Modifier.height(12.dp))
                Destination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = destination == current,
                        onClick = { navigate(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.title), maxLines = 1) },
                        modifier = Modifier.testTag("nav_${destination.route}"),
                    )
                }
            }
            content(Modifier.weight(1f), null)
        }
        NavLayout.Permanent -> PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    Modifier.width(280.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) { DrawerItems(current, ::navigate) }
            },
        ) { content(Modifier.fillMaxHeight(), null) }
    }
}

@Composable
private fun DrawerItems(current: Destination, onSelect: (Destination) -> Unit) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        BrandHeader(Modifier.padding(12.dp))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Destination.entries.forEach { destination ->
            NavigationDrawerItem(
                label = { Text(stringResource(destination.title)) },
                icon = { Icon(destination.icon, contentDescription = null) },
                selected = destination == current,
                onClick = { onSelect(destination) },
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .testTag("nav_${destination.route}"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(@StringRes title: Int, navigationIcon: (@Composable () -> Unit)?, onLock: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(title), modifier = Modifier.testTag("screen_title")) },
        navigationIcon = { navigationIcon?.invoke() },
        actions = {
            IconButton(onClick = onLock, modifier = Modifier.testTag("lock_now")) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = stringResource(R.string.nav_lock))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun MainNavHost(navController: NavHostController, container: AppContainer, modifier: Modifier) {
    NavHost(
        navController = navController,
        startDestination = Destination.Dashboard.route,
        modifier = modifier,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        Destination.entries.forEach { destination ->
            composable(destination.route) {
                when (destination) {
                    Destination.Settings -> {
                        val vm = containerViewModel { SettingsViewModel(container) }
                        SettingsScreen(vm, onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) })
                    }
                    Destination.Dashboard -> Box { DashboardScreen(container) }
                    Destination.NewAnalysis -> {
                        val vm = containerViewModel { NewAnalysisViewModel(container) }
                        NewAnalysisScreen(
                            vm,
                            onOpenAnalysis = { id -> navController.navigate("analisis/$id") },
                            onOpenExplorer = { id -> navController.navigate(explorerRoute(id)) },
                            onOpenFile = { id, path -> navController.navigate(explorerRoute(id, path)) },
                        )
                    }
                    Destination.Explorer -> ExplorerScreen(containerViewModel("explorador") { ExplorerViewModel(container, null) })
                    else -> PlaceholderScreen(destination)
                }
            }
        }
        composable(ROUTE_ANALYSIS_DETAIL) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm = containerViewModel(key = "detalle-$id") { AnalysisDetailViewModel(container, id) }
            AnalysisDetailScreen(
                vm,
                onDeleted = { navController.popBackStack() },
                onOpenExplorer = { navController.navigate(explorerRoute(it)) },
                onOpenFile = { id, path -> navController.navigate(explorerRoute(id, path)) },
            )
        }
        composable(
            ROUTE_EXPLORER_ANALYSIS,
            arguments = listOf(
                androidx.navigation.navArgument("archivo") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val path = entry.arguments?.getString("archivo")
            ExplorerScreen(containerViewModel(key = "explorador-$id-${path.orEmpty()}") { ExplorerViewModel(container, id, path) })
        }
        composable(ROUTE_DIAGNOSTICS) {
            val vm = containerViewModel { DiagnosticsViewModel(container) }
            DiagnosticsScreen(vm)
        }
    }
}
