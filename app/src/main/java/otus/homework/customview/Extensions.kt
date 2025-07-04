package otus.homework.customview

import android.content.Context
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.roundToInt

fun Float.toPx(context: Context): Float = this * context.resources.displayMetrics.density

fun Float.toDp(context: Context): Float = this / context.resources.displayMetrics.density