package com.yoyo.dshmobile.shell.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.yoyo.dshmobile.shell.R
import com.yoyo.dshmobile.shell.ui.LoadingButton
import com.yoyo.dshmobile.shell.ui.themedDialog
import kotlinx.coroutines.launch

/**
 * 引导页原生 View 组装（白色简洁 Flat Minimalist 主题）。
 * 4 页（P1 介绍 / P2 协议 / P3 权限 / P4 高级权限）+ 底部圆点进度指示器，
 * 由 [ViewPager2] 承载页面，按钮驱动翻页；P2 未点击「同意并继续」前禁用滑动。
 *
 * 颜色/样式一律从统一主题资源读取（R.color.dh_* / R.style.*），不硬编码颜色。
 * 状态流转由 [bind] 在 LifecycleOwner 的 STARTED 作用域内收集 ViewModel 的 StateFlow 驱动。
 *
 * @param viewModel 引导页 ViewModel（Shizuku/Root/权限状态单一来源）
 * @param onFinish 完成/跳过引导回调
 * @param onRequestPermissions 申请运行时权限回调（由宿主 Activity 的 launcher 处理）
 */
class OnboardingScreenView(
  val context: Context,
  private val viewModel: OnboardingViewModel,
  private val onFinish: () -> Unit,
  private val onRequestPermissions: () -> Unit,
) {
  private val res = context.resources

  /** 根布局（Activity 通过 setContentView 设置）。 */
  val rootView: View

  private lateinit var viewPager: ViewPager2
  private lateinit var pageDots: LinearLayout

  // P2 协议正文
  private lateinit var policyBody: TextView

  // P2 协议加载态 spinner
  private lateinit var policySpinner: ProgressBar

  // P3 权限状态标签
  private lateinit var storageTag: TextView
  private lateinit var notifTag: TextView

  // P3 权限操作按钮（手机主按钮 或 平板右下按钮，同时只显示其一）
  private lateinit var permAction: LoadingButton

  // P4 Shizuku 权限卡片：状态文本 + 授权按钮
  private lateinit var shizukuStatus: TextView
  private lateinit var shizukuBtn: LoadingButton

  // P4 Root 权限卡片：状态文本 + 检测按钮
  private lateinit var rootStatus: TextView
  private lateinit var rootBtn: LoadingButton

  // Root 检测进行中标志（PITFALLS §11：rootState 初始 CHECKING 但不会自动检测，
  // 需本地标志驱动按钮 loading，避免默认一直 loading 造成功能死锁）
  private var rootCheckRunning = false

  init {
    val wide = isWideLayout()
    val rawPages = listOf(buildPage1(), buildPage2(), buildPage3(), buildPage4())
    // 宽屏：左侧品牌列固定、右侧 ViewPager 仅承载内容页（每页竖向 ScrollView 便于独立滚动）；
    // 窄屏：单栏整页直接进 ViewPager。
    val pages: List<View> = if (wide) rawPages.map { pageContent(it) } else rawPages

    viewPager = ViewPager2(context).apply {
      adapter = PagesAdapter(pages)
      registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = onPageChanged(position)
      })
      // 右栏显式白底，避免平板横屏下 ViewPager 区域因首帧未渲染而呈现空白
      setBackgroundColor(color(R.color.dh_background))
    }
    // 拒绝滑动翻页：只允许通过按钮（下一步 / 同意并继续）切换页面
    viewPager.isUserInputEnabled = false

    val container = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      // 全端白底（浅灰白 dh_background，去掉渐变/网格背景）
      setBackgroundColor(color(R.color.dh_background))
    }
    if (wide) {
      // 横排：固定品牌列 + ViewPager（仅右侧内容随翻页滑动，左侧 logo 静止）
      val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(32), dp(24), dp(32), dp(16))
      }
      row.addView(
        buildBrandPanel(),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply {
          rightMargin = dp(40)
        },
      )
      row.addView(
        viewPager,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f),
      )
      container.addView(
        row,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
      )
    } else {
      container.addView(
        viewPager,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
      )
    }
    pageDots = buildDots()
    container.addView(
      pageDots,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
        gravity = Gravity.CENTER
      },
    )
    // 全屏铺满，无 520dp 居中限宽小盒子
    rootView = container
    updateDots(0)
    // ViewPager2 首帧测量兜底：在首次 laid out 后再次触发测量，
    // 规避其父为 weight 布局时宽度/高度首帧为 0 导致内容未渲染的经典时序问题
    viewPager.post { viewPager.requestLayout() }
    container.post { container.requestLayout() }
  }

  /**
   * 绑定生命周期：在 STARTED 作用域收集 ViewModel 状态刷新 UI；
   * 同时异步加载远程用户协议（失败由 loader 回退内置文本，绝不阻断）。
   */
  fun bind(lifecycleOwner: LifecycleOwner) {
    lifecycleOwner.lifecycleScope.launch {
      if (::policySpinner.isInitialized) policySpinner.visibility = View.VISIBLE
      policyBody.text = RemotePolicyLoader.loadPolicy(context)
      policyBody.setTextColor(color(R.color.dh_text_primary))
      if (::policySpinner.isInitialized) policySpinner.visibility = View.GONE
    }
    lifecycleOwner.lifecycleScope.launch {
      lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { viewModel.storageGranted.collect { setStorageTag(it) } }
        launch { viewModel.notificationGranted.collect { setNotifTag(it) } }
        launch { viewModel.shizukuState.collect { renderShizuku(it) } }
        launch { viewModel.rootState.collect { renderRoot(it) } }
      }
    }
  }

  /** 刷新权限状态到 ViewModel（进入 P3 / 权限申请回调后调用）。 */
  fun refreshPermissions() {
    val storage = if (Build.VERSION.SDK_INT >= 33) {
      context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else {
      context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    val notif =
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    viewModel.updatePermissionStates(storage, notif)
    updatePermButton()
  }

  /** 权限申请流程已处理完毕（系统授权回调后调用）：复位按钮 loading 并刷新按钮文案。 */
  fun onPermissionsResultProcessed() {
    if (::permAction.isInitialized) permAction.setLoading(false)
    updatePermButton()
  }

  /** 依据存储 + 通知两权限是否齐全，切换 P3 操作按钮文案（全齐=下一步 / 未齐=获取权限）。 */
  private fun updatePermButton() {
    if (!::permAction.isInitialized) return
    val allGranted = viewModel.storageGranted.value && viewModel.notificationGranted.value
    permAction.setLabel(
      if (allGranted) res.getString(R.string.onb_next) else res.getString(R.string.onb_get_permission),
    )
  }

  /** 权限申请被拒后弹出引导弹窗（提供「去设置」入口；统一 themedDialog 白底圆角+主色按钮）。 */
  fun showPermissionDeniedDialog() {
    context.themedDialog(
      title = "需要权限",
      message = "存储权限与通知权限用于正常展示与通知核心功能。若你已关闭，请前往系统设置手动授权。",
      negativeText = "取消",
      positiveText = "去设置",
      onPositive = {
        val intent = Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
      },
    ).show()
  }

  /* =========================================================================
   * 页面切换 / 底部圆点
   * ========================================================================= */

  private fun onPageChanged(position: Int) {
    updateDots(position)
    when (position) {
      1 -> Unit
      2 -> refreshPermissions()
      3 -> viewModel.refreshShizukuStatus()
    }
  }

  private fun updateDots(current: Int) {
    val wideP3 = isWideLayout() && current == 2
    for (i in 0 until pageDots.childCount) {
      val dot = pageDots.getChildAt(i)
      val lp = dot.layoutParams as LinearLayout.LayoutParams
      if (wideP3) {
        // 平板横屏 P3：圆形指示器（当前页蓝实心圆、其余浅灰空心圆）
        if (i == current) {
          dot.setBackgroundResource(R.drawable.bg_dot_circle_active)
          lp.width = dp(10)
          lp.height = dp(10)
        } else {
          dot.setBackgroundResource(R.drawable.bg_dot_circle_idle)
          lp.width = dp(10)
          lp.height = dp(10)
        }
      } else if (i == current) {
        dot.setBackgroundResource(R.drawable.bg_dot_capsule_active)
        lp.width = dp(18)
        lp.height = dp(8)
      } else {
        dot.setBackgroundResource(R.drawable.bg_dot_capsule_idle)
        lp.width = dp(8)
        lp.height = dp(8)
      }
      dot.layoutParams = lp
    }
  }

  private fun buildDots(): LinearLayout {
    val bar = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }
    repeat(4) {
      val dot = View(context).apply {
        setBackgroundResource(R.drawable.bg_dot_capsule_idle)
      }
      bar.addView(
        dot,
        LinearLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(dp(5), 0, dp(5), 0) },
      )
    }
    return bar
  }

  /* =========================================================================
   * P1 应用介绍页
   * ========================================================================= */

  private fun buildPage1(): View {
    val page = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(24), dp(36), dp(24), dp(24))
    }
    val wide = isWideLayout()

    if (!wide) {
      // 顶部：App 名 + 版本（宽屏由左侧固定品牌列展示，避免重复）
      page.addView(
        TextView(context).apply {
          text = res.getString(R.string.app_name)
          setTextColor(color(R.color.dh_text_primary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
          typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
          gravity = Gravity.CENTER
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
      )
      page.addView(
        TextView(context).apply {
          text = res.getString(R.string.onb_version)
          setTextColor(color(R.color.dh_text_secondary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
          gravity = Gravity.CENTER
        },
        lp(top = dp(8)),
      )

      page.addView(View(context), lp(weight = 1f))
    }

    // 中央：Logo + 副标语（宽屏 Logo 由品牌列展示，右侧仅保留副标语与底部按钮）
    val center = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    if (!wide) center.addView(buildLogo(), LinearLayout.LayoutParams(dp(120), dp(120)))
    center.addView(
      TextView(context).apply {
        text = "让 AI 掌控你的设备"
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER
      },
      lp(top = if (wide) 0 else dp(28)),
    )
    page.addView(
      center,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    // 平板横屏：P1 右侧用白底圆角卡片承载功能介绍，确保文字清晰可见（见规划 onboarding-p1-wide-intro-and-ratio）
    if (wide) {
      val introCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(color(R.color.dh_surface), dp(16))
        setPadding(dp(18), dp(16), dp(18), dp(16))
      }
      introCard.addView(
        TextView(context).apply {
          text = "为你带来"
          setTextColor(color(R.color.dh_text_secondary))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        },
        lp(),
      )
      listOf("终端指令执行", "设备级管理能力", "本地优先，隐私安全").forEachIndexed { index, feature ->
        val row = LinearLayout(context).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
          TextView(context).apply {
            text = "✓"
            setTextColor(color(R.color.dh_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
          },
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ),
        )
        row.addView(
          TextView(context).apply {
            text = feature
            setTextColor(color(R.color.dh_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
          },
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ).apply {
            leftMargin = dp(10)
          },
        )
        introCard.addView(row, lp(top = if (index == 0) dp(12) else dp(10)))
      }
      page.addView(introCard, lp(top = dp(28)))
    }

    page.addView(View(context), lp(weight = 1f))

    // 底部：下一步
    page.addView(
      primaryButton(res.getString(R.string.onb_next)) {
        viewPager.setCurrentItem(1, true)
      },
      lp(),
    )
    return page
  }

  /** 真实 App 图标（蓝渐变玻璃质感，颜色来自 bg_icon_gradient + 白色 D 前景）。 */
  private fun buildLogo(): View =
    ImageView(context).apply {
      setImageResource(R.mipmap.ic_launcher)
      contentDescription = "deepseek HARNESS"
      scaleType = ImageView.ScaleType.FIT_CENTER
    }

  /* =========================================================================
   * P2 隐私协议页（远程拉取 + 强制确认）
   * ========================================================================= */

  private fun buildPage2(): View {
    val page = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), dp(28), dp(24), dp(24))
    }

    // 标题：锁图标 + 「用户协议」
    val header = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(
      LockIconView(context).apply { setLockColor(color(R.color.dh_primary)) },
      LinearLayout.LayoutParams(dp(26), dp(26)),
    )
    header.addView(
      TextView(context).apply {
        text = RemotePolicyLoader.POLICY_TITLE
        setTextAppearance(R.style.Widget_Dsh_TitleText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
      },
      lp(left = dp(14)),
    )
    page.addView(header, lp())

    page.addView(
      TextView(context).apply {
        text = "在使用前，请先阅读并同意以下条款"
        setTextAppearance(R.style.Widget_Dsh_BodyText)
      },
      lp(top = dp(8)),
    )

    // 协议正文卡片：白底圆角。
    // 平板两栏时协议正文由右侧外层 ScrollView 统一滚动（内部不再套 ScrollView）；
    // 手机竖屏单栏保留内部滚动并让卡片占满剩余高度（防小屏溢出）。
    val wideP2 = isWideLayout()
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = roundedBg(color(R.color.dh_surface), dp(16))
    }
    val bodyContainer: ViewGroup =
      if (wideP2) {
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
      } else {
        ScrollView(context)
      }.apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
    val policyWrap = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    policySpinner = ProgressBar(context, null, android.R.attr.progressBarStyle).apply {
      visibility = View.VISIBLE
      isIndeterminate = true
      indeterminateTintList = ColorStateList.valueOf(color(R.color.dh_primary))
    }
    policyWrap.addView(policySpinner, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
      gravity = Gravity.CENTER_HORIZONTAL
    })
    policyBody = TextView(context).apply {
      text = "加载中…"
      setTextColor(color(R.color.dh_text_secondary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setLineSpacing(0f, 1.4f)
    }
    policyWrap.addView(
      policyBody,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
      },
    )
    bodyContainer.addView(
      policyWrap,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    card.addView(
      bodyContainer,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if (wideP2) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
        if (wideP2) 0f else 1f,
      ),
    )
    page.addView(
      card,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if (wideP2) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
        if (wideP2) 0f else 1f,
      ).apply {
        topMargin = dp(20)
        bottomMargin = dp(16)
      },
    )

    // 链接：查看全部协议（dh_link 蓝 + 下划线）
    val link = TextView(context).apply {
      text = res.getString(R.string.onb_view_full_policy)
      setTextAppearance(R.style.Widget_Dsh_LinkText)
      setPadding(dp(4), dp(4), dp(4), dp(4))
      setOnClickListener { RemotePolicyLoader.openFullPolicy(context) }
    }
    val span = SpannableString(link.text)
    span.setSpan(UnderlineSpan(), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    link.text = span
    page.addView(link, lp(bottom = dp(20)))

    // 底部：同意并继续（未同意前禁止进入 P3）
    page.addView(
      primaryButton(res.getString(R.string.onb_agree_continue)) {
        viewPager.setCurrentItem(2, true)
      },
      lp(),
    )
    return page
  }

  /* =========================================================================
   * P3 权限申请页
   * ========================================================================= */

  private fun buildPage3(): View {
    // 平板横屏/宽幅 → 左右分栏的品牌 + 权限卡片高保真布局；手机竖屏走下方原生纵向布局
    if (isWideLayout()) return buildPage3Wide()

    val page = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), dp(28), dp(24), dp(24))
    }

    page.addView(
      TextView(context).apply {
        text = "基础权限"
        setTextAppearance(R.style.Widget_Dsh_TitleText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
      },
      lp(),
    )
    page.addView(
      TextView(context).apply {
        text = "用于正常运行与通知，请授权以启用完整功能"
        setTextAppearance(R.style.Widget_Dsh_BodyText)
      },
      lp(top = dp(8)),
    )

    // 权限清单（可滚动区域，防小屏溢出）
    val scroll = ScrollView(context)
    val list = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    val storageRow = buildPermRow("存储权限", "访问设备存储，用于读写插件与配置文件")
    storageTag = storageRow.tag
    list.addView(storageRow.view, lp(top = dp(16)))

    val notifRow = buildPermRow("通知权限", "接收运行状态与更新提醒")
    notifTag = notifRow.tag
    list.addView(notifRow.view, lp(top = dp(14)))

    val normalRow = buildPermRow("基础网络权限", "INTERNET / VIBRATE / WAKE_LOCK 为普通权限，安装即自动授权")
    normalRow.tag.text = "已授权"
    applyTagPill(normalRow.tag, R.color.dh_success)
    list.addView(normalRow.view, lp(top = dp(14)))

    scroll.addView(
      list,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    page.addView(
      scroll,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
        bottomMargin = dp(16)
      },
    )

    permAction = LoadingButton(context).apply {
      setPrimaryStyle()
      setLabel(res.getString(R.string.onb_get_permission))
      setOnClick {
        if (viewModel.storageGranted.value && viewModel.notificationGranted.value) {
          viewPager.setCurrentItem(3, true)
        } else {
          setLoading(true)
          onRequestPermissions()
        }
      }
    }
    page.addView(permAction, lp())
    return page
  }

  /* =========================================================================
   * P3 平板横屏/宽幅：左右分栏（左品牌区 + 右白色权限卡片）
   * ========================================================================= */

  private fun buildPage3Wide(): View {
    // 仅返回「白色圆角权限卡片」作为右侧内容区；左侧品牌区由外层 brandedLayout 统一提供
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = roundedBg(color(R.color.dh_surface), dp(20))
      setPadding(dp(24), dp(20), dp(24), dp(20))
    }

    // 卡片表头：基础权限 + 右侧下拉箭头
    val header = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(
      TextView(context).apply {
        text = "基础权限"
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
      },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    card.addView(header, lp())

    // 3 条真实权限项
    val storage = buildPermRowWide("存储权限", "访问设备存储", R.drawable.ic_perm_storage)
    storageTag = storage.tag
    card.addView(storage.view, lp(top = dp(18)))

    val notif = buildPermRowWide("通知权限", "接收运行状态提醒", R.drawable.ic_perm_notification)
    notifTag = notif.tag
    card.addView(notif.view, lp(top = dp(12)))

    val network = buildPermRowWide("基础网络权限", "联网访问", R.drawable.ic_perm_network)
    network.tag.text = "已授权"
    applyTagPill(network.tag, R.color.dh_success)
    card.addView(network.view, lp(top = dp(12)))

    // 卡片右下角：细描边 LoadingButton——点击直接触发系统授权；权限齐后变「下一步」
    permAction = LoadingButton(context).apply {
      setLabel(res.getString(R.string.onb_get_permission))
      setOnClick {
        if (viewModel.storageGranted.value && viewModel.notificationGranted.value) {
          viewPager.setCurrentItem(3, true)
        } else {
          setLoading(true)
          onRequestPermissions()
        }
      }
    }
    // 卡片最底部：全宽长条按钮（撑满右侧权限卡片），与上方权限行保持合理间距
    card.addView(
      permAction,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(20)
      },
    )

    return card
  }

  /** 平板横屏权限行：左侧线性小图标 + 名称/说明 + 状态 tag + 右向箭头。 */
  private fun buildPermRowWide(title: String, desc: String, iconRes: Int): PermissionRow {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(
      ImageView(context).apply {
        setImageResource(iconRes)
        contentDescription = null
      },
      LinearLayout.LayoutParams(dp(22), dp(22)),
    )
    val texts = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.START
    }
    texts.addView(
      TextView(context).apply {
        text = title
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      },
      lp(),
    )
    texts.addView(
      TextView(context).apply {
        text = desc
        setTextColor(color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      },
      lp(top = dp(2)),
    )
    row.addView(
      texts,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = dp(12)
      },
    )
    val tag = createStatusTag("未授权", false)
    row.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return PermissionRow(row, tag)
  }

  /** 权限行卡片（白底圆角）：标题 + 右侧状态标签 + 说明。 */
  private fun buildPermRow(title: String, desc: String): PermissionRow {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = roundedBg(color(R.color.dh_surface), dp(16))
      setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    val line = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    line.addView(
      TextView(context).apply {
        text = title
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    val tag = createStatusTag("未授权", false)
    line.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    row.addView(line, lp())

    row.addView(
      TextView(context).apply {
        text = desc
        setTextColor(color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      },
      lp(top = dp(6)),
    )
    return PermissionRow(row, tag)
  }

  private class PermissionRow(val view: View, val tag: TextView)

  /* =========================================================================
   * P4 高级权限页（权限模式选择：普通 / 高级，进入软件后再获取）
   * ========================================================================= */

  private fun buildPage4(): View {
    val page = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), dp(28), dp(24), dp(24))
    }

    page.addView(
      TextView(context).apply {
        text = "普通用户权限"
        setTextAppearance(R.style.Widget_Dsh_TitleText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
      },
      lp(),
    )
    page.addView(
      TextView(context).apply {
        text = "选择权限模式，进入软件后可自动获取对应状态"
        setTextAppearance(R.style.Widget_Dsh_BodyText)
      },
      lp(top = dp(8)),
    )

    // 三大操作项整合进一个圆角白底卡片（宽度不限，MATCH_PARENT + 内边距，天然适配平板/横屏）
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = roundedBg(color(R.color.dh_surface), dp(20))
      setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    // 选项一：普通权限（描边样式，与其余两项一致；安装即自动授权，点击仅确认所选模式）
    val normalOption = LoadingButton(context).apply {
      setLabel("普通权限")
      setOnClick { viewModel.setMode(MODE_NORMAL) }
    }
    card.addView(normalOption, lp(top = dp(12), height = dp(48)))

    // 选项二：授权 SHIZUKU（左按钮占满 + 右状态胶囊）
    val shizukuRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    shizukuStatus = createStatusTag(
      shizukuStatusText(viewModel.shizukuState.value),
      true,
    ).also {
      applyTagPill(it, shizukuPillColor(viewModel.shizukuState.value))
    }
    shizukuBtn = LoadingButton(context).apply {
      setLabel(res.getString(R.string.onb_auth_shizuku))
      setOnClick { onShizukuClick() }
    }
    shizukuRow.addView(
      shizukuBtn,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
    )
    shizukuRow.addView(
      shizukuStatus,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp(12)
      },
    )
    card.addView(shizukuRow, lp(top = dp(12), height = dp(48)))

    // 选项三：获取 ROOT（左按钮占满 + 右状态胶囊）
    val rootRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    rootStatus = createStatusTag(
      rootStatusText(viewModel.rootState.value, false),
      true,
    ).also {
      applyTagPill(it, rootPillColor(viewModel.rootState.value))
    }
    rootBtn = LoadingButton(context).apply {
      setLabel(res.getString(R.string.onb_check_root))
      setOnClick { onRootClick() }
    }
    rootRow.addView(
      rootBtn,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
    )
    rootRow.addView(
      rootStatus,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp(12)
      },
    )
    card.addView(rootRow, lp(top = dp(12), height = dp(48)))

    page.addView(card, lp(top = dp(28)))

    // 底部占位，把「下一步」推到页面最底部
    page.addView(View(context), lp(weight = 1f))

    // 底部：下一步（全宽主色长条，横贯页面底部作为完整操作栏）
    val p4Next = LoadingButton(context).apply {
      setPrimaryStyle()
      setLabel(res.getString(R.string.onb_next))
      setOnClick {
        val host = context as? LifecycleOwner
        if (host == null) {
          onFinish()
          return@setOnClick
        }
        setLoading(true)
        host.lifecycleScope.launch {
          savePermissionMode(context, viewModel.selectedMode.value)
          onFinish()
        }
      }
    }
    page.addView(p4Next, lp(height = dp(56)))
    return page
  }

  /* =========================================================================
   * 状态驱动 UI
   * ========================================================================= */

  private fun setStorageTag(granted: Boolean) {
    storageTag.text = if (granted) "已授权" else "未授权"
    applyTagPill(storageTag, if (granted) R.color.dh_success else R.color.dh_danger)
    updatePermButton()
  }

  private fun setNotifTag(granted: Boolean) {
    notifTag.text = if (granted) "已授权" else "未授权"
    applyTagPill(notifTag, if (granted) R.color.dh_success else R.color.dh_danger)
    updatePermButton()
  }

  /** Shizuku 状态 → 胶囊底主色：已授权绿 / 未授权红 / 服务未运行灰。 */
  private fun shizukuPillColor(state: OnboardingViewModel.ShizukuState): Int = when (state) {
    OnboardingViewModel.ShizukuState.GRANTED -> R.color.dh_success
    OnboardingViewModel.ShizukuState.NOT_GRANTED -> R.color.dh_danger
    OnboardingViewModel.ShizukuState.UNAVAILABLE -> R.color.dh_text_secondary
  }

  /** Root 状态 → 胶囊底主色：已就绪绿 / 无 Root 红 / 检测中灰。 */
  private fun rootPillColor(state: OnboardingViewModel.RootState): Int = when (state) {
    OnboardingViewModel.RootState.AVAILABLE -> R.color.dh_success
    OnboardingViewModel.RootState.UNAVAILABLE -> R.color.dh_danger
    OnboardingViewModel.RootState.CHECKING -> R.color.dh_text_secondary
  }

  /* =========================================================================
   * P4 高级权限：Shizuku 授权 / Root 检测
   * ========================================================================= */

  /** Shizuku 状态文案。 */
  private fun shizukuStatusText(state: OnboardingViewModel.ShizukuState): String = when (state) {
    OnboardingViewModel.ShizukuState.UNAVAILABLE -> "服务未运行"
    OnboardingViewModel.ShizukuState.NOT_GRANTED -> "未授权"
    OnboardingViewModel.ShizukuState.GRANTED -> "已授权"
  }

  /** Root 状态文案（CHECKING 且未手动触发检测时显示「未检测」，见 PITFALLS §11）。 */
  private fun rootStatusText(state: OnboardingViewModel.RootState, running: Boolean): String = when (state) {
    OnboardingViewModel.RootState.CHECKING -> if (running) "检测中…" else "未检测"
    OnboardingViewModel.RootState.AVAILABLE -> "已就绪"
    OnboardingViewModel.RootState.UNAVAILABLE -> "无 Root"
  }

  /** 依据 Shizuku 状态刷新卡片：状态文本/颜色、授权按钮 loading 与显隐。 */
  private fun renderShizuku(state: OnboardingViewModel.ShizukuState) {
    if (::shizukuStatus.isInitialized) {
      shizukuStatus.text = shizukuStatusText(state)
      applyTagPill(shizukuStatus, shizukuPillColor(state))
    }
    if (::shizukuBtn.isInitialized) {
      shizukuBtn.setLoading(false)
      // 已授权无需再请求授权，隐藏按钮
      shizukuBtn.visibility = if (state == OnboardingViewModel.ShizukuState.GRANTED) View.GONE else View.VISIBLE
    }
  }

  /** 依据 Root 状态刷新卡片：状态文本/颜色；终态时复位检测 loading（PITFALLS §11）。 */
  private fun renderRoot(state: OnboardingViewModel.RootState) {
    if (::rootStatus.isInitialized) {
      rootStatus.text = rootStatusText(state, rootCheckRunning)
      applyTagPill(rootStatus, rootPillColor(state))
    }
    // 检测到达终态才复位按钮（初始 CHECKING 由 rootCheckRunning=false 保证不误显示 loading）
    if (state == OnboardingViewModel.RootState.AVAILABLE || state == OnboardingViewModel.RootState.UNAVAILABLE) {
      rootCheckRunning = false
      if (::rootBtn.isInitialized) rootBtn.setLoading(false)
    }
  }

  /** 【授权 Shizuku】点击：服务未运行提示；否则发起授权并显示按钮 loading，结果经 collect 复位。 */
  private fun onShizukuClick() {
    val state = viewModel.shizukuState.value
    if (state == OnboardingViewModel.ShizukuState.UNAVAILABLE) {
      Toast.makeText(context, "请先启动 Shizuku 服务", Toast.LENGTH_SHORT).show()
      return
    }
    if (state == OnboardingViewModel.ShizukuState.GRANTED) return
    if (::shizukuBtn.isInitialized) shizukuBtn.setLoading(true)
    val launched = viewModel.requestShizukuPermission()
    if (!launched) {
      if (::shizukuBtn.isInitialized) shizukuBtn.setLoading(false)
      Toast.makeText(context, "请先启动 Shizuku 服务", Toast.LENGTH_SHORT).show()
    }
  }

  /** 【检测 Root】点击：置检测标志与按钮 loading，结果经终态 collect 复位。 */
  private fun onRootClick() {
    if (rootCheckRunning) return
    rootCheckRunning = true
    if (::rootBtn.isInitialized) rootBtn.setLoading(true)
    viewModel.checkRoot()
  }

  /* =========================================================================
   * 通用组件
   * ========================================================================= */

  /** 主按钮：蓝渐变胶囊 + 白色文字 + 全圆角（无四边阴影）。 */
  private fun primaryButton(text: String, onClick: () -> Unit): Button =
    Button(context, null, 0, R.style.Widget_Dsh_Button_Primary).apply {
      this.text = text
      background = ContextCompat.getDrawable(context, R.drawable.btn_primary_gradient)
      setTextColor(color(R.color.dh_on_primary))
      setOnClickListener { onClick() }
    }

  /** 圆角矩形背景 drawable（颜色一律来自 R.color）。 */
  private fun roundedBg(
    color: Int,
    radiusPx: Int,
    strokeColor: Int? = null,
    strokeWidthDp: Int = 1,
  ): GradientDrawable =
    GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = radiusPx.toFloat()
      setColor(color)
      if (strokeColor != null) setStroke(strokeWidthDp, strokeColor)
    }

  /** LinearLayout 布局参数快捷构造。 */
  private fun lp(
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    weight: Float = 0f,
    top: Int = 0,
    bottom: Int = 0,
    left: Int = 0,
    right: Int = 0,
  ): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height, weight).apply {
      setMargins(left, top, right, bottom)
    }

  private fun color(id: Int): Int = ContextCompat.getColor(context, id)

  /** 横屏 / 平板宽屏判定（与 MainActivity.isWideLayout 一致）。 */
  private fun isWideLayout(): Boolean {
    val c = res.configuration
    val landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE
    val wideEnough = c.screenWidthDp >= 600
    val tablet = c.smallestScreenWidthDp >= 600
    return landscape || wideEnough || tablet
  }

  private fun dp(value: Int): Int = (value * res.displayMetrics.density).toInt()

  /** 通用状态胶囊：白字 + 圆角实心底（granted=绿 / 否则=红）。 */
  private fun createStatusTag(text: String, granted: Boolean): TextView =
    TextView(context).apply {
      this.text = text
      setTextColor(color(R.color.dh_on_primary))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setPadding(dp(10), dp(4), dp(10), dp(4))
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(color(if (granted) R.color.dh_success else R.color.dh_danger))
      }
    }

  /** 动态刷新胶囊：文字固定白色，仅切换背景主色。 */
  private fun applyTagPill(tag: TextView, bgColorRes: Int) {
    tag.setTextColor(color(R.color.dh_on_primary))
    (tag.background as? GradientDrawable)?.setColor(color(bgColorRes))
  }

  /**
   * 左品牌区：真实应用图标 + 标题/版本/副标题，横向排布、垂直居中。
   * 作为平板横屏两栏布局的左侧统一组件（P1-P4 复用）。
   */
  private fun buildBrandPanel(): View {
    val brand = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }
    brand.addView(
      ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher)
        contentDescription = res.getString(R.string.app_name)
        scaleType = ImageView.ScaleType.FIT_CENTER
      },
      LinearLayout.LayoutParams(dp(64), dp(64)),
    )
    val texts = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.START
    }
    texts.addView(
      TextView(context).apply {
        text = res.getString(R.string.app_name)
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
      },
      lp(),
    )
    texts.addView(
      TextView(context).apply {
        text = res.getString(R.string.onb_version)
        setTextColor(color(R.color.dh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      },
      lp(top = dp(6)),
    )
    texts.addView(
      TextView(context).apply {
        text = "让 AI 掌控你的设备"
        setTextColor(color(R.color.dh_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      },
      lp(top = dp(18)),
    )
    brand.addView(
      texts,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = dp(16)
      },
    )
    return brand
  }

  /** 宽屏：仅对右侧内容页包竖向 ScrollView，实现内容区独立纵向滚动（左品牌列固定、不随翻页滑动）。 */
  private fun pageContent(content: View): View =
    ScrollView(context).apply {
      addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      isFillViewport = true
      isVerticalScrollBarEnabled = false
      setBackgroundColor(color(R.color.dh_background))
    }

  /** 静态页适配器：每页一个预构建 View，viewType 即页码。 */
  private class PagesAdapter(private val pages: List<View>) :
    RecyclerView.Adapter<PagesAdapter.Holder>() {

    class Holder(val view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = pages.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
      val page = pages[viewType]
      // ViewPager2 硬性要求每个 page 根视图宽高均为 MATCH_PARENT，
      // 否则抛 "Pages must fill the whole ViewPager2 (use match_parent)" 崩溃。
      page.layoutParams = RecyclerView.LayoutParams(
        RecyclerView.LayoutParams.MATCH_PARENT,
        RecyclerView.LayoutParams.MATCH_PARENT,
      )
      return Holder(page)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit
  }

  /** 锁图标（Canvas 手绘，无需 emoji/额外资源）。 */
  private class LockIconView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
    }
    private var lockColor = 0

    fun setLockColor(color: Int) {
      lockColor = color
      invalidate()
    }

    override fun onDraw(canvas: Canvas) {
      val s = minOf(width, height).toFloat()
      if (s <= 0 || lockColor == 0) return
      val cx = width / 2f
      val cy = height / 2f
      paint.color = lockColor
      paint.strokeWidth = s * 0.10f
      // 提环
      canvas.drawArc(cx - s * 0.20f, cy - s * 0.34f, cx + s * 0.20f, cy + s * 0.02f, 180f, 180f, false, paint)
      // 锁体
      canvas.drawRoundRect(cx - s * 0.34f, cy - s * 0.14f, cx + s * 0.34f, cy + s * 0.38f, s * 0.10f, s * 0.10f, paint)
      // 锁孔
      paint.style = Paint.Style.FILL
      canvas.drawCircle(cx, cy + s * 0.14f, s * 0.06f, paint)
    }
  }
}
