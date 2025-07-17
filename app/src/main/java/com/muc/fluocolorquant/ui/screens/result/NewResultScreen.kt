@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.ExportViewModel
import com.muc.fluocolorquant.ui.viewmodels.ResultViewModel
import kotlinx.coroutines.launch

/**
 * 新版结果展示页面
 * 支持按分析物组织的结果展示
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewResultScreen(
    navController: NavController,
    runId: String? = null,
    projectId: String? = null,
    viewModel: ResultViewModel = hiltViewModel(),
    exportViewModel: ExportViewModel = hiltViewModel()
) {
    val resultState by viewModel.resultState.collectAsState()
    val analyteResultsMap by viewModel.analyteResultsMap.collectAsState()
    val analytesList by viewModel.analytesList.collectAsState()
    val selectedAnalyteId by viewModel.selectedAnalyteId.collectAsState()
    val concentrationUnit by viewModel.concentrationUnit.collectAsState()
    val toastManager = LocalToastManager.current
    var showExportPanel by remember { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(runId, projectId) {
        when {
            !runId.isNullOrEmpty() -> viewModel.loadResultsByRunId(runId)
            !projectId.isNullOrEmpty() -> viewModel.loadResultsByProjectId(projectId)
            else -> viewModel.loadDefaultOrMostRecentResults()
        }
    }

    val project = (resultState as? ResultViewModel.ResultState.Success)?.project
    val isStandardCurveFitting = project?.analysisMethod == "CURVE_FIT"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.detection_results)) },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showExportPanel = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.export),
                            contentDescription = stringResource(R.string.export),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showExportPanel = true }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.export)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.export))
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            when (val state = resultState) {
                is ResultViewModel.ResultState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.loading_result_data))
                    }
                }

                is ResultViewModel.ResultState.Success -> {
                    val projectData = state.project

                    if (analytesList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.padding(bottom = 16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.no_analytes_found),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            androidx.compose.material3.Button(
                                onClick = { navController.navigate(Screen.Home.route) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.return_to_home))
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            ProjectInfoCard(
                                project = projectData,
                                concentrationUnit = concentrationUnit,
                                analytesList = analytesList
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            AnalyteTabRow(
                                analytesList = analytesList,
                                selectedAnalyteId = selectedAnalyteId ?: "",
                                onAnalyteSelected = { analyteId ->
                                    coroutineScope.launch {
                                        viewModel.selectAnalyte(analyteId)
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            val currentAnalyteId = selectedAnalyteId ?: ""
                            val currentAnalyteDetails = analyteResultsMap[currentAnalyteId]
                            if (currentAnalyteDetails != null) {
                                AnalysisPlanCard(currentAnalyteDetails)
                                Spacer(modifier = Modifier.height(16.dp))

                                ResultsDisplaySection(currentAnalyteDetails)
                                Spacer(modifier = Modifier.height(16.dp))

                                ValidationCard(
                                    analyteId = currentAnalyteId,
                                    analyteDetails = currentAnalyteDetails,
                                    viewModel = viewModel
                                )
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = stringResource(R.string.no_data_for_analyte),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            androidx.compose.material3.Button(
                                onClick = { navController.navigate(Screen.Home.route) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.return_to_home))
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }

                is ResultViewModel.ResultState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (showExportPanel) {
        (resultState as? ResultViewModel.ResultState.Success)?.let { successState ->
            ExportBottomPanel(
                isVisible = true,
                onDismiss = { showExportPanel = false },
                project = successState.project,
                wellResults = successState.wellResults,
                detectionRun = successState.detectionRun,
                captureScreenshot = {
                    try {
                        val rootView = view.rootView
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            rootView.width,
                            rootView.height,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        rootView.draw(canvas)
                        val statusBarHeight = getStatusBarHeight(context)
                        android.graphics.Bitmap.createBitmap(
                            bitmap,
                            0,
                            statusBarHeight,
                            bitmap.width,
                            bitmap.height - statusBarHeight
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("ResultScreen", "Screenshot failed", e)
                        null
                    }
                },
                projectAnalyteJoinRepository = viewModel.projectAnalyteJoinRepository,
                analyteResultDetails = analyteResultsMap,
                exportViewModel = exportViewModel
            )
        }
    }
}

@Composable
fun AnalyteTabRow(
    analytesList: List<com.muc.fluocolorquant.data.model.Analyte>,
    selectedAnalyteId: String,
    onAnalyteSelected: (String) -> Unit
) {
    if (analytesList.isNotEmpty()) {
        TabRow(
            selectedTabIndex = analytesList.indexOfFirst { it.id == selectedAnalyteId }.takeIf { it >= 0 } ?: 0,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            analytesList.forEach { analyte ->
                Tab(
                    selected = analyte.id == selectedAnalyteId,
                    onClick = { onAnalyteSelected(analyte.id) },
                    text = {
                        Text(
                            text = analyte.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (analyte.id == selectedAnalyteId) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getStatusBarHeight(context: android.content.Context): Int {
    val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
}