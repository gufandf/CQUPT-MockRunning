/*
 * 一行设置项。
 *
 * 参照 ColorOS 设置页的结构：一整组设置是一张圆角卡片，卡片里一行一项 ——
 * 左边标题（可带一行灰色描述），右边当前取值 + 箭头，行与行之间一条细分割线。
 *
 * 为什么做成自定义 View 而不是在八个布局里复制同样的 XML：
 *   我们的设置页分成了首页 + 七个二级页，行数加起来三十多行。
 *   每行手写「标题 + 描述 + 取值 + 箭头 + 分割线」五层结构，
 *   改一次字号或行距就要改三十处。这里把结构收进一个 <merge> 布局，
 *   外部只剩 app:rowTitle / app:rowValue 这种属性。
 */
package com.whale.campusrun

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class SettingsRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val titleView: TextView
    private val summaryView: TextView
    private val valueView: TextView
    private val chevronView: TextView
    private val dividerView: View

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_settings_row, this, true)

        titleView = findViewById(R.id.rowTitle)
        summaryView = findViewById(R.id.rowSummary)
        valueView = findViewById(R.id.rowValue)
        chevronView = findViewById(R.id.rowChevron)
        dividerView = findViewById(R.id.rowDividerLine)

        val a = context.obtainStyledAttributes(attrs, R.styleable.SettingsRow)
        val title = a.getString(R.styleable.SettingsRow_rowTitle)
        val summary = a.getString(R.styleable.SettingsRow_rowSummary)
        val value = a.getString(R.styleable.SettingsRow_rowValue)
        val chevron = a.getBoolean(R.styleable.SettingsRow_rowChevron, false)
        val accent = a.getBoolean(R.styleable.SettingsRow_rowAccent, false)
        val divider = a.getBoolean(R.styleable.SettingsRow_rowDivider, true)
        a.recycle()

        if (title != null) titleView.text = title
        setSummary(summary)
        setValue(value)
        chevronView.visibility = if (chevron) VISIBLE else GONE
        dividerView.visibility = if (divider) VISIBLE else GONE

        if (accent) {
            titleView.setTextColor(ContextCompat.getColor(context, R.color.accent))
        }

        // 有箭头的行才可点，避免「点空白没反应」的错觉
        isClickable = chevron || accent
        isFocusable = isClickable
    }

    /** 右侧取值（选中项 / 当前状态）。传 null 或空串就隐藏。 */
    fun setValue(text: CharSequence?) {
        valueView.text = text
        valueView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** 标题下方那行灰色说明。 */
    fun setSummary(text: CharSequence?) {
        summaryView.text = text
        summaryView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    /** 右侧取值的颜色（正常 / 成功 / 次要）。传 0 恢复默认。 */
    fun setValueColor(color: Int) {
        valueView.setTextColor(
            if (color == 0) ContextCompat.getColor(context, R.color.fg_dim) else color
        )
    }

    /** 整组最后一行把分割线去掉，圆角才不会出现一道「多出来的线」。 */
    fun setDividerVisible(visible: Boolean) {
        dividerView.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
