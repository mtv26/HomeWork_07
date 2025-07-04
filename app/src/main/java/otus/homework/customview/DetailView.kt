package otus.homework.customview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.DiscretePathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

class DetailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = -1,
    defStyleRes: Int = -1): View(context, attrs, defStyleAttr, defStyleRes) {

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var fromAmount: Int = 0
    private var toAmount: Int = 1000
    private var fromDate: LocalDateTime? = null
    private var toDate: LocalDateTime? = null
    private var xAxisResolution = 0f
    private var yAxisResolution = 0f
    private var isPortrait = false

    private var items: List<Item> = emptyList()
    private val labelHeight by lazy { textPain.fontMetrics.descent - textPain.fontMetrics.ascent }

    private var detail: List<Detail> = emptyList()
    private var category: String? = null

    private var source: Int = -1
    private var initSource: Job? = null
    private var drawRect: RectF = RectF()

    private var xAxisFirstDate: LocalDateTime? = null
    private var xAxisLastDate: LocalDateTime? = null
    private var yAxisStartDate: LocalDateTime? = null

    private var yAxisTotalAmount: Int = 0


    private val xyAxisLineWidth = 2f.toPx(context)
    private val xyAxisPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
    }

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.DetailView, defStyleAttr, 0)
        source = typedArray.getResourceId(R.styleable.DetailView_dv_source, -1)
        category = if (category.isNullOrEmpty()) typedArray.getString(R.styleable.DetailView_dv_category) else category
        if (source > -1) {
            obtainSource(category)
        }
        try {
            typedArray.recycle()
        } catch (e: Exception) {}
    }

    private fun obtainSource(category: String?) {
        val prevJob = initSource
        initSource = viewScope.launch(Dispatchers.IO) {
            prevJob?.cancelAndJoin()
            try {
                val raw = BufferedReader(InputStreamReader(resources.openRawResource(source), "UTF-8")).use {
                    it.readText()
                }
                val items = Json.decodeFromString<List<Item>>(raw)
                withContext(Dispatchers.Main) {
                    setCategory(category)
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

    data class Detail(
        val name: String,
        val amount: Int,
        val time: LocalDateTime
    )

    @Parcelize
    private data class SavedState(val items: List<Item>, val category: String?, val superState: Parcelable?): Parcelable

    fun setItems(items: List<Item>) {
        this.items = items
        calculateDetail()
        invalidate()
    }

    fun setCategory(category: String? = null) {
        this.category = category
        calculateDetail()
        invalidate()
    }

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
        calculateGridRect(width, height)
        calculateX()
        calculateY()
    }

    private fun calculateGridRect(width: Int, height: Int) {
        val min = min(width, height)
        val max = max(width, height)
        isPortrait = width < height
        val offset = (max - min) / 2
        val left = 0f
        val top = if (width >= height) 0f else offset.toFloat()
        drawRect.set(
            left,
            top,
            width.toFloat() - left,
            height.toFloat() - (top + if (isPortrait) 0f else labelHeight * 1.5f)
        )
    }

    private fun calculateX() {
        val _fromDate = fromDate
        val _toDate = toDate
        if (_fromDate != null && _toDate != null) {
            val divisionCount = (_toDate.date.dayOfMonth - _fromDate.date.dayOfMonth) + 1
            xAxisFirstDate = _fromDate
            yAxisStartDate = _fromDate.toJavaLocalDateTime().toLocalDate().toKotlinLocalDate().atTime(0, 0)
            xAxisLastDate = _toDate
                .toJavaLocalDateTime()
                .plusDays(1)
                .toKotlinLocalDateTime()
            xAxisResolution = drawRect.width() / (1440f * divisionCount)
        }
    }

    private fun calculateY() {
        val toDiff = toAmount % 1000
        if (toDiff > 0) {
            yAxisTotalAmount = (1000 - toDiff) + toAmount
        } else {
            yAxisTotalAmount = toAmount
        }
        yAxisResolution = drawRect.height() / yAxisTotalAmount
    }

    private fun calculateDetail() {
        if (items.isEmpty()) return
        var maxAmount = 0
        var minAmount = 0
        detail = items.filter {
            if (!category.isNullOrEmpty()) {
                it.category == category
            } else true
        }.sortedBy { it.time }.map {
            if (maxAmount == 0 || it.amount > maxAmount) {
                maxAmount = it.amount
            }
            if (minAmount == 0 || it.amount < minAmount) {
                minAmount = it.amount
            }
            Detail(it.name, it.amount, Instant.fromEpochSeconds(it.time).toLocalDateTime(TimeZone.currentSystemDefault()))
        }
        fromAmount = minAmount
        toAmount = maxAmount
        fromDate = detail.first().time
        toDate = detail.last().time
    }

    private fun getXPosition(index: Int): Float {
        return yAxisStartDate?.let { date ->
            drawRect.left + ((detail[index].time.minutesPerMonth() - date.minutesPerMonth()) * xAxisResolution)
        } ?: -1f
    }

    private fun LocalDateTime.minutesPerMonth(): Int {
        return ((dayOfMonth - 1) * 24 * 60) + (hour * 60) + minute
    }

    private fun getYPosition(index: Int): Float {
        return drawRect.bottom - (detail[index].amount * yAxisResolution)
    }

    private val path  = Path()
    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
        pathEffect = CornerPathEffect(200f)
    }

    private fun drawPath(canvas: Canvas) {
        path.reset()
        path.moveTo(drawRect.left, drawRect.bottom)
        detail.forEachIndexed { i, _ ->
            path.lineTo(getXPosition(i), getYPosition(i))
        }
        canvas.drawPath(path, paint)
    }

    private val textPain = Paint().apply {
        textSize = 16f.toPx(context)
        textAlign = Paint.Align.LEFT
        color = Color.BLACK
        isAntiAlias = true
    }

    private fun drawGrid(canvas: Canvas) {
        xAxisLastDate ?: return
        xAxisFirstDate ?: return

        xyAxisPaint.isAntiAlias = true

        val xyAxisLineHalfWidth = xyAxisLineWidth / 2f
        xyAxisPaint.strokeWidth = xyAxisLineWidth
        canvas.drawLine(drawRect.left, drawRect.bottom - xyAxisLineHalfWidth, drawRect.right, drawRect.bottom - xyAxisLineHalfWidth, xyAxisPaint)
        canvas.drawLine(drawRect.left + xyAxisLineHalfWidth, drawRect.bottom, drawRect.left + xyAxisLineHalfWidth, drawRect.top, xyAxisPaint)

        val xDivisionCount = (xAxisLastDate!!.dayOfMonth - xAxisFirstDate!!.dayOfMonth)
        val xDivisionWidth = drawRect.width() / xDivisionCount
        var xStep = xDivisionWidth

        xyAxisPaint.strokeWidth = xyAxisLineHalfWidth
        val labelOffset = (labelHeight * 1.5f) / 2f
        val y = drawRect.bottom + xyAxisLineWidth + labelOffset
        (0..xDivisionCount).forEach {
            val startX = drawRect.left + (xStep * it) - (xyAxisLineHalfWidth / 2f)
            val endX = drawRect.left + (xStep * it) - (xyAxisLineHalfWidth / 2f)
            if (it > 0) {
                canvas.drawLine(startX, drawRect.bottom, endX, drawRect.top, xyAxisPaint)
            }
            val label = xAxisFirstDate!!.dayOfMonth + it
            val labelWidth = textPain.measureText("$label")
            if (it == 0) {
                canvas.drawText("${label} ${xAxisFirstDate!!.month.name}", startX + (labelHeight / 2f), y, textPain)
            } else if (it == xDivisionCount) {
                canvas.drawText("$label", drawRect.right - (labelWidth + (labelWidth / 2f)), y, textPain)
            } else {
                canvas.drawText("$label", startX - (labelHeight / 2f), y, textPain)
            }
        }

        val yDivisionCount = min(4, yAxisTotalAmount / (if (yAxisTotalAmount == 1000) 500 else 1000))
        val yDivisionHeight = drawRect.height() / yDivisionCount
        var yStep = yDivisionHeight

        val x = drawRect.left + (labelHeight / 2f)
        (0..yDivisionCount).forEach {
            val startY = drawRect.bottom - ((yStep * it) + (xyAxisLineHalfWidth / 2f))
            val endY = drawRect.bottom - ((yStep * it) + (xyAxisLineHalfWidth / 2f))
            if (it > 0) {
                canvas.drawLine(drawRect.left, startY, drawRect.right, endY, xyAxisPaint)
            }
            val label = (yAxisTotalAmount / yDivisionCount) * it
            if (it == 0) {
                canvas.drawText("${label}$", x, startY - (labelHeight / 2f), textPain)
            } else {
                canvas.drawText("${label}$", x, startY + xyAxisLineHalfWidth + labelHeight, textPain)
            }
        }
        category?.also {
            val labelWidth = textPain.measureText(it)
            canvas.drawText(
                it,
                drawRect.right - (labelWidth + (labelHeight / 2f)),
                drawRect.top + xyAxisLineHalfWidth + labelHeight,
                textPain
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        drawGrid(canvas)
        drawPath(canvas)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.coroutineContext.cancelChildren()
    }

    override fun onSaveInstanceState(): Parcelable? {
        return SavedState(items, category, super.onSaveInstanceState())
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        (state as? SavedState)?.let { s ->
            super.onRestoreInstanceState(s.superState)
            initSource?.cancel()
            items = s.items
            category = s.category
            calculateDetail()
        }
    }

}