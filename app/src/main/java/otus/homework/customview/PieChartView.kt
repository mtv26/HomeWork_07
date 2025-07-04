package otus.homework.customview

import android.R.raw
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = -1,
    defStyleRes: Int = -1): View(context, attrs, defStyleAttr, defStyleRes) {

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val padding get() = min(width, height).let { it / 8f }

    private val centerClipRadius get() = (min(width, height) / 3f) - padding

    private var items: List<Item> = emptyList()

    private var listener: CategoryClickListener? = null

    private val rad = PI / 180f

    private var categories: List<Category> = emptyList()
    private var angles: List<Angle> = emptyList()
    private val animatedAlpha = mutableMapOf<Int, Int>()
    private val animations = mutableMapOf<Int, Job>()
    private var total = -1
    private var totalLabel = ""
    private val percentLabel = "%d%%"
    private val percentPlaceholder = "00%"
    private var source: Int = -1
    private var initSource: Job? = null

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.PieChartView, defStyleAttr, 0)
        source = typedArray.getResourceId(R.styleable.PieChartView_cv_source, -1)
        if (source > -1) {
            obtainSource()
        }
        try {
            typedArray.recycle()
        } catch (e: Exception) {}
    }

    private fun obtainSource() {
        val prevJob = initSource
        initSource = viewScope.launch(Dispatchers.IO) {
            prevJob?.cancelAndJoin()
            try {
                val raw = BufferedReader(InputStreamReader(resources.openRawResource(source), "UTF-8")).use {
                    it.readText()
                }
                val items = Json.decodeFromString<List<Item>>(raw)
                withContext(Dispatchers.Main) {
                    setItems(items)
                    source = -1
                    requestLayout()
                }
            } catch (e: Exception) {}
        }
    }

    @Serializable
    @Parcelize
    data class Item(
        val id: Int,
        val name: String,
        val amount: Int,
        val category: String,
        val time: Long
    ): Parcelable

    data class Category(
        val name: String,
        val amount: Int,
    )

    @Parcelize
    private data class SavedState(val items: List<Item>, val superState: Parcelable?): Parcelable

    private data class Angle(val start: Float, val current: Float, val labelPoint: PointF, val percent: Int)

    interface CategoryClickListener {
        fun onCLick(category: Category)
    }

    fun setOnCategoryClickListener(listener: CategoryClickListener?) {
        this.listener = listener
    }

    fun setItems(items: List<Item>) {
        this.items = items
        calculateCategories()
        invalidate()
    }

    private val colors = listOf<Int>(
        Color.parseColor("#F44336").toColor().toArgb(),
        Color.parseColor("#E91E63").toColor().toArgb(),
        Color.parseColor("#9C27B0").toColor().toArgb(),
        Color.parseColor("#673AB7").toColor().toArgb(),
        Color.parseColor("#3F51B5").toColor().toArgb(),
        Color.parseColor("#2196F3").toColor().toArgb(),
        Color.parseColor("#03A9F4").toColor().toArgb(),
        Color.parseColor("#00BCD4").toColor().toArgb(),
        Color.parseColor("#009688").toColor().toArgb(),
        Color.parseColor("#4CAF50").toColor().toArgb(),
        Color.parseColor("#8BC34A").toColor().toArgb(),
        Color.parseColor("#CDDC39").toColor().toArgb(),
        Color.parseColor("#FFEB3B").toColor().toArgb(),
        Color.parseColor("#FFC107").toColor().toArgb(),
        Color.parseColor("#FF9800").toColor().toArgb(),
        Color.parseColor("#FF5722").toColor().toArgb()
    )

    private val bitRect = RectF()
    private val bitPaint = Paint().apply {
        isAntiAlias = true
        maskFilter = BlurMaskFilter(0.5f, BlurMaskFilter.Blur.NORMAL)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
    }
    private val percentLabelPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }

    private val totalPaint = Paint()
    private val centerClipPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requiredWidth = 300
        val requiredHeight = 300

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = if (widthMode == MeasureSpec.EXACTLY) {
            widthSize
        } else if (widthMode == MeasureSpec.AT_MOST) {
            min(requiredWidth, widthSize)
        } else {
            requiredWidth
        }

        val height = if (heightMode == MeasureSpec.EXACTLY) {
            heightSize
        } else if (heightMode == MeasureSpec.AT_MOST) {
            min(requiredHeight, heightSize)
        } else {
            requiredHeight
        }

        setMeasuredDimension(width, height)
        calculateBitRect(width, height)
        calculateAngles(width, height)
    }

    private fun drawTotal(canvas: Canvas) {
        if (total == -1) return
        totalPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        totalPaint.flags = Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
        totalPaint.color = Color.BLACK
        val textSize = centerClipRadius / 2.2f
        totalPaint.textSize = textSize
        totalPaint.textAlign = Paint.Align.LEFT
        val metric = totalPaint.fontMetrics
        val textHeight = ceil(metric.descent - metric.ascent)
        val textWidth = totalPaint.measureText(totalLabel)
        val x = bitRect.centerX() - (textWidth / 2)
        val y = bitRect.centerY() - ((textHeight / 2) + (metric.descent - textHeight))
        canvas.drawText(totalLabel, x, y, totalPaint)
    }

    private fun drawBit(canvas: Canvas, bit: Int) {
        if (angles.isEmpty()) return
        val color = colors[bit % colors.lastIndex]
        val angle = angles[bit]
        bitPaint.color = color
        animatedAlpha[bit]?.let { alpha ->
            bitPaint.alpha = alpha
        }
        canvas.drawArc(bitRect, angle.start, angle.current, true, bitPaint)
    }

    private fun drawPercentLabel(canvas: Canvas, bit: Int) {
        if (angles.isEmpty()) return
        val angle = angles[bit]
        val point = angle.labelPoint
        canvas.drawText(percentLabel.format(angle.percent), point.x, point.y, percentLabelPaint)
        canvas.drawCircle(point.x, point.y, 8f, percentLabelPaint)
    }

    private fun isCurrentPoint(angle: Angle, x: Float, y: Float): Boolean {
        val checkRadius = hypot(x - bitRect.centerX(), y - bitRect.centerY()).let {
            it > centerClipRadius && it < (bitRect.width() / 2f)
        }
        val checkAngle = if (x >= bitRect.centerX()) {
            (atan((y - bitRect.centerY()) / (x - bitRect.centerX())) * (180 / PI)).let {
                it > angle.start && it < angle.start + angle.current
            }
        } else {
            (atan2(y - bitRect.centerY(), x - bitRect.centerX()) * (180 / PI)).let {
                val a = if (it.sign < 0) (360 + it) else it
                a > angle.start && a < angle.start + angle.current
            }
        }
        return checkRadius && checkAngle
    }

    private fun calculateBitRect(width: Int, height: Int) {
        val min = min(width, height)
        val max = max(width, height)
        val padding = min / 8f
        val offset = (max - min) / 2
        val left = if (width <= height) 0f else offset
        val top = if (width >= height) 0f else offset
        bitRect.set(
            padding + left.toFloat(),
            padding + top.toFloat(),
            width.toFloat() - left.toFloat() - padding,
            height.toFloat() - top.toFloat() - padding
        )
    }

    private fun calculateCategories() {
        categories = items.groupBy {
            it.category
        }.map {
            Category(it.key,
                it.value.sumOf {
                    it.amount
                }
            )
        }
        total = categories.sumOf { it.amount }
        totalLabel = "$$total"
    }

    private fun onAction(x: Float, y: Float, action: (Int) -> Unit) {
        run chech@{
            angles.forEachIndexed { i, a ->
                if (isCurrentPoint(a, x, y)) {
                    action(i)
                    return@chech
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event ?: return false
        performClick()
        when (event.action) {
            MotionEvent.ACTION_UP -> {
                onAction(event.x, event.y) { i ->
                    animateUp(i)
                    listener?.onCLick(categories[i])
                }
            }
            MotionEvent.ACTION_DOWN -> {
                onAction(event.x, event.y) { i ->
                    animateDown(i)
                }
            }
            else -> {
                onAction(event.x, event.y) { i ->
                    animateUp(i)
                }
            }
        }
        return true
    }

    private fun animateDown(index: Int) {
        animations[index]?.cancel()
        animations[index] = viewScope.launch {
            repeat(60) { times ->
                val alpha = animatedAlpha[index] ?: 255
                animatedAlpha[index] = max(205,alpha - 5)
                invalidate()
                delay(5)
            }
            animatedAlpha[index] = 205
            invalidate()
        }
    }

    private fun animateUp(index: Int) {
        animations[index]?.cancel()
        animations[index] = viewScope.launch {
            repeat(60) { times ->
                val alpha = animatedAlpha[index] ?: 255
                animatedAlpha[index] = min(255,alpha + 5)
                invalidate()
                delay(5)
            }
            animatedAlpha[index] = 255
            invalidate()
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun calculateAngles(width: Int, height: Int) {
        val degreeAmount = total / 360f
        val angles = mutableListOf<Angle>()
        var _total = 0
        var maxPercent = 0
        var minPercent = 0
        var maxIndex = -1
        var minIndex = -1
        val bounds = Rect()
        percentLabelPaint.textSize = min(width, height) / 18f
        percentLabelPaint.textAlign = Paint.Align.LEFT
        percentLabelPaint.getTextBounds(percentPlaceholder, 0, percentPlaceholder.length, bounds)
        categories.forEachIndexed { i, c ->
            val startAngle = if (i == 0) -90f else angles[i - 1].let { it.start + it.current }
            val currentAngle = c.amount / degreeAmount
            val radius = bitRect.width() / 2f
            val angle = (startAngle + (currentAngle / 2f))
            val rawX = (bitRect.centerX() + (radius * cos(angle * rad))).toFloat()
            val rawY = (bitRect.centerY() + (radius * sin(angle * rad))).toFloat()
            val percent = ((c.amount.toFloat() / total) * 100f).roundToInt()
            if (maxIndex < 0 || percent > maxPercent) {
                maxPercent = percent
                maxIndex = i
            }
            if (maxIndex < 0 || percent < minPercent) {
                minPercent = percent
                minIndex = i
            }
            _total += percent
            angles.add(
                Angle(startAngle, currentAngle, PointF(rawX, rawY), percent)
            )
        }
        fixPercents(minIndex, maxIndex, _total, angles)
        this.angles = angles
    }

    private fun fixPercents(minIndex: Int, maxIndex: Int, _total: Int, angles: MutableList<Angle>) {
        val diff = 100 - _total
        val index = when {
            diff < 0 -> maxIndex
            diff > 0 -> minIndex
            else -> -1
        }
        if (index >= 0) {
            val angle = angles[index]
            angles[index] = angle.copy(percent = angle.percent + diff)
        }
    }

    private fun clipCenterCircle(canvas: Canvas) {
        centerClipPath.addCircle(bitRect.centerX(), bitRect.centerY(), centerClipRadius, Path.Direction.CW)
        canvas.clipOutPath(centerClipPath)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        drawTotal(canvas)
        clipCenterCircle(canvas)
        categories.forEachIndexed { i, _ ->
            drawBit(canvas, i)
            drawPercentLabel(canvas, i)
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.coroutineContext.cancelChildren()
        animations.clear()
    }

    override fun onSaveInstanceState(): Parcelable? {
        return SavedState(items, super.onSaveInstanceState())
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        (state as? SavedState)?.let { s ->
            super.onRestoreInstanceState(s.superState)
            items = s.items
            calculateCategories()
        }
    }

}