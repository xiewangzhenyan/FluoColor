package com.muc.fluocolorquant.utils

import android.content.Context
import androidx.annotation.StringRes

/**
 * UI 文案封装，避免在 ViewModel 中直接依赖字符串解析逻辑。
 */
sealed class UiText {
    data class DynamicString(val value: String) : UiText()

    data class StringResource(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText()

    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> context.getString(resId, *args.toTypedArray())
        }
    }
}
