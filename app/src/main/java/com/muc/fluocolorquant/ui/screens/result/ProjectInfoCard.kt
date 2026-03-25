package com.muc.fluocolorquant.ui.screens.result

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDate(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)

/**
 * 项目信息卡片
 * 显示项目基础信息，并在右侧展示实拍缩略图。
 */
@Composable
fun ProjectInfoCard(
    project: Project,
    concentrationUnit: String,
    analytesList: List<Analyte> = emptyList()
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(
            modifier = Modifier.padding(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(end = if (project.imageUri.isNotBlank()) 128.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                ResultInfoLabelValueText(
                    label = stringResource(R.string.result_project_detection_mode_label),
                    value = when (project.detectionMode) {
                        "FLUORESCENCE" -> stringResource(R.string.fluorescence_detection_mode)
                        "COLORIMETRIC" -> stringResource(R.string.colorimetric_detection_mode)
                        else -> project.detectionMode
                    },
                    color = colorScheme.onSurfaceVariant
                )

                ResultInfoLabelValueText(
                    label = stringResource(R.string.result_project_analysis_method_label),
                    value = when (project.analysisMethod) {
                        "DL_MODEL" -> stringResource(R.string.deep_learning_analysis)
                        "CURVE_FIT" -> stringResource(R.string.curve_fitting_analysis)
                        else -> project.analysisMethod
                    },
                    color = colorScheme.onSurfaceVariant
                )

                if (analytesList.isNotEmpty()) {
                    val analyteNames = analytesList.joinToString(", ") { it.name }
                    ResultInfoLabelValueText(
                        label = stringResource(R.string.result_project_analytes_label),
                        value = analyteNames,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                }

                ResultInfoLabelValueText(
                    label = stringResource(R.string.result_project_creation_time_label),
                    value = formatDate(project.createTime),
                    color = colorScheme.onSurfaceVariant
                )

                ResultInfoLabelValueText(
                    label = stringResource(R.string.result_project_concentration_unit_label),
                    value = concentrationUnit,
                    color = colorScheme.onSurfaceVariant
                )
            }

            if (project.imageUri.isNotBlank()) {
                ProjectCaptureThumbnail(
                    imageUri = project.imageUri,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(112.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultInfoLabelValueText(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        modifier = modifier,
        style = style,
        color = color,
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(label)
            }
            append(value)
        }
    )
}

@Composable
private fun ProjectCaptureThumbnail(
    imageUri: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageModel = remember(imageUri) { resolveProjectImageModel(imageUri) }
    var showPreview by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .aspectRatio(0.92f)
            .clickable { showPreview = true },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageModel)
                    .crossfade(true)
                    .placeholder(R.drawable.placeholder_image)
                    .error(R.drawable.placeholder_image)
                    .build(),
                contentDescription = stringResource(R.string.result_project_capture_thumbnail_desc),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopStart)
                    .background(
                        color = Color.Black.copy(alpha = 0.58f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.result_project_capture_thumbnail_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.BottomEnd)
                    .size(30.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.48f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = stringResource(R.string.result_project_capture_preview_action),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (showPreview) {
        ProjectCapturePreviewDialog(
            imageModel = imageModel,
            onDismiss = { showPreview = false }
        )
    }
}

@Composable
private fun ProjectCapturePreviewDialog(
    imageModel: Any,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageModel)
                        .crossfade(true)
                        .placeholder(R.drawable.placeholder_image)
                        .error(R.drawable.placeholder_image)
                        .build(),
                    contentDescription = stringResource(R.string.result_project_capture_thumbnail_desc),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit
                )

                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.TopStart)
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = stringResource(R.string.result_project_capture_thumbnail_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.TopEnd)
                        .background(
                            color = Color.Black.copy(alpha = 0.46f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.result_project_capture_close_action),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

private fun resolveProjectImageModel(imageUri: String): Any {
    return if (imageUri.startsWith("content://") || imageUri.startsWith("file://")) {
        Uri.parse(imageUri)
    } else {
        File(imageUri)
    }
}
