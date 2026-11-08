package com.funcodestudiocb.analyzer.ui.main

import androidx.annotation.StringRes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.funcodestudiocb.analyzer.ui.architecture.ArchitectureScreen
import com.funcodestudiocb.analyzer.ui.reports.ReportsScreen
import com.funcodestudiocb.analyzer.ui.reports.ReportsViewModel
import com.funcodestudiocb.analyzer.ui.architecture.ArchitectureViewModel
import com.funcodestudiocb.analyzer.ui.dependencies.DependenciesScreen
import com.funcodestudiocb.analyzer.ui.dependencies.DependenciesViewModel
import com.funcodestudiocb.analyzer.ui.diagnostics.DiagnosticsScreen
import com.funcodestudiocb.analyzer.ui.diagnostics.DiagnosticsViewModel
import com.funcodestudiocb.analyzer.ui.explorer.ExplorerScreen
import com.funcodestudiocb.analyzer.ui.explorer.ExplorerViewModel
import com.funcodestudiocb.analyzer.ui.security.SecurityScreen
import com.funcodestudiocb.analyzer.ui.security.SecurityViewModel
import com.funcodestudiocb.analyzer.ui.dashboard.DashboardActions
import com.funcodestudiocb.analyzer.ui.dashboard.DashboardScreen
import com.funcodestudiocb.analyzer.ui.settings.SettingsScreen
import com.funcodestudiocb.analyzer.ui.settings.SettingsViewModel
import com.funcodestudiocb.analyzer.ui.settings.VulnDbSettingsViewModel
import com.funcodestudiocb.analyzer.ui.containerViewModel
import kotlinx.coroutines.launch

/** Pantallas principales. */
enum class Destination(
    val route: String,
    @StringRes val title: Int,
    val icon: ImageVector,
) {
    Dashboard("panel", R.string.nav_dashboard, Icons.Filled.Home),
    NewAnalysis("nuevo", R.string.nav_new_analysis, Icons.Filled.AddCircle),
    Explorer("explorador", R.string.nav_explorer, Icons.AutoMirrored.Filled.List),
    Architecture("arquitectura", R.string.nav_architecture, Icons.Filled.Build),
    Security("seguridad", R.string.nav_security, Icons.Filled.Lock),
    Reports("informes", R.string.nav_reports, Icons.Filled.DateRange),
    Settings("ajustes", R.string.nav_settings, Icons.Filled.Settings),
}

const val ROUTE_DIAGNOSTICS = "ajustes/diagnostico"
const val ROUTE_ANALYSIS_DETAIL = "analisis/{id}"
const val ROUTE_EXPLORER_ANALYSIS = "explorador/{id}?archivo={archivo}"
const val ROUTE_DEPENDENCIES = "dependencias/{id}?archivo={archivo}"
const val ROUTE_ARCHITECTURE = "arquitectura/{id}?archivo={archivo}"
const val ROUTE_REPORT = "informes/{id}"

fun reportRoute(id: String): String = "informes/${android.net.Uri.encode(id)}"

/** Ruta de la arquitectura de un análisis, opcionalmente con el foco en un archivo. */
fun architectureRoute(id: String, file: String? = null): String =
    "arquitectura/${android.net.Uri.encode(id)}" + (file?.let { "?archivo=${android.net.Uri.encode(it)}" } ?: "")

/** Ruta de las dependencias de un análisis, opcionalmente solo las de un manifiesto o archivo de bloqueo. */
fun dependenciesRoute(id: String, file: String? = null): String =
    "dependencias/${android.net.Uri.encode(id)}" + (file?.let { "?archivo=${android.net.Uri.encode(it)}" } ?: "")

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
        ROUTE_DEPENDENCIES -> Destination.Security
        ROUTE_ARCHITECTURE -> Destination.Architecture
        ROUTE_REPORT -> Destination.Reports
        else -> Destination.Dashboard
    }
    val titleOverride = when (currentRoute) {
        ROUTE_ANALYSIS_DETAIL -> R.string.analysis_detail_title
        ROUTE_DEPENDENCIES -> R.string.dep_title
        else -> null
    }

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
                        val vulnDb = containerViewModel("bases-vulnerabilidades") { VulnDbSettingsViewModel(container) }
                        SettingsScreen(vm, vulnDb, onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) })
                    }
                    Destination.Dashboard -> {
                        fun goTo(d: Destination) = navController.navigate(d.route) {
                            popUpTo(Destination.Dashboard.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                        DashboardScreen(
                            container,
                            DashboardActions(
                                onNewAnalysis = { goTo(Destination.NewAnalysis) },
                                onOpenAnalysis = { id -> navController.navigate("analisis/$id") },
                                onOpenReport = { id -> navController.navigate(reportRoute(id)) },
                                onOpenSecurity = { goTo(Destination.Security) },
                                onOpenSettings = { goTo(Destination.Settings) },
                            ),
                        )
                    }
                    Destination.NewAnalysis -> {
                        val vm = containerViewModel { NewAnalysisViewModel(container) }
                        NewAnalysisScreen(
                            vm,
                            onOpenAnalysis = { id -> navController.navigate("analisis/$id") },
                            onOpenExplorer = { id -> navController.navigate(explorerRoute(id)) },
                            onOpenFile = { id, path -> navController.navigate(explorerRoute(id, path)) },
                            onOpenDependencies = { id -> navController.navigate(dependenciesRoute(id)) },
                            onOpenArchitecture = { id -> navController.navigate(architectureRoute(id)) },
                            onOpenReports = { id -> navController.navigate(reportRoute(id)) },
                        )
                    }
                    Destination.Explorer -> ExplorerScreen(
                        containerViewModel("explorador") { ExplorerViewModel(container, null) },
                        onOpenDependencies = { id, file -> navController.navigate(dependenciesRoute(id, file)) },
                        onOpenArchitecture = { id, file -> navController.navigate(architectureRoute(id, file)) },
                    )
                    Destination.Architecture -> ArchitectureScreen(
                        containerViewModel("arquitectura") { ArchitectureViewModel(container) },
                        onOpenFile = { id, path -> navController.navigate(explorerRoute(id, path)) },
                        onOpenDependencies = { id -> navController.navigate(dependenciesRoute(id)) },
                    )
                    Destination.Security -> SecurityScreen(
                        containerViewModel("seguridad") { SecurityViewModel(container) },
                        onOpenFile = { id, path -> navController.navigate(explorerRoute(id, path)) },
                        onOpenDependencies = { id -> navController.navigate(dependenciesRoute(id)) },
                    )
                    Destination.Reports -> ReportsScreen(containerViewModel("informes") { ReportsViewModel(container) })
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
                onOpenDependencies = { id -> navController.navigate(dependenciesRoute(id)) },
                onOpenArchitecture = { id -> navController.navigate(architectureRoute(id)) },
                onOpenReports = { id -> navController.navigate(reportRoute(id)) },
            )
        }
        composable(
            ROUTE_DEPENDENCIES,
            arguments = listOf(
                androidx.navigation.navArgument("archivo") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val file = entry.arguments?.getString("archivo")
            DependenciesScreen(
                containerViewModel(key = "dependencias-$id-${file.orEmpty()}") { DependenciesViewModel(container, id, file) },
                onOpenFile = { analysisId, path -> navController.navigate(explorerRoute(analysisId, path)) },
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
            ExplorerScreen(
                containerViewModel(key = "explorador-$id-${path.orEmpty()}") { ExplorerViewModel(container, id, path) },
                onOpenDependencies = { analysisId, file -> navController.navigate(dependenciesRoute(analysisId, file)) },
                onOpenArchitecture = { analysisId, file -> navController.navigate(architectureRoute(analysisId, file)) },
            )
        }
        composable(
            ROUTE_ARCHITECTURE,
            arguments = listOf(
                androidx.navigation.navArgument("archivo") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val file = entry.arguments?.getString("archivo")
            ArchitectureScreen(
                containerViewModel(key = "arquitectura-$id-${file.orEmpty()}") { ArchitectureViewModel(container, id, file) },
                onOpenFile = { analysisId, path -> navController.navigate(explorerRoute(analysisId, path)) },
                onOpenDependencies = { analysisId -> navController.navigate(dependenciesRoute(analysisId)) },
            )
        }
        composable(ROUTE_REPORT) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ReportsScreen(containerViewModel(key = "informes-$id") { ReportsViewModel(container, id) })
        }
        composable(ROUTE_DIAGNOSTICS) {
            val vm = containerViewModel { DiagnosticsViewModel(container) }
            DiagnosticsScreen(vm)
        }
    }
}
