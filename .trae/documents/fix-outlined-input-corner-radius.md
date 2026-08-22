# 修复 OutlinedBox 输入框圆角不一致导致边框变形的计划

## Summary
`dialog_rounded_input.xml` 中 `TextInputLayout` 四个角的 `app:boxCornerRadius*` 值不一致（`BottomStart=50dp`，其余 `35dp`），导致 Material OutlinedBox 描边路径在相邻角相接处圆心/半径突变、切线不连续，出现视觉扭曲。修复目标：**统一四角圆角**，让描边平滑；并顺带优化「外层 padding16dp + 输入框 margin16dp」的重叠间距。

## Current State Analysis
当前文件：`/workspace/dsh-mobile-apk-yoyo/app/src/main/res/layout/dialog_rounded_input.xml`

- 根布局 `LinearLayoutCompat`：`android:padding="16dp"`。
- 子控件 `TextInputLayout`：`android:layout_margin="16dp"`，且分别设置了
  `boxCornerRadiusTopStart/TopEnd/BottomEnd="35dp"`、`boxCornerRadiusBottomStart="50dp"`。

由此产生两个问题：
1. **圆角不一致**（`BottomStart=50` vs 其余 `35`）→ OutlinedBox 描边在相邻角圆弧相接处因半径突变、圆心不重合而扭曲（见 Summary 的原因）。
2. **双层缩进**：外层 padding 16 + 内层 margin 16 = 四周 32dp，竖直方向浪费空间，居中感变差，且 `box` 浮动标签与外框间距不一致。

调用方：`Ui.kt#roundedInputView` inflate 该布局后 `setView()` 到弹窗；弹窗内容区本身已有内容 padding，故此处双层缩进冗余。

## Proposed Changes

### 文件 1：`app/src/main/res/layout/dialog_rounded_input.xml`
采用**统一四角圆角**，用单一 `app:boxCornerRadius="35dp"`（一次设置四角，最干净，避免逐角手写不一致），并移除冗余的 `android:layout_margin="16dp"`（外层 `padding` 与调用方弹窗 padding 已保证间距）。

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.appcompat.widget.LinearLayoutCompat xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:gravity="center_vertical|start"
    android:padding="16dp">

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
        app:boxCornerRadius="35dp">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/ti"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:singleLine="true"
            android:hint="名称"
            android:textStyle="bold" />
    </com.google.android.material.textfield.TextInputLayout>
</androidx.appcompat.widget.LinearLayoutCompat>
```

### 文件 2：`app/src/main/java/com/yoyo/dshmobile/shell/ui/Ui.kt`
同步更新 `roundedInputView` 的 KDoc 措辞：由「圆角 35/35/35/50dp」改为「统一圆角 35dp」，避免文档与实现不符。

### 排版优化建议（不强制改动，仅建议）
- **方案 A（本计划采用）**：去掉输入框 `margin`，只保留外层 `padding=16dp`，四角统一 `boxCornerRadius=35dp`。间距单一来源，最稳。
- **方案 B**：若希望输入框在外层 padding 基础上再收紧一些，可去掉外层 `padding`、保留输入框负值 margin 逼近弹窗边距——但会增加耦合，不推荐。
- **前往选择**：单个控件用 `boxCornerRadius` 统一即可；仅当确有「某几个角需要不同弧度」的交互需求才用分角属性（并保证相邻角尽量接近）。

## Assumptions & Decisions
- 四角统一取 **35dp**（与用户其余三角一致；左下角由 50dp 收紧到 35dp 是本次修复点）。
- 用单一 `app:boxCornerRadius` 替代四个分角属性，是 Material 官方推荐的统一圆角写法。
- 保留外层 `padding=16dp` 作为通用缩进；移除内层 `margin` 以消除双层间距。

## Verification
- `cd /workspace/dsh-mobile-apk-yoyo && JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 PATH=$JAVA_HOME/bin:$PATH ./gradlew :app:assembleRelease --no-daemon` 编译通过、无 AAPT 报错。
- 运行时打开任一输入/密码弹窗（如开发者设置密码门），目视确认：
  - 输入框四角圆角一致（均为 35dp）、描边平滑无扭曲折点；
  - 输入框四周与外层间距合理（不再出现 32dp 的过宽双层缩进）。