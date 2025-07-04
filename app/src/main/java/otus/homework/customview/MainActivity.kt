package otus.homework.customview

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.transition.Transition
import androidx.transition.TransitionManager
import otus.homework.customview.PieChartView.Item

class MainActivity : AppCompatActivity() {

    private var pieChart: PieChartView? = null
    private var detail: DetailView? = null
    private var root: ConstraintLayout? = null

    override fun onBackPressed() {
        if (detail?.isVisible == true) {
            root?.let {
                TransitionManager.beginDelayedTransition(it)
                detail?.isVisible = false
                pieChart?.isVisible = true
            }
        } else super.onBackPressed()
    }

    private var detailIsVisible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById<ConstraintLayout>(R.id.root)
        pieChart = findViewById<PieChartView>(R.id.pie_chart_view)
        detail = findViewById<DetailView>(R.id.detail_view)
        pieChart?.setOnCategoryClickListener(object : PieChartView.CategoryClickListener {
            override fun onCLick(category: PieChartView.Category) {
                Toast.makeText(this@MainActivity, category.name, Toast.LENGTH_SHORT).show()
                detail?.setCategory(category.name)
                root?.let {
                    TransitionManager.beginDelayedTransition(it)
                    pieChart?.isVisible = false
                    detail?.isVisible = true
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("detail_visibility_key", detail?.isVisible ?: false)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        detailIsVisible = savedInstanceState.getBoolean("detail_visibility_key", false)
        detail?.isVisible = detailIsVisible
        pieChart?.isVisible = !detailIsVisible
    }

    override fun onDestroy() {
        super.onDestroy()
        pieChart?.setOnCategoryClickListener(null)
        pieChart = null
        detail = null
        root = null
    }
}