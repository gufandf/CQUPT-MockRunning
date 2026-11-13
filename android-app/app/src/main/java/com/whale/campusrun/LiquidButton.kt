/*
 * 液体按钮 —— 按 ColorOS 计算器（com.coloros.calculator 17.2.16）的
 * 「触摸动效」三重弹簧实现重写。
 *
 * 逆向证据见 refs/REA-CALCULATOR-FLUID.md，关键类和取值：
 *   com.coui.appcompat.animation.touchanimation.COUIIComposeMotionUnit
 *   com.coui.appcompat.animation.touchanimation.COUIPressAnimatorUnit   按下缩放
 *   com.coui.appcompat.animation.touchanimation.COUIDeformAnimatorUnit  面积守恒形变
 *   com.coui.appcompat.animation.touchanimation.COUIRubberAnimatorUnit  橡皮筋跟手
 *   com.coui.appcompat.animation.touchanimation.param.olivelink.*       默认参数
 *   assets/cal_btn_glow_0328.coz                                        边缘发光 SDF
 *
 * 它和我们以前那版（把胶囊轮廓逐点往拖动方向推）最大的区别：
 *
 *   ① **不是描边变形，是面积守恒缩放。**
 *      迎风侧拉伸 s 倍，垂直侧就压到 1/s。轮廓永远是胶囊，
 *      「水滴感」来自长宽比的变化，而不是把圆角扭成别的形状。
 *      好处是光栅化质量稳定，描边和高光不会在拐点处糊掉。
 *
 *   ② **三个弹簧各管一件事，参数不同。**
 *      按下：response 0.25 / bounce 0.0（干脆，不过冲）
 *      拖动：response 0.15 / bounce 0.15（跟手，几乎不晃）
 *      松手：response 0.42 / bounce 由拖动距离在 0.4~0.65 之间取（荡一下再收住）
 *
 *   ③ **位移走橡皮筋，不走线性限幅。**
 *      越拉越硬，视觉位移永远到不了上限，永远不出容器。
 *      以前是 len > maxDrag 就按比例缩 —— 手感在阈值那里是断的。
 *
 * 为什么仍然是自绘 + 自写弹簧：
 *   ColorOS 那套 COUI 组件库不对外开放，引入 androidx.dynamicanimation
 *   又违背本项目「零第三方依赖」的底线。所以把公式搬过来，用 Canvas 自己画。
 */
package com.whale.campusrun

import android.content.Context
import android.graphics.BlurMaskFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class LiquidButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    // 刻意不用 android.R.attr.buttonStyle：那套样式会带一个 stateList 背景
    // （灰色实心），把这里手画的形状整个盖住。背景和前景都自己管。
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    /**
     * 按钮的视觉层级。
     *
     * 三级，对应设置页里三类动作 —— 一页里所有按钮都做成整宽大胶囊，
     * 主次就分不出来了（「连接」和「openid 怎么拿」看起来一样重）。
     */
    enum class Tone {
        /** 主操作：渐变实心胶囊。一屏最多一个。 */
        ACCENT,
        /** 次操作：玻璃胶囊。 */
        GHOST,
        /** 第三级：文字链。无底无描边，只有字和按压反馈。 */
        TEXT,
        /** 中性实心（取消 / 重置）：浅底，在白卡片上也看得见。 */
        MUTED,
        /** 危险操作：红玻璃。 */
        DANGER
    }

    /**
     * 可选图标（纯图标按钮用，例如顶栏的日志 / 设置）。
     *
     * 图标画在胶囊内部，**跟着按钮一起缩放** —— 和 ColorOS 的 COUIButton 一致
     * （它给整个 View 设 scale，图标和文字一起动）。
     */
    var icon: Drawable? = null
        set(value) {
            field = value
            applyIconTint()
            invalidate()
        }

    /** 主按钮（渐变实心）/ 结束打卡（红玻璃）/ 次要按钮（玻璃）。 */
    var tone: Tone = Tone.ACCENT
        set(value) {
            if (field != value) {
                field = value
                applyPaint()
                invalidate()
            }
        }

    // ─────────────────────────────────────────── 逆向参数（全部来自 ColorOS）

    /** 按下缩放。目标值由 [FluidMotion.pressScale] 按尺寸算，这里只定手感。 */
    private val pressSpring = LiquidSpring(response = 0.25f, bounce = 0f)

    /** 形变两轴各一根弹簧，参数 = iolDragSpring（0.15 / 0.15）。 */
    private val deformX = LiquidSpring(response = 0.15f, bounce = 0.15f)
    private val deformY = LiquidSpring(response = 0.15f, bounce = 0.15f)

    /** 跟手位移两轴，参数同 iolDragSpring。 */
    private val rubberX = LiquidSpring(response = 0.15f, bounce = 0.15f)
    private val rubberY = LiquidSpring(response = 0.15f, bounce = 0.15f)

    private var targetSx = 1f
    private var targetSy = 1f
    private var targetTx = 0f
    private var targetTy = 0f

    /** 死区 5dp —— CommonParams.iolDeadZone。小于这个不算拖动。 */
    private val deadZone: Float get() = dp(5f)

    /** 位移橡皮筋上限 28dp / 曲率 0.05 —— iolMaxStretchDistance / iolRubberCurveRatio。 */
    private val maxStretch: Float get() = dp(28f)
    private val stretchCurve = 0.05f

    /**
     * 形变橡皮筋上限 / 曲率。
     *
     * ColorOS 的默认值是 150dp / 0.55，但那是给计算器 90dp 见方的按键标定的：
     * 把 150dp 套到手机底部这条 330dp 宽的胶囊上，手指拖 15dp 才换 2% 形变，
     * 拖动几乎看不出来。这里只改「曲率尺度」这一个标定量（90dp），
     * 映射公式、面积守恒、弹簧参数全部保持逆向值 —— 改的是手感标定，不是模型。
     */
    private val maxDeform: Float get() = dp(90f)
    private val deformCurve = 0.55f

    /** 最大拉伸 1.2 —— CommonParams.iolMaxScale，再由 scaleWithK 按尺寸衰减。 */
    private val baseMaxScale = 1.2f

    /**
     * 抬手弹簧。
     *
     * response 是「回去要多久」：ColorOS 默认 0.42 在自己那套 90dp 小按键上刚好，
     * 但放在这条 330dp 宽的主按钮上，0.42 秒的收尾看着像被弹回去 —— 太快。
     * 这里放到 0.58（收尾约 1.2 秒），bounce 由拖动距离在 0.4~0.65 之间取：
     * 轻点一下「啪」地收住，拖远了才荡两下。
     */
    private val handUpResponse = 0.58f
    private val handUpBaseBounce = 0.65f

    /**
     * 形变在 view 内部预留的余量（px）。
     *
     * 胶囊本体 = view 尺寸 − 2×room，所以它同时决定了「按钮看起来多大」。
     * 默认按高度取 17.5%（92dp 的主按钮 → 16dp 余量、60dp 本体，和设计稿一致）；
     * 小尺寸控件可以再用 `app:liquidRoom` 精确指定 —— 56dp 的圆形图标按钮
     * 要的是 44dp 本体，按比例算只有 36dp。
     */
    private var roomOverride = 0f
    private val room: Float
        get() = if (roomOverride > 0f) roomOverride else min(dp(16f), height * 0.175f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }

    /**
     * 边缘发光。
     *
     * 原版是一段 SDF 着色器（assets/cal_btn_glow_0328.coz 存的就是它：
     * sdRoundedBox + 1.1/((d−BaseA)·glow+1) 的衰减，压在按钮轮廓内外各一层）。
     * 项目 minSdk 24，RuntimeShader 要 33 —— 为一道边缘光写两套渲染路径不划算。
     * 这里用「描边 + BlurMaskFilter」等价复现，贴合胶囊轮廓、随按压淡入淡出。
     *
     * **按下要变暗、变窄**：形变越深，按钮越像被按进屏幕里，边上那圈光就该越弱。
     * 之前按下直接把 alpha 顶到 1、模糊半径还是固定的 7dp，看起来不是"发光"，
     * 而是按钮周围突然糊出一圈脏边 —— 尤其暗色下，深色底上那层蓝晕会被读成阴影。
     * 现在发光强度由按压深度反向驱动（见 [glowStrength]），深度按满时只剩三成。
     */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        maskFilter = BlurMaskFilter(dp(5f), BlurMaskFilter.Blur.NORMAL)
    }

    /** 发光峰值透明度 —— 原来给到 200，按下时暗色底上像糊出一圈脏阴影，压到 84。 */
    private val glowPeakAlpha = 84

    private val shape = Path()
    private val rect = RectF()

    private var dragging = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var dragX = 0f
    private var dragY = 0f

    /** 发光强度 0..1，按下点亮、抬手淡出。 */
    private var glowAlpha = 0f

    private var cAccent = 0
    private var cAccentDeep = 0
    private var cDangerBg = 0
    private var cDangerStroke = 0
    private var cGlass = 0
    private var cGlassStroke = 0
    private var cField = 0
    private var cFg = 0
    private var iconTint = 0

    private val touchSlop = dp(6f)

    /** 动画循环：还有弹簧在动就挂着，全停了就摘掉，不空转。 */
    private val ticker = object : Runnable {
        private var last = 0L

        override fun run() {
            val now = AnimationUtils.currentAnimationTimeMillis()
            if (last == 0L) last = now
            // 一帧跳太远会「瞬移」；解析解本身稳定，夹住只是为了视觉连续
            val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.032f)
            last = now

            var moving = pressSpring.step(if (downPress) pressTarget else 1f, dt)
            moving = deformX.step(targetSx, dt) or moving
            moving = deformY.step(targetSy, dt) or moving
            moving = rubberX.step(targetTx, dt) or moving
            moving = rubberY.step(targetTy, dt) or moving

            if (!dragging && glowAlpha > 0f) {
                glowAlpha = (glowAlpha - dt * 3.2f).coerceAtLeast(0f)
            }

            invalidate()
            if (moving || glowAlpha > 0f) {
                // 必须重新挂自己 —— postInvalidateOnAnimation 只排一次重绘，
                // 不会推进弹簧（以前把 step() 放在 onDraw 里才不需要这一步）
                postOnAnimation(this)
            } else {
                last = 0L
                glowAlpha = 0f
                invalidate()
            }
        }
    }

    /** 按下期间按压目标是尺寸算出来的值，抬手后目标是 1。 */
    private var pressTarget = 1f
    private var downPress = false

    init {
        isClickable = true
        isFocusable = true
        // 形状自己画：background 必须清空，否则会盖住形变
        background = null
        foreground = null
        clipToOutline = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = min(view.width, view.height) / 2f
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        // 形变 / 按压弹簧的静止值是 1（不变形、不压缩），位移是 0。
        // 必须显式设 —— 否则第一帧 sx = sy = 0，按钮会整个缩没。
        deformX.set(1f)
        deformY.set(1f)
        pressSpring.set(1f)
        rubberX.set(0f)
        rubberY.set(0f)
        targetSx = 1f
        targetSy = 1f

        readColors()
        readAttrs(attrs)
        applyPaint()
    }

    // ---------------------------------------------------------------- 初始化

    private fun readColors() {
        cAccent = ContextCompat.getColor(context, R.color.accent)
        cAccentDeep = ContextCompat.getColor(context, R.color.accent_deep)
        cDangerBg = ContextCompat.getColor(context, R.color.danger_bg)
        cDangerStroke = ContextCompat.getColor(context, R.color.danger_stroke)
        cGlass = ContextCompat.getColor(context, R.color.glass)
        cGlassStroke = ContextCompat.getColor(context, R.color.glass_stroke)
        cField = ContextCompat.getColor(context, R.color.field_bg)
        cFg = ContextCompat.getColor(context, R.color.fg)
        iconTint = cFg
    }

    /** 图标跟着主题走：颜色走资源，不在 XML 里写死 tint。 */
    private fun applyIconTint() {
        icon?.let {
            DrawableCompat.setTintList(DrawableCompat.wrap(it).mutate(), ColorStateList.valueOf(iconTint))
        }
    }

    private fun readAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        val a = context.obtainStyledAttributes(attrs, R.styleable.LiquidButton)
        tone = when (a.getInt(R.styleable.LiquidButton_liquidTone, 0)) {
            1 -> Tone.DANGER
            2 -> Tone.GHOST
            3 -> Tone.TEXT
            4 -> Tone.MUTED
            else -> Tone.ACCENT
        }
        roomOverride = a.getDimension(R.styleable.LiquidButton_liquidRoom, 0f)
        val iconRes = a.getResourceId(R.styleable.LiquidButton_liquidIcon, 0)
        if (iconRes != 0) {
            icon = ContextCompat.getDrawable(context, iconRes)
            iconTint = a.getColor(R.styleable.LiquidButton_liquidIconTint, cFg)
        }
        a.recycle()
    }

    private fun applyPaint() {
        when (tone) {
            Tone.ACCENT -> {
                // 注意：颜色 alpha 会**乘在 shader 输出上**。这里写 TRANSPARENT
                // 不是「交给 shader」，而是把整条渐变乘成 0 —— 按钮直接消失。
                // 所以必须用不透明的白色占位，RGB 由 shader 完全覆盖。
                fillPaint.color = Color.WHITE
                fillPaint.shader = LinearGradient(
                    0f, 0f, 0f, height.coerceAtLeast(1).toFloat(),
                    cAccent, cAccentDeep, Shader.TileMode.CLAMP
                )
            }
            Tone.DANGER -> {
                fillPaint.shader = null
                fillPaint.color = cDangerBg
            }
            Tone.GHOST, Tone.TEXT -> {
                fillPaint.shader = null
                fillPaint.color = cGlass
            }
            Tone.MUTED -> {
                fillPaint.shader = null
                fillPaint.color = cField
            }
        }
        strokePaint.color = if (tone == Tone.DANGER) cDangerStroke else cGlassStroke
        glowPaint.color = if (tone == Tone.DANGER) cDangerStroke else cAccent
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPaint()      // 渐变要按新的高度重建
        pressTarget = FluidMotion.pressScale(
            w.toFloat(), h.toFloat(), resources.displayMetrics.density
        )
        // 发光的模糊半径跟着余量收 —— 56dp 的小图标按钮只留 6dp 余量，
        // 还按 5dp 糊出去的话，那圈光自己就先越过 view 边界被裁掉，
        // 看到的是一条直边重新盖上来（和按钮顶出框是同一个视觉结果）。
        // 只在尺寸变化时重建：BlurMaskFilter 在 onDraw 里每帧新建太贵。
        glowPaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
        invalidate()
    }

    /**
     * 边缘光的模糊半径。
     *
     * 满额 5dp，但永远不超过余量的 40% —— 光必须长在 [room] 里面，
     * 否则它会先于胶囊本体被 view 边界裁掉。这个值同样进 [fitBudget] 的账。
     */
    private val glowRadius: Float get() = min(dp(5f), room * 0.4f)

    // ---------------------------------------------------------------- 形变

    /** 胶囊本体的矩形（内缩 [room]）。 */
    private fun capsuleRect(): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val l = room
        val t = room
        val r = w - room
        val b = h - room
        if (r - l <= 4f || b - t <= 4f) return false
        rect.set(l, t, r, b)
        return true
    }

    /**
     * 形变 / 位移共用的「还能长出去多少」。
     *
     * 胶囊本体离 view 边界只有 [room]，要从里面先扣掉两笔一直都在、却从来没进过账的开销：
     *   · 描边（`strokeWidth` 以路径为中心线，一半长在轮廓外）；
     *   · 边缘光的模糊半径（见 [glowRadius]）。只按半额计 ——
     *     光本身就是软衰减，尾巴被裁掉一点点看不出来；
     *     但胶囊本体那条硬边只要越出去一个像素，就会呈现成一条直角。
     *     半额既能保住手感（56dp 的小图标按钮不至于形变全被砍没），也不露破绽。
     *
     * `0.9` 是给弹簧过冲留的：夹取只夹目标值，绘制值会被弹簧推过目标再荡回来，
     * 所以预算不能给满。
     */
    private val fitBudget: Float
        get() {
            val ink = strokePaint.strokeWidth / 2f
            return ((room - ink - glowRadius * 0.5f) * 0.9f).coerceAtLeast(0f)
        }

    /**
     * 把这一帧真正要画的 sx / sy / tx / ty 收进渲染框。
     *
     * 关键变化：**不再分别夹形变和位移**。以前两边各按 `room·0.9` 独立限幅，
     * 加起来就是 1.8·room —— 56dp 的顶栏图标按钮 room = 6dp，拉到头正好探出 view 一条，
     * 被父容器裁成直角。现在两者共享一份预算，超额按同比例一起收，
     * 所以「指尖到哪、形状跟到哪」的手感不变，只是永远不会画到框外。
     *
     * 在 [onDraw] 里对实时值做，而不是只夹目标值：抬手回弹是欠阻尼的，
     * 中途会过冲，只夹目标等于没夹。
     */
    private fun fitIntoBounds(sx: Float, sy: Float, tx: Float, ty: Float): FluidMotion.Motion {
        // 尺寸还没定（首帧 / 被量成 0）就没有"框"可言，原样返回
        if (!capsuleRect()) return FluidMotion.Motion(sx, sy, tx, ty)
        return FluidMotion.fitMotion(
            sx, sy, tx, ty,
            halfWidth = rect.width() / 2f,
            halfHeight = rect.height() / 2f,
            budget = fitBudget
        )
    }

    // ---------------------------------------------------------------- 绘制

    /** 0 = 完全抬起，1 = 按到底。按压弹簧的值（1 → pressTarget）反推出来的。 */
    private val pressDepth: Float
        get() {
            val span = (1f - pressTarget).coerceAtLeast(0.001f)
            return ((1f - pressSpring.value) / span).coerceIn(0f, 1f)
        }

    override fun onDraw(canvas: Canvas) {
        if (!capsuleRect()) {
            super.onDraw(canvas)
            return
        }

        /*
         * 这一帧实际画出来的四个量 —— 全部经过 [fitIntoBounds]。
         *
         * 以前这里是 `deformX.value * pressSpring.value` 直接上画布：
         * 形变由 MOVE 里的 clampDeform 夹过、位移由 coerceIn 夹过，
         * 但两者各占一份余量、还都没扣描边，叠加后就伸出 view 边界了。
         */
        val fit = fitIntoBounds(
            deformX.value * pressSpring.value,
            deformY.value * pressSpring.value,
            rubberX.value,
            rubberY.value
        )
        val sx = fit.sx
        val sy = fit.sy

        /*
         * 缩放锚点固定在胶囊中心。
         *
         * 曾经照搬过 ColorOS 的 resolveAutoDeformAnchor（朝哪边拖就钉住对边），
         * 结果手指在中心附近来回越过 5dp 死区时，锚点会在「中心 ↔ 左/右边缘」之间翻转；
         * 而 sx 里乘着按压缩放（现在约 0.97），一条 800px 宽的胶囊一翻就是 30+px 的位移，
         * 看起来像按钮被弹走了。
         *
         * 回去翻 COUITouchMotionAnimator 才发现：mAutoDeformAnchorEnable 默认 false，
         * 只有在 config 里显式开了才会用 auto anchor。默认行为就是
         * CommonParams.pivotX/pivotY = -1 → 解析成中心。这里改成一致的默认值。
         */
        val px = rect.centerX()
        val py = rect.centerY()

        val save = canvas.save()
        // 用夹过的 tx / ty，不是弹簧原始值 —— 原始值可能已经把胶囊推出去了
        canvas.translate(fit.tx, fit.ty)
        canvas.scale(sx, sy, px, py)

        shape.reset()
        val radius = min(rect.width(), rect.height()) / 2f
        shape.addRoundRect(rect, radius, radius, Path.Direction.CW)

        /*
         * 边缘光：按下时**变弱**，不是变强。
         *
         * 以前是按下直接把 alpha 顶到 200、模糊半径固定 7dp —— 那圈光在暗色底上
         * 会被读成"按钮外扩的一圈阴影"，而不是边缘发光。真实按压的手感是按钮
         * 往屏幕里沉：发光收窄、变淡。所以强度由按压深度反向驱动，
         * 按到底只剩三成，模糊半径也同时收 5dp → 3dp。
         */
        if (glowAlpha > 0.01f) {
            // 只调 alpha 和描边宽度：BlurMaskFilter 每帧新建代价太大（onDraw 里不能分配）
            val fade = 1f - pressDepth * 0.7f
            glowPaint.alpha = (glowPeakAlpha * glowAlpha * fade).toInt().coerceIn(0, 255)
            glowPaint.strokeWidth = dp(1.6f) * (1f - pressDepth * 0.4f)
            canvas.drawPath(shape, glowPaint)
        }

        if (tone != Tone.TEXT) {
            canvas.drawPath(shape, fillPaint)
            if (tone != Tone.ACCENT) canvas.drawPath(shape, strokePaint)
        }

        drawIcon(canvas)

        // 文字跟着按钮一起缩放 —— 和 ColorOS 的 COUIButton 一致（它对整个 View 设 scale）
        super.onDraw(canvas)

        canvas.restoreToCount(save)
    }

    /** 纯图标按钮：图标居中，尺寸按胶囊高度取（44dp 的圆形按钮里图标约 20dp）。 */
    private fun drawIcon(canvas: Canvas) {
        val d = icon ?: return
        val target = min(rect.width(), rect.height()) * 0.46f
        if (target <= 0f) return
        val cx = rect.centerX()
        val cy = rect.centerY()
        val half = target / 2f
        d.setBounds(
            (cx - half).toInt(), (cy - half).toInt(),
            (cx + half).toInt(), (cy + half).toInt()
        )
        d.draw(canvas)
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragX = 0f
                dragY = 0f
                dragging = true
                moved = false
                downPress = true

                // 按下：干脆，不过冲
                pressSpring.retune(0.25f, 0f)
                deformX.retune(0.15f, 0.15f)
                deformY.retune(0.15f, 0.15f)
                rubberX.retune(0.15f, 0.15f)
                rubberY.retune(0.15f, 0.15f)

                targetSx = 1f
                targetSy = 1f
                targetTx = 0f
                targetTy = 0f
                pressTarget = FluidMotion.pressScale(
                    width.toFloat(), height.toFloat(), resources.displayMetrics.density
                )

                glowAlpha = 1f
                postOnAnimation(ticker)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return super.onTouchEvent(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (hypot(dx, dy) > touchSlop) moved = true
                dragX = dx
                dragY = dy

                if (abs(dx) > deadZone || abs(dy) > deadZone) {
                    // 死区内的那一轴不参与形变 —— 否则斜着拖会两边一起鼓
                    val ddx = if (abs(dx) > deadZone) dx else 0f
                    val ddy = if (abs(dy) > deadZone) dy else 0f

                    val maxScale = FluidMotion.scaleWithK(
                        width.toFloat(), height.toFloat(), baseMaxScale
                    )
                    val f = FluidMotion.deformScaleFactors(ddx, ddy, maxDeform, deformCurve, maxScale)

                    /*
                     * 目标值也走同一套「装进渲染框」的夹取，但**形变和位移共享预算**。
                     *
                     * 以前这里是 clampDeform(f.sx, f.sy) + 位移各自 coerceIn(±room·0.9)：
                     * 形变吃掉 0.9·room 之后，位移又能再吃 0.9·room，
                     * 目标值本身就合法地组合成了一个出框的组合。
                     * 现在目标值先按预算收好，onDraw 再对实时值兜一层（弹簧会过冲）。
                     */
                    val target = fitIntoBounds(
                        f.sx, f.sy,
                        FluidMotion.rubberBand(ddx, stretchCurve, maxStretch),
                        FluidMotion.rubberBand(ddy, stretchCurve, maxStretch)
                    )
                    targetSx = target.sx
                    targetSy = target.sy
                    targetTx = target.tx
                    targetTy = target.ty

                    postOnAnimation(ticker)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return super.onTouchEvent(event)
                dragging = false
                if (!moved) performClick()
                release()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return super.onTouchEvent(event)
                dragging = false
                release()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 松手：形变 / 位移回原位，按压回 1。
     *
     * 抬手阻尼比不是常数 —— 拖得越远越弹，见 [FluidMotion.handUpBounce]。
     * 这就是 ColorOS 那个「松手会荡一下再收住」的来源。
     */
    private fun release() {
        downPress = false
        val pan = maxOf(abs(dragX), abs(dragY))
        val bounce = FluidMotion.handUpBounce(pan, handUpBaseBounce)

        // 保留当前速度地换参数，曲线不能跳
        deformX.retune(handUpResponse, bounce)
        deformY.retune(handUpResponse, bounce)
        rubberX.retune(handUpResponse, bounce)
        rubberY.retune(handUpResponse, bounce)
        pressSpring.retune(handUpResponse, bounce)

        targetSx = 1f
        targetSy = 1f
        targetTx = 0f
        targetTy = 0f
        dragX = 0f
        dragY = 0f

        postOnAnimation(ticker)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
