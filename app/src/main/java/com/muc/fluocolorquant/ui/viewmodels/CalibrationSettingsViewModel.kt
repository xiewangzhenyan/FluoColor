package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.repository.CalibrationPolicyPreferences
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.CalibrationStrategy
import com.muc.fluocolorquant.domain.calibration.LowQualityCalibrationAction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 曲线拟合设置页面状态。
 *
 * 页面每次操作都会先更新内存状态再持久化，避免 DataStore 写入尚未返回时快速连续点击
 * 丢失前一个选项。领域对象自身负责最终范围校验。
 */
@HiltViewModel
class CalibrationSettingsViewModel @Inject constructor(
    private val repository: CalibrationPolicyPreferences
) : ViewModel() {

    private val _policy = MutableStateFlow(CalibrationPolicy.DEFAULT)
    val policy: StateFlow<CalibrationPolicy> = _policy.asStateFlow()

    init {
        viewModelScope.launch {
            repository.policyFlow.collect { stored -> _policy.value = stored }
        }
    }

    fun setStrategy(strategy: CalibrationStrategy) = update { copy(strategy = strategy) }

    fun toggleFunction(function: FittingFunction) = update {
        val next = allowedFunctions.toggleKeepingAtLeastOne(function)
        copy(allowedFunctions = next)
    }

    fun setSimplicityTolerance(value: Double) = update {
        copy(rSquaredSimplicityTolerance = value.coerceIn(0.0, 0.020))
    }

    fun setLowQualityRSquaredThreshold(value: Double) = update {
        copy(lowQualityRSquaredThreshold = value.coerceIn(0.0, 1.0))
    }

    fun changeMinimumFourParameterLevels(delta: Int) = update {
        copy(minimumFourParameterLevels = (minimumFourParameterLevels + delta).coerceIn(5, 20))
    }

    fun changeMinimumFiveParameterLevels(delta: Int) = update {
        copy(minimumFiveParameterLevels = (minimumFiveParameterLevels + delta).coerceIn(6, 20))
    }

    fun setLowQualityAction(action: LowQualityCalibrationAction) = update {
        copy(lowQualityAction = action)
    }

    fun toggleColorimetricFeature(feature: AnalysisPrimaryFeature) = update {
        copy(colorimetricFeatures = colorimetricFeatures.toggleKeepingAtLeastOne(feature))
    }

    fun toggleFluorescenceFeature(feature: AnalysisPrimaryFeature) = update {
        copy(fluorescenceFeatures = fluorescenceFeatures.toggleKeepingAtLeastOne(feature))
    }

    fun toggleWeighting(code: Int) = update {
        if (code !in 0..3) return@update this
        copy(enabledWeightingCodes = enabledWeightingCodes.toggleKeepingAtLeastOne(code))
    }

    fun setSaveToLibraryByDefault(enabled: Boolean) = update {
        copy(saveToLibraryByDefault = enabled)
    }

    fun reset() {
        _policy.value = CalibrationPolicy.DEFAULT
        viewModelScope.launch { repository.reset() }
    }

    /** 所有写入都从最新内存状态派生，保证快速多选操作不会互相覆盖。 */
    private fun update(transform: CalibrationPolicy.() -> CalibrationPolicy) {
        val next = runCatching { _policy.value.transform() }.getOrNull() ?: return
        _policy.value = next
        viewModelScope.launch { repository.save(next) }
    }

    private fun <T> Set<T>.toggleKeepingAtLeastOne(value: T): Set<T> {
        if (value in this && size == 1) return this
        return if (value in this) this - value else this + value
    }
}
