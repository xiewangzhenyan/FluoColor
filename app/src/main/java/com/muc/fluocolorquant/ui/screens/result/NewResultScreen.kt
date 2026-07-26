@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
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
    var showExportPanel by remember { mutableStateOf(false) }
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(runId, projectId) {
        when {
            !runId.isNullOrEmpty() -> viewModel.loadResultsByRunId(runId)
            !projectId.isNullOrEmpty() -> viewModel.loadResultsByProjectId(projectId)
            else -> viewModel.loadDefaultOrMostRecentResults()
        }
    }

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.detection_results),
                onBack = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                actions = {
                    IconButton(onClick = { showExportPanel = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.export),
                            contentDescription = stringResource(R.string.export),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
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
                                // 科研结果优先：分析物切换后立即看到热力图和浓度，不再先穿过
                                // 追溯信息与方案说明。辅助信息保留在结果之后并默认折叠。
                                ResultsDisplaySection(currentAnalyteDetails)
                                Spacer(modifier = Modifier.height(12.dp))

                                ValidationCard(
                                    analyteId = currentAnalyteId,
                                    analyteDetails = currentAnalyteDetails,
                                    viewModel = viewModel
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                ResultTraceabilityCard(currentAnalyteDetails)
                                Spacer(modifier = Modifier.height(10.dp))

                                AnalysisPlanCard(currentAnalyteDetails)
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
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
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
                        val statusBarHeight = ViewCompat.getRootWindowInsets(rootView)
                            ?.getInsets(WindowInsetsCompat.Type.statusBars())
                            ?.top
                            ?: 0
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(6.dp)
        ) {
            TabRow(
                selectedTabIndex = analytesList.indexOfFirst { it.id == selectedAnalyteId }.takeIf { it >= 0 } ?: 0,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                divider = {},
                indicator = {}
            ) {
                analytesList.forEach { analyte ->
                    val selected = analyte.id == selectedAnalyteId
                    Tab(
                        selected = selected,
                        onClick = { onAnalyteSelected(analyte.id) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            ),
                        text = {
                            Text(
                                text = analyte.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
