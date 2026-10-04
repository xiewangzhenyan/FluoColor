package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.ExperimentTemplateViewModel
import com.muc.fluocolorquant.ui.viewmodels.TemplateWithDetails
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import org.json.JSONObject
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.InteractivePlateGrid
import com.muc.fluocolorquant.data.enums.WellRoleType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.ChartPoint
import com.muc.fluocolorquant.data.enums.FittingFunction
import kotlin.math.pow
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.ui.theme.FluoRadius

/**
 * 实验模板管理页面
 * 显示所有实验模板列表，提供创建、编辑和删除功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentTemplateManagementScreen(
    navController: NavController,
    viewModel: ExperimentTemplateViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val toastManager = LocalToastManager.current
    
    val templatesWithDetails by viewModel.templatesWithDetails.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    // 删除确认对话框状态
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var templateToDelete by remember { mutableStateOf<ExperimentTemplate?>(null) }
    
    // 错误消息处理
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            toastManager.showToast(it, ToastType.ERROR)
            viewModel.clearError()
        }
    }

    
    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.library_template_title),
                onBack = { navController.navigateUp() }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navController.navigate(Screen.CreateExperimentTemplate.route) },
                icon = {
                    Icon(
                    imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.add_template)) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (templatesWithDetails.isEmpty()) {
                // 空状态
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Article,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_templates),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.add_template_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                // 模板列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
                ) {
                    items(
                        templatesWithDetails.filter {
                            it.template.status != TemplateLifecycleStatus.ARCHIVED.code
                        }
                    ) { templateWithDetails ->
                        TemplateItem(
                            templateWithDetails = templateWithDetails,
                            onEditClick = {
                                val template = templateWithDetails.template
                                navController.navigate(
                                    Screen.CreateExperimentTemplate.createRoute(template.id)
                                )
                            },
                            onDeleteClick = {
                                templateToDelete = templateWithDetails.template
                                showDeleteConfirmation = true
                            }
                        )
                    }
                }
            }
            
            // 删除确认对话框
            if (showDeleteConfirmation && templateToDelete != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmation = false },
                    title = {
                        Text(stringResource(R.string.delete_template_title))
                    },
                    text = { 
                        Text(
                            stringResource(
                                R.string.delete_template_confirmation,
                                templateToDelete?.templateName ?: ""
                            )
                        ) 
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                templateToDelete?.let(viewModel::deleteTemplate)
                                showDeleteConfirmation = false
                                templateToDelete = null
                            }
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmation = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
        }
    }
}

/**
 * 模板列表项组件
 */
@Composable
fun TemplateItem(
    templateWithDetails: TemplateWithDetails,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    viewModel: ExperimentTemplateViewModel = hiltViewModel()
) {
    val template = templateWithDetails.template
    val analyte = templateWithDetails.analyte
    val antigen = templateWithDetails.antigen
    val antibody = templateWithDetails.antibody
    val curveModel = templateWithDetails.curveModel
    val isVersionedTemplate = template.carrierProfileId != null || template.detectionMode != null
    
    // 展开/折叠状态
    var expanded by remember { mutableStateOf(false) }
    
    // 旋转动画
    val rotationState by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "rotation"
    )
    
    // 获取曲线函数表达式
    val curveExpression = remember(curveModel) {
        curveModel?.function?.identifier?.let { fnId ->
            FittingFunction.fromIdentifier(fnId)?.latexFormula ?: "y = f(x)"
        } ?: "y = f(x)"
    }
    
    // 解析默认布局JSON
    val defaultLayout = remember(template.defaultLayoutJson) {
        viewModel.parseDefaultLayout(template.defaultLayoutJson)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluoRadius.badge))
            .clickable { expanded = !expanded },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题和提示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = template.templateName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (expanded) 
                            stringResource(R.string.collapse) 
                        else 
                            stringResource(R.string.expand),
                        modifier = Modifier.rotate(rotationState)
                    )
                }
            }
            
            // 基本信息区域（始终显示）
            Spacer(modifier = Modifier.height(12.dp))
            
            if (isVersionedTemplate) {
                VersionedTemplateSummary(template = template)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Room 10 以前的单分析物模板继续按原字段展示，避免历史数据失去入口。
                    TemplateInfoRow(
                        icon = Icons.Default.Biotech,
                        label = stringResource(R.string.analyte),
                        value = analyte?.name ?: stringResource(R.string.unknown)
                    )

                    val reagentText = when {
                        antigen != null && antibody != null -> {
                            "${antigen.reagentName} / ${antibody.reagentName}"
                        }
                        antigen != null -> antigen.reagentName
                        antibody != null -> antibody.reagentName
                        else -> stringResource(R.string.none)
                    }
                    TemplateInfoRow(
                        icon = Icons.Default.Science,
                        label = stringResource(R.string.reagents),
                        value = reagentText
                    )

                    val curveModelText = curveModel?.let {
                        "${it.name} (${it.pixelType.displayName})"
                    } ?: stringResource(R.string.unknown)
                    TemplateInfoRow(
                        icon = Icons.Default.ShowChart,
                        label = stringResource(R.string.curve_model),
                        value = curveModelText
                    )

                    TemplateInfoRow(
                        icon = Icons.Default.Tune,
                        label = stringResource(R.string.range),
                        value = "${template.reliableRangeMin} - ${template.reliableRangeMax} " +
                            template.concentrationUnit
                    )
                }
            }

            // 新模板展开后展示版本科学元数据；旧模板仍保留曲线预览和 96 孔布局详情。
            AnimatedVisibility(
                visible = expanded && isVersionedTemplate,
                enter = fadeIn(animationSpec = tween(300)) +
                    expandVertically(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300)) +
                    shrinkVertically(animationSpec = tween(300))
            ) {
                VersionedTemplateDetails(template = template)
            }
            
            // 展开区域（详细信息）
            AnimatedVisibility(
                visible = expanded && !isVersionedTemplate,
                enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300)) + shrinkVertically(animationSpec = tween(300))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    // 分隔线
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    
                    // 曲线函数表达式
                    Text(
                        text = stringResource(R.string.curve_function),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // 使用FittingEngine.formatParametersToLatex()生成LaTeX表达式
                    val latexExpression = curveModel?.let { model ->
                        FittingEngine.formatParametersToLatex(model.function, model.parameters)
                    } ?: curveExpression
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .height(50.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LatexView(
                            latex = latexExpression,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    // 参数表
                    curveModel?.parameters?.let { params ->
                        if (params.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(
                                        text = stringResource(R.string.curve_parameters),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    
                                    Divider()
                                    
                                    params.forEach { (key, value) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = key,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = String.format("%.4f", value)
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                    
                    // 曲线预览
                    Text(
                        text = stringResource(R.string.curve_preview),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(8.dp)
                    ) {
                        if (curveModel != null) {
                            // 创建函数
                            val curveFunction: (Double) -> Double = { x ->
                                FittingEngine.calculate(curveModel.function, curveModel.parameters, x)
                            }
                            
                            CurveChart(
                                fittedCurve = curveFunction,
                                selectedFunction = curveModel.function,
                                parameters = curveModel.parameters,
                                xAxisLabel = stringResource(R.string.concentration),
                                yAxisLabel = curveModel.pixelType.displayName,
                                title = curveModel.name,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // 如果没有曲线模型，显示提示信息
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.no_curve_model),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    
                    // 默认孔板布局（如果存在）
                    if (template.defaultLayoutJson != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = stringResource(R.string.default_layout),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        if (defaultLayout.isNotEmpty()) {
                            InteractivePlateGrid(
                                layout = defaultLayout,
                                selectedRole = "",  // 空字符串表示只读模式
                                onWellClick = { },  // 空函数，因为我们只是展示不需要交互
                                rows = 8,  // 默认8行
                                columns = 12,  // 默认12列
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )
                            
                            // 添加图例
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                LegendItem(role = WellRoleType.SAMPLE.code, label = stringResource(R.string.legend_sample))
                                LegendItem(role = WellRoleType.STANDARD.code, label = stringResource(R.string.legend_standard))
                                LegendItem(role = WellRoleType.BLANK.code, label = stringResource(R.string.legend_blank))
                                LegendItem(role = WellRoleType.QUALITY_CONTROL.code, label = stringResource(R.string.legend_control))
                                LegendItem(role = WellRoleType.NONE.code, label = stringResource(R.string.legend_empty))
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.no_default_layout),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }
            
            // 操作区
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onEditClick) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.edit))
                }

                TextButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Room 11 版本化模板的紧凑摘要。
 *
 * 新模板的分析物、试剂和分析模型位于子表，不能继续读取已降级为兼容字段的单分析物主表；
 * 此处改为展示真正属于主档的检测模态、生命周期和版本，避免出现“未知分析物/0–0 范围”。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun VersionedTemplateSummary(template: ExperimentTemplate) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        TemplateSummaryPill(
            icon = Icons.Default.Biotech,
            text = stringResource(R.string.template_management_multi_analyte_value)
        )
        TemplateSummaryPill(
            icon = Icons.Default.Analytics,
            text = managementDetectionModeLabel(template.detectionMode)
        )
    }
}

/** 版本化模板的单行科学元数据标签，避免四行摘要把单张模板卡拉得过高。 */
@Composable
private fun TemplateSummaryPill(icon: ImageVector, text: String) {
    Surface(
        shape = RoundedCornerShape(FluoRadius.badge),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

/** 展开区展示可审计的模板契约，不再渲染与新分析模型无关的旧曲线预览。 */
@Composable
private fun VersionedTemplateDetails(template: ExperimentTemplate) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Divider(
            modifier = Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        TemplateInfoRow(
            icon = Icons.Default.ShowChart,
            label = stringResource(R.string.template_management_readout_label),
            value = managementReadoutLabel(template.readoutLayout)
        )
        TemplateInfoRow(
            icon = Icons.Default.Science,
            label = stringResource(R.string.template_wizard_protocol_title),
            value = managementProtocolLabel(template.inputProtocol)
        )
        template.purpose?.takeIf(String::isNotBlank)?.let { purpose ->
            TemplateInfoRow(
                icon = Icons.Default.Article,
                label = stringResource(R.string.template_wizard_purpose_label),
                value = purpose
            )
        }
    }
}

@Composable
private fun managementDetectionModeLabel(code: String?): String = when (
    DetectionModality.fromCode(code)
) {
    DetectionModality.COLORIMETRIC -> stringResource(R.string.analysis_model_mode_colorimetric)
    DetectionModality.FLUORESCENCE -> stringResource(R.string.analysis_model_mode_fluorescence)
    DetectionModality.SPECTRUM -> stringResource(R.string.analysis_model_mode_spectrum)
    null -> stringResource(R.string.unknown)
}

@Composable
private fun managementProtocolLabel(code: String?): String = when (InputProtocol.fromCode(code)) {
    InputProtocol.ENDPOINT_ONLY -> stringResource(R.string.analysis_model_protocol_endpoint)
    InputProtocol.SINGLE_SPECTRUM_ANALYSIS -> {
        stringResource(R.string.analysis_model_protocol_single_spectrum)
    }
    InputProtocol.LSPR_PAIRED_QUANTIFICATION -> {
        stringResource(R.string.analysis_model_protocol_lspr_pair)
    }
    null -> stringResource(R.string.unknown)
}

@Composable
private fun managementReadoutLabel(code: String?): String = when (ReadoutLayout.fromCode(code)) {
    ReadoutLayout.GRID_SITES -> stringResource(R.string.template_wizard_readout_grid)
    ReadoutLayout.SPECTRAL_TRACKS -> stringResource(R.string.template_wizard_readout_tracks)
    ReadoutLayout.SINGLE_REGION -> {
        stringResource(R.string.template_wizard_readout_single_region)
    }
    ReadoutLayout.PER_SITE_SPECTRUM -> {
        stringResource(R.string.template_wizard_readout_per_site_spectrum)
    }
    null -> stringResource(R.string.unknown)
}

/**
 * 模板信息行组件
 */
@Composable
fun TemplateInfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = stringResource(R.string.label_with_colon, label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 图例项组件
 */
@Composable
private fun LegendItem(
    role: String,
    label: String
) {
    val roleType = WellRoleType.fromCode(role)
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(roleType.color)
                .border(0.5.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
        )
        
        Spacer(modifier = Modifier.width(4.dp))
        
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp)
        )
    }
}
