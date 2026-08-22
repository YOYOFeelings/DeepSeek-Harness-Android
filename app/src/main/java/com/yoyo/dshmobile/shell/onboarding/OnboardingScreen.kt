package com.yoyo.dshmobile.shell.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yoyo.dshmobile.shell.onboarding.OnboardingViewModel.RootState
import com.yoyo.dshmobile.shell.onboarding.OnboardingViewModel.ShizukuState
import com.yoyo.dshmobile.shell.ui.theme.Brand
import com.yoyo.dshmobile.shell.ui.theme.C_Card
import com.yoyo.dshmobile.shell.ui.theme.C_Inactive
import com.yoyo.dshmobile.shell.ui.theme.C_Log
import com.yoyo.dshmobile.shell.ui.theme.C_Red
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/* =========================================================================
 * 视觉令牌（引导页专用，玻璃态 × 荧光青蓝）
 * ========================================================================= */
private val G_BgTop = Color(0xFF081014)   // 顶部：偏青黑的氛围
private val G_BgMid = Color(0xFF0C1218)
private val G_BgBottom = Color(0xFF05060A) // 底部：更深的黑，形成纵向渐变层次
private val BrandDeep = Color(0xFF00B8D9)
private val GlassGlow = Brand.copy(alpha = 0.35f)
private val TextPrimary = Color(0xFFEDF6F7)
private val TextSecondary = Color(0xFF9FB3B8)
private val TextFaint = Color(0xFF5F737A)
private val GlassCard = Brush.linearGradient(
  listOf(Color(0x24FFFFFF), Color(0x0DFFFFFF)),
)
private val GlassBorder = Brush.linearGradient(
  listOf(Color(0x59FFFFFF), Brand.copy(alpha = 0.28f), Color(0x14FFFFFF)),
)
private val BtnGrad = Brush.horizontalGradient(listOf(Brand, BrandDeep))

/** 页面背景：深黑→青黑纵向渐变。 */
private val PageBg = Brush.verticalGradient(listOf(G_BgTop, G_BgMid, G_BgBottom))

private val SHAPE_CARD = RoundedCornerShape(20.dp)

/** 隐私政策占位文本（≥300 字）。 */
private val PRIVACY_TEXT = """
亲爱的用户，感谢你选择并信任 Operit。在开始使用之前，请你仔细阅读并充分理解本隐私政策。我们深知个人信息对你的重要性，因此我们始终将保护你的隐私视为最高原则之一。

一、信息收集范围：我们仅在为你提供服务所必需的范围内收集信息，主要包括设备基础信息、你主动输入的内容以及你在使用功能过程中产生的操作日志。我们不会收集与你身份直接相关的敏感个人信息，除非得到你的明确同意。

二、信息使用目的：我们所收集的信息仅用于改进产品体验、保障服务安全以及向你呈现更贴合需求的功能，绝不会用于与上述无关的商业用途，更不会非法出售、出租或提供给任何第三方。

三、信息存储与安全：我们会采取加密、访问控制、多重身份验证等合理措施保护你的数据。数据仅保存在你的设备本地，未经你的授权不向服务器上传。请妥善保管你的设备，避免在设备丢失后造成不必要的风险。

四、信息共享原则：除法律另有规定或获得你的明确授权外，我们不会与任何第三方共享你的个人信息。若未来因业务需要对外合作，我们将单独向你说明并获得你的同意。

五、你的权利：你可以随时查阅、更正或删除你所提供的个人信息，也可以撤回此前授予的权限。我们承诺在收到你的请求后及时予以处理。

六、政策更新：我们可能根据产品功能的演进适当调整本政策，并在更新时通过应用内提示告知你。对重大变更，我们会寻求你的事先同意。

如你有任何疑问，欢迎随时通过应用内反馈渠道与我们联系。最后再次感谢你的信任与支持。
""".trimIndent()

/* =========================================================================
 * 引导页主框架
 * ========================================================================= */

/**
 * 改版引导页（Operit：深色玻璃 × 荧光青蓝，高级质感）。
 * P1 介绍 / P2 隐私协议（未同意锁定滑动）/ P3 基础权限 / P4 高级权限。
 *
 * @param onFinish 跳过并进入主页回调。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
  val viewModel: OnboardingViewModel = viewModel()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  val pagerState = rememberPagerState { 4 }
  // P2 未点击“同意并继续”前锁定手指滑动（state 持久化，避免重组重置锁定）。
  var agreed by remember { mutableStateOf(false) }

  // 收集状态
  val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
  val shizukuStatusText by viewModel.shizukuStatusText.collectAsStateWithLifecycle()
  val shizukuOutput by viewModel.shizukuOutput.collectAsStateWithLifecycle()
  val shizukuLoading by viewModel.shizukuLoading.collectAsStateWithLifecycle()
  val rootState by viewModel.rootState.collectAsStateWithLifecycle()
  val rootOutput by viewModel.rootOutput.collectAsStateWithLifecycle()
  val rootLoading by viewModel.rootLoading.collectAsStateWithLifecycle()
  val storageGranted by viewModel.storageGranted.collectAsStateWithLifecycle()
  val notificationGranted by viewModel.notificationGranted.collectAsStateWithLifecycle()

  // Shizuku 授权结果监听器：注册/注销由 DisposableEffect 管理。
  DisposableEffect(Unit) {
    val recipient = ShizukuHelper.addPermissionResultListener { requestCode, grantResult ->
      viewModel.onShizukuPermissionResult(requestCode, grantResult)
    }
    // 服务未运行/注册失败时返回 null，注销仅在非空时进行。
    onDispose { recipient?.unregister() }
  }

  var showSettingsDialog by remember { mutableStateOf(false) }

  fun syncPermissions() {
    val storage =
      context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
    val notif =
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    viewModel.updatePermissionStates(storage, notif)
  }

  val storageLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    syncPermissions()
    if (!granted) showSettingsDialog = true
  }

  val notificationLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    syncPermissions()
    if (!granted) showSettingsDialog = true
  }

  Box(modifier = Modifier.fillMaxSize().background(PageBg)) {
    Column(modifier = Modifier.fillMaxSize()) {
      HorizontalPager(
        state = pagerState,
        userScrollEnabled = agreed,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      ) { page ->
        when (page) {
          0 -> Page1(onNext = { scope.launch { pagerState.animateScrollToPage(1) } })
          1 -> Page2(
            onAgree = {
              agreed = true
              scope.launch { pagerState.animateScrollToPage(2) }
            },
          )
          2 -> Page3(
            storageGranted = storageGranted,
            notificationGranted = notificationGranted,
            onRequestClick = {
              storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
              notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
          )
          else -> Page4(
            shizukuState = shizukuState,
            shizukuStatusText = shizukuStatusText,
            shizukuOutput = shizukuOutput,
            shizukuLoading = shizukuLoading,
            rootState = rootState,
            rootOutput = rootOutput,
            rootLoading = rootLoading,
            viewModel = viewModel,
            onFinish = onFinish,
          )
        }
      }
      PageIndicator(
        current = pagerState.currentPage,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 22.dp),
      )
    }
  }

  if (showSettingsDialog) {
    AlertDialog(
      onDismissRequest = { showSettingsDialog = false },
      shape = RoundedCornerShape(28.dp),
      containerColor = C_Card.copy(alpha = 0.98f),
      title = { Text("需要权限", fontWeight = FontWeight.Bold, color = TextPrimary) },
      text = {
        Text(
          "存储权限与通知权限用于正常展示与通知核心功能。若你已关闭，请前往系统设置手动授权。",
          fontSize = 14.sp,
          color = TextSecondary,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          showSettingsDialog = false
          val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
          )
          try {
            context.startActivity(intent)
          } catch (_: Exception) {
            // 部分 ROM 无法打开设置页，静默忽略。
          }
        }) { Text("去设置", color = Brand) }
      },
      dismissButton = {
        TextButton(onClick = { showSettingsDialog = false }) { Text("取消", color = TextSecondary) }
      },
    )
  }
}

/* =========================================================================
 * 通用组件
 * ========================================================================= */

/** 底部圆点指示器：当前页放大 + 青蓝发光。 */
@Composable
private fun PageIndicator(current: Int, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(4) { index ->
      val selected = index == current
      val size by animateDpAsState(if (selected) 12.dp else 8.dp, label = "dotSize")
      Box(
        Modifier
          .size(size)
          .then(
            if (selected) Modifier.shadow(8.dp, CircleShape, ambientColor = GlassGlow, spotColor = GlassGlow)
            else Modifier
          )
          .clip(CircleShape)
          .background(if (selected) Brand else C_Inactive.copy(alpha = 0.5f)),
      )
    }
  }
}

/** 高级主按钮：渐变青蓝、圆角 16、点击缩放 0.95→1.0、发光。 */
@Composable
private fun MainButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "btnScale")
  Box(
    modifier = modifier
      .scale(scale)
      .shadow(14.dp, RoundedCornerShape(16.dp), ambientColor = Brand.copy(alpha = 0.12f), spotColor = GlassGlow),
  ) {
    Box(
      modifier = Modifier
        .height(54.dp)
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(BtnGrad)
        .alpha(if (enabled) 1f else 0.6f)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}

/** 副按钮：描边玻璃感。 */
@Composable
private fun OutlineButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "outlineScale")
  Box(
    modifier
      .scale(scale)
      .height(50.dp)
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .border(1.dp, Brand.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
      .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = Brand, fontSize = 15.sp)
  }
}

/** 玻璃卡片容器：半透明渐变 + 渐变描边 + 柔和投影。 */
@Composable
private fun GlassCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .shadow(18.dp, SHAPE_CARD, ambientColor = Color(0x22000000), spotColor = GlassGlow.copy(alpha = 0.10f))
      .clip(SHAPE_CARD)
      .background(GlassCard)
      .border(1.dp, GlassBorder, SHAPE_CARD)
      .padding(18.dp),
    content = content,
  )
}

/** 页面小标题 + 副标题。 */
@Composable
private fun PageHeader(title: String, subtitle: String) {
  Column {
    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp)
  }
}

/** 状态标签（AnimatedContent 淡入淡出）。 */
@Composable
private fun StatusTag(granted: Boolean) {
  val bg = if (granted) Brand.copy(alpha = 0.16f) else C_Red.copy(alpha = 0.16f)
  val fg = if (granted) Brand else C_Red
  AnimatedContent(targetState = granted, label = "permState") { g ->
    Box(
      Modifier
        .clip(RoundedCornerShape(100))
        .background(if (g) bg else (C_Red.copy(alpha = 0.16f)))
        .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
      Text(if (g) "已授权" else "未授权", fontSize = 13.sp, color = if (g) Brand else fg)
    }
  }
}

/** 80.dp 日志输出区：近纯黑 + 外层青蓝细边 + 打字机。 */
@Composable
private fun LogBox(text: String, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(Color(0xFF030507))
      .border(1.dp, Brand.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
      .padding(12.dp),
  ) {
    TypewriterText(text = text)
  }
}

/** 10.dp 状态指示灯（发光）。 */
@Composable
private fun StatusDot(granted: Boolean, error: Boolean = false) {
  val c = when {
    error -> C_Red
    granted -> Brand
    else -> C_Inactive
  }
  Box(
    Modifier
      .size(10.dp)
      .then(if (granted) Modifier.shadow(8.dp, CircleShape, ambientColor = c) else Modifier)
      .clip(CircleShape)
      .background(c),
  )
}

/** 日志打字机组合：LaunchedEffect(text) 变化时重置并逐字打印。 */
@Composable
private fun TypewriterText(text: String, modifier: Modifier = Modifier) {
  val visibleChars = remember { mutableIntStateOf(0) }
  LaunchedEffect(text) {
    visibleChars.intValue = 0
    var i = 1
    while (i <= text.length) {
      visibleChars.intValue = i
      i += 3
      delay(10)
    }
    visibleChars.intValue = text.length
  }
  Text(
    text = text.substring(0, visibleChars.intValue.coerceIn(0, text.length)),
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    color = C_Log,
    modifier = modifier,
  )
}

/* =========================================================================
 * P1 应用介绍页
 * ========================================================================= */
@Composable
private fun Page1(onNext: () -> Unit) {
  Box(Modifier.fillMaxSize().padding(24.dp)) {
    // 顶部：版本号
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(Brand),
      )
      Spacer(Modifier.width(8.dp))
      Text(
        "Operit v1.0",
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        color = TextPrimary,
      )
    }
    Column(
      Modifier.align(Alignment.Center),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      GlowLogo()
      Spacer(Modifier.height(28.dp))
      Text(
        "Operit",
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
      )
      Spacer(Modifier.height(8.dp))
      Text(
        "让AI掌控你的设备",
        fontSize = 16.sp,
        fontFamily = FontFamily.Monospace,
        color = Brand,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        "System・Terminal・Power",
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = TextFaint,
      )
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
      MainButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("下一步", fontSize = 16.sp) }
    }
  }
}

/** 发光 Logo：六边形 + 中央终端 `>_` 符号。 */
@Composable
private fun GlowLogo() {
  val glowLine = Brand.copy(alpha = 0.35f)
  Box(
    Modifier
      .size(120.dp)
      .shadow(28.dp, RoundedCornerShape(30.dp), ambientColor = Brand.copy(alpha = 0.25f), spotColor = GlassGlow),
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val side = size.minDimension
      val cx = size.width / 2f
      val cy = size.height / 2f
      val r = side / 2f

      // 背景柔光盘
      drawCircle(Brand.copy(alpha = 0.10f), radius = r * 0.98f, center = Offset(cx, cy))
      // 六边形描边（外层光晕 → 内层实体）
      val hexStroke = 2.2.dp.toPx()
      repeat(3) { i ->
        val width = hexStroke * (3 - i)
        val alpha = 0.06f + 0.10f * (i + 1)
        drawPath(
          path = hexagonPath(cx, cy, r * 0.86f),
          color = if (i == 2) BrandDeep else Brand.copy(alpha = alpha),
          style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
      }
      // 中央终端 `>_`：先光晕后亮线
      val chev = chevronPath(cx, cy, r * 0.62f)
      repeat(3) { i ->
        val width = 4.dp.toPx() * (3 - i)
        val alpha = 0.06f + 0.09f * (i + 1)
        drawPath(
          path = chev,
          color = if (i == 2) Brand else Brand.copy(alpha = alpha),
          style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
      }
      // 高光细线（核心）
      val coreW = 2.4.dp.toPx()
      drawPath(
        path = chev,
        color = Color.White.copy(alpha = 0.55f),
        style = Stroke(width = coreW, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
      // 下划线游标
      drawLine(
        color = Brand,
        start = Offset(cx - r * 0.42f, cy + r * 0.46f),
        end = Offset(cx + r * 0.42f, cy + r * 0.46f),
        strokeWidth = 2.4.dp.toPx(),
        cap = StrokeCap.Round,
      )
      // 底部两个发光点
      drawCircle(glowLine, radius = 3.dp.toPx(), center = Offset(cx - r * 0.5f, cy + r * 0.62f))
      drawCircle(glowLine, radius = 3.dp.toPx(), center = Offset(cx + r * 0.5f, cy + r * 0.62f))
    }
  }
}

private fun hexagonPath(cx: Float, cy: Float, r: Float): Path {
  val p = Path()
  for (i in 0 until 6) {
    val angle = Math.toRadians(-90.0 + i * 60.0)
    val x = cx + r * cos(angle).toFloat()
    val y = cy + r * sin(angle).toFloat()
    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
  }
  p.close()
  return p
}

private fun chevronPath(cx: Float, cy: Float, size: Float): Path {
  val p = Path()
  p.moveTo(cx - size * 0.38f, cy - size * 0.24f)
  p.lineTo(cx + size * 0.16f, cy)
  p.lineTo(cx - size * 0.38f, cy + size * 0.24f)
  return p
}

/* =========================================================================
 * P2 隐私政策页
 * ========================================================================= */
@Composable
private fun Page2(onAgree: () -> Unit) {
  Column(Modifier.fillMaxSize().padding(24.dp)) {
    // 锁图标 + 标题
    Row(verticalAlignment = Alignment.CenterVertically) {
      LockIcon()
      Spacer(Modifier.width(14.dp))
      Text("隐私协议", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    }
    Spacer(Modifier.height(8.dp))
    Text("在使用前，请先阅读并同意以下条款", fontSize = 14.sp, color = TextSecondary)
    Spacer(Modifier.height(20.dp))

    // 玻璃卡片包裹可滚动文本
    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(SHAPE_CARD)
        .background(GlassCard)
        .border(1.dp, GlassBorder, SHAPE_CARD)
        .padding(16.dp),
    ) {
      Text(
        PRIVACY_TEXT,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = TextPrimary,
        modifier = Modifier.verticalScroll(rememberScrollState()),
      )
    }
    Spacer(Modifier.height(20.dp))
    MainButton(onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
      Text("同意并继续", fontSize = 16.sp)
    }
  }
}

/** 锁图标：Canvas 绘制描边，无需 emoji。 */
@Composable
private fun LockIcon() {
  Canvas(Modifier.size(26.dp)) {
    val s = size.minDimension
    val stroke = s * 0.085f
    // 提环
    drawArc(
      color = Brand,
      startAngle = 180f,
      sweepAngle = 180f,
      useCenter = false,
      topLeft = Offset(s * 0.32f, s * 0.28f),
      size = Size(s * 0.36f, s * 0.32f),
      style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    // 锁体
    drawRoundRect(
      color = Brand,
      topLeft = Offset(s * 0.16f, s * 0.46f),
      size = Size(s * 0.68f, s * 0.42f),
      cornerRadius = CornerRadius(s * 0.09f),
      style = Stroke(width = stroke),
    )
    // 锁孔
    drawCircle(color = Brand, radius = s * 0.05f, center = Offset(s * 0.50f, s * 0.68f))
  }
}

/* =========================================================================
 * P3 基本权限申请页
 * ========================================================================= */
@Composable
private fun Page3(
  storageGranted: Boolean,
  notificationGranted: Boolean,
  onRequestClick: () -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(24.dp)) {
    PageHeader("基本权限", "用于正常运行与通知，请授权以启用完整功能")
    Spacer(Modifier.height(24.dp))
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      item {
        GlassCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("存储权限", fontSize = 15.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            StatusTag(granted = storageGranted)
          }
          Spacer(Modifier.height(6.dp))
          Text("访问设备存储，用于读写插件与配置文件", fontSize = 12.sp, color = TextSecondary)
        }
      }
      item {
        GlassCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("通知权限", fontSize = 15.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            StatusTag(granted = notificationGranted)
          }
          Spacer(Modifier.height(6.dp))
          Text("接收运行状态与更新提醒", fontSize = 12.sp, color = TextSecondary)
        }
      }
    }
    Spacer(Modifier.height(16.dp))
    MainButton(onClick = onRequestClick, modifier = Modifier.fillMaxWidth()) {
      Text("申请权限", fontSize = 16.sp)
    }
  }
}

/* =========================================================================
 * P4 高级权限获取页（Shizuku / Root）
 * ========================================================================= */
@Composable
private fun Page4(
  shizukuState: ShizukuState,
  shizukuStatusText: String,
  shizukuOutput: String,
  shizukuLoading: Boolean,
  rootState: RootState,
  rootOutput: String,
  rootLoading: Boolean,
  viewModel: OnboardingViewModel,
  onFinish: () -> Unit,
) {
  val context = LocalContext.current
  Column(Modifier.fillMaxSize().padding(24.dp)) {
    PageHeader("高级权限", "获取系统级能力，释放终端潜能")
    Spacer(Modifier.height(20.dp))
    Column(
      modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      // 卡片 A (Shizuku)
      GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusDot(granted = shizukuState == ShizukuState.GRANTED)
          Spacer(Modifier.width(10.dp))
          AnimatedContent(targetState = shizukuStatusText, label = "shizukuStatus") { text ->
            Text(
              text,
              fontSize = 15.sp,
              fontWeight = FontWeight.SemiBold,
              color = when (shizukuState) {
                ShizukuState.GRANTED -> Brand
                ShizukuState.NOT_GRANTED -> C_Red
                ShizukuState.UNAVAILABLE -> TextSecondary
              },
            )
          }
        }
        Spacer(Modifier.height(6.dp))
        Text("以系统进程身份执行命令，无需 Root", fontSize = 12.sp, color = TextSecondary)
        Spacer(Modifier.height(14.dp))
        if (shizukuState != ShizukuState.GRANTED) {
          Row {
            MainButton(
              onClick = {
                val launched = viewModel.requestShizukuPermission()
                if (!launched) {
                  Toast.makeText(
                    context,
                    "请先启动 Shizuku 服务",
                    Toast.LENGTH_SHORT,
                  ).show()
                }
              },
              modifier = Modifier.fillMaxWidth(),
            ) { Text("授权 Shizuku", fontSize = 15.sp) }
          }
        } else if (shizukuLoading) {
          Row {
            MainButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
              CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF002B33), strokeWidth = 2.dp)
            }
          }
        } else {
          Row {
            MainButton(onClick = { viewModel.runListPackages() }, modifier = Modifier.fillMaxWidth()) {
              Text("运行演示命令", fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            }
          }
        }
        Spacer(Modifier.height(12.dp))
        LogBox(text = shizukuOutput, modifier = Modifier.fillMaxWidth().height(80.dp))
      }

      // 卡片 B (Root)
      GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusDot(granted = rootState == RootState.AVAILABLE, error = rootState == RootState.UNAVAILABLE)
          Spacer(Modifier.width(10.dp))
          AnimatedContent(targetState = rootState, label = "rootStatus") { state ->
            Text(
              when (state) {
                RootState.CHECKING -> "检测中…"
                RootState.AVAILABLE -> "Root 可用"
                RootState.UNAVAILABLE -> "无 Root"
              },
              fontSize = 15.sp,
              fontWeight = FontWeight.SemiBold,
              color = when (state) {
                RootState.AVAILABLE -> Brand
                RootState.UNAVAILABLE -> C_Red
                RootState.CHECKING -> TextSecondary
              },
            )
          }
        }
        Spacer(Modifier.height(6.dp))
        Text("直接执行 su 命令，绕过沙箱限制", fontSize = 12.sp, color = TextSecondary)
        Spacer(Modifier.height(14.dp))
        if (rootLoading) {
          Row {
            MainButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
              CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF002B33), strokeWidth = 2.dp)
            }
          }
        } else {
          Row {
            MainButton(
              onClick = { viewModel.checkRoot() },
              modifier = Modifier.fillMaxWidth(),
            ) { Text("检测 Root", fontSize = 15.sp) }
          }
        }
        if (rootState == RootState.AVAILABLE && !rootLoading) {
          Spacer(Modifier.height(10.dp))
          Row {
            MainButton(
              onClick = { viewModel.runLsData() },
              modifier = Modifier.fillMaxWidth(),
            ) { Text("执行 Root 命令", fontSize = 15.sp, fontFamily = FontFamily.Monospace) }
          }
        }
        Spacer(Modifier.height(12.dp))
        LogBox(text = rootOutput, modifier = Modifier.fillMaxWidth().height(80.dp))
      }
    }

    Spacer(Modifier.height(20.dp))
    OutlineButton(
      text = "跳过并进入主页",
      onClick = onFinish,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}