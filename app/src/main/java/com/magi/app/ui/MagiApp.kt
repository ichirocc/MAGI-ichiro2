package com.magi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagiApp(vm: MagiViewModel = viewModel(), themeMode: Int = 0, onThemeMode: (Int) -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var editingCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var oneHand by rememberSaveable { mutableStateOf(false) }
    var wishConfirm by remember { mutableStateOf(0) } // >0: 担当外件数の確認ダイアログ表示

    val openJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                }
                if (text != null) vm.load(text)
            }
        }
    }

    val openCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                }
                if (text != null) vm.importCsv(text)
            }
        }
    }

    val saveJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.Default) { vm.exportJson() }
                if (json != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                }
            }
        }
    }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = withContext(Dispatchers.Default) { vm.exportCsv() }
                if (csv != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                }
            }
        }
    }

    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) { vm.exportLogs() }
                if (text != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                }
            }
        }
    }

    val saveLogJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) { vm.exportLogsJson() }
                if (text != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                }
            }
        }
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 許可有無に関わらず計算は継続。許可時のみ完了通知が表示される。 */ }
    val onBgOptimize: () -> Unit = {
        notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        vm.runInBackground()
    }

    var tab by rememberSaveable { mutableStateOf(0) }
    val loadSample: () -> Unit = {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val asset = runCatching { ctx.assets.open("sample_state_v6.json") }.getOrElse { ctx.assets.open("sample_state.json") }
                    asset.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text != null) vm.load(text)
        }
    }
    val openJson: () -> Unit = { openJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }

    Scaffold(
        topBar = { MagiTopBar(ui) },
        bottomBar = {
            Column {
                if (ui.loaded) BottomCommandBar(ui, vm)
                MagiBottomNav(tab) { tab = it }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .padding(top = if (oneHand) 120.dp else 0.dp) // 片手モード: 内容を親指の届く下方へ
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            if (!ui.loaded && tab != 4) {
                EmptyStateCard(onOpen = openJson, onSample = loadSample)
            } else when (tab) {
                0 -> {
                    StatusHero(ui)
                    CopilotCard(ui, onGoEdit = { tab = 2 })
                    SummaryCard(ui)
                    ActionCard(ui, vm, onBgOptimize = onBgOptimize)
                    AlternativesCard(ui, onApply = { vm.applyAlternative(it) })
                }
                1 -> {
                    val openEditor: (Int, Int) -> Unit = { i, j ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        editingCell = i to j
                    }
                    WishApplyCard(ui, onApply = {
                        val oos = vm.wishOutOfScopeCount()
                        if (oos > 0) wishConfirm = oos else vm.applyWishes(false)
                    })
                    ScheduleGrid(ui, onCellClick = openEditor)
                    StaffCalendarCard(ui, onCellClick = openEditor)
                }
                2 -> {
                    SetupGuideCard(ui, vm)
                    Ws1Card(ui, vm)
                    ConstraintsCard(ui, vm)
                    NeedDayCard(ui, vm)
                    StaffRangeCard(ui, vm)
                    WishCard(ui, vm)
                }
                3 -> {
                    V6DashboardCard(ui.v6)
                    OverviewDashboard(ui)
                    CheckSummaryView(ui)
                    FlagsView(ui, vm)
                    BreakdownCard(ui)
                    ColorSettingsView(ui)
                }
                else -> {
                    AppearanceCard(themeMode, onThemeMode, oneHand) { oneHand = it }
                    ShiftColorCard(ui, vm)
                    DataActionsCard(
                        ui = ui,
                        onOpenJson = openJson,
                        onSample = loadSample,
                        onSaveJson = { saveJsonLauncher.launch("magi_state_${System.currentTimeMillis()}.json") },
                        onOpenCsv = { openCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                        onSaveCsv = { saveCsvLauncher.launch("magi_schedule_${System.currentTimeMillis()}.csv") },
                        onCheck = { vm.refreshCheck() },
                    )
                    SettingsCard(ui, vm)
                    OperatorLogView(ui)
                    LogsCard(
                        ui = ui,
                        onExportLog = { saveLogLauncher.launch("magi_log_${System.currentTimeMillis()}.txt") },
                        onExportJson = { saveLogJsonLauncher.launch("magi_log_${System.currentTimeMillis()}.json") },
                    )
                }
            }
            ui.message?.let { MessageBar(it) }
            Spacer(Modifier.height(12.dp)) // 下部コマンドバー分の余白
        }
        val cell = editingCell
        if (cell != null) {
            ShiftPickerSheet(
                ui = ui,
                vm = vm,
                cell = cell,
                onPick = { k ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.setCell(cell.first, cell.second, k)
                    editingCell = null
                },
                onDismiss = { editingCell = null },
            )
        }
        if (wishConfirm > 0) {
            AlertDialog(
                onDismissRequest = { wishConfirm = 0 },
                title = { Text("担当外の希望を含めますか？") },
                text = { Text("担当できないグループの希望が ${wishConfirm} 件あります。含めて反映すると担当不可の配置になります（違反として検出されます）。") },
                confirmButton = {
                    TextButton(onClick = { vm.applyWishes(true); wishConfirm = 0 }) { Text("含めて反映") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.applyWishes(false); wishConfirm = 0 }) { Text("担当内のみ反映") }
                },
            )
        }
    }
}

@Composable
private fun SetupGuideCard(ui: UiState, vm: MagiViewModel) {
    if (!ui.loaded) return
    val c = vm.setupCounts()
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("入力ガイド（月次の手順）", style = MaterialTheme.typography.titleMedium)
            GuideRow("① 基本情報", "${c.days}日 / ${c.staff}名 / ${c.shifts}シフト / ${c.groups}群", c.days > 0 && c.staff > 0 && c.shifts > 0)
            GuideRow("② 希望シフト", "${c.wishes}件", c.wishes > 0)
            GuideRow("③ 必要人数", if (c.needDay > 0) "${c.needDay}件（個別指定）" else "シフト既定のみ", true)
            GuideRow("④ 制約", "${c.constraints}件", true)
            GuideRow("⑤ 個人の回数範囲", "${c.ranges}件", true)
            val next = when {
                c.staff == 0 || c.shifts == 0 -> "基本情報（スタッフ／シフト）を整えましょう。"
                c.wishes == 0 -> "次に『希望シフト』を登録すると満足度が上がります。"
                else -> "準備OK。ホームの『最適化』で勤務表を作成しましょう。"
            }
            Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text("次の一手: $next", color = cs.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun GuideRow(label: String, value: String, done: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (done) "✓" else "・", color = if (done) cs.primary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AlternativesCard(ui: UiState, onApply: (Int) -> Unit) {
    if (ui.alternatives.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("他の案（${ui.alternatives.size}）", style = MaterialTheme.typography.titleMedium)
            Text("並列探索で見つかった、採用案以外の候補です。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.alternatives.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { onApply(i) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text("採用") }
                }
            }
        }
    }
}

@Composable
private fun WishApplyCard(ui: UiState, onApply: () -> Unit) {
    if (!ui.loaded) return
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("希望シフトを反映", fontWeight = FontWeight.Bold)
                Text("登録済みの希望を勤務表へ上書きします（元に戻せます）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onApply, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text("反映") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftPickerSheet(
    ui: UiState,
    vm: MagiViewModel,
    cell: Pair<Int, Int>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val (i, j) = cell
    val sheetState = rememberModalBottomSheetState()
    val allowed = remember(cell) { vm.allowedShiftsFor(i).toList() }
    val current = ui.schedule.getOrNull(i)?.getOrNull(j) ?: -1
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${ui.staffNames.getOrNull(i) ?: i} ・ ${j + 1}日 のシフトを選ぶ",
                style = MaterialTheme.typography.titleMedium,
            )
            val opts = if (allowed.isNotEmpty()) allowed else ui.shiftSymbols.indices.toList()
            opts.chunked(4).forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowKeys.forEach { k ->
                        val sym = ui.shiftSymbols.getOrNull(k) ?: k.toString()
                        val sel = k == current
                        val bg = if (sel) MaterialTheme.colorScheme.primary else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                        val fg = if (sel) MaterialTheme.colorScheme.onPrimary else hexToColor(ui.shiftTextHex.getOrNull(k) ?: "")
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .background(bg, RoundedCornerShape(16.dp))
                                .clickable { onPick(k) },
                            contentAlignment = Alignment.Center,
                        ) { Text(sym, color = fg, fontWeight = FontWeight.Bold) }
                    }
                    repeat(4 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CopilotCard(ui: UiState, onGoEdit: () -> Unit) {
    if (!ui.hasResult && !ui.running && ui.copilotHint == null && ui.impossibleWishCount == 0) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("操作アシスト", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("満足度", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { ui.satisfaction / 100f },
                    modifier = Modifier.weight(1f).height(10.dp),
                )
                Text("${ui.satisfaction}点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (ui.running) {
                val remain = (ui.budgetSec - (ui.elapsedMs / 1000)).coerceAtLeast(0L)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Text("計算中 — 残り約 ${remain}秒 ・ 未解決 ${ui.bestHard} ・ 調整 ${ui.bestSoft} ・ 反復 ${ui.iters}",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            // 担当外など実現不能な希望の警告（Web版の担当外希望警告に相当）
            if (ui.impossibleWishCount > 0) {
                Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⚠ 実現できない希望が ${ui.impossibleWishCount} 件（担当外シフトなど）。配布前に見直しを。",
                            color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("希望シフトを編集") }
                    }
                }
            }
            // ガチャ操作の助言＋修正導線（NextActionBar相当）
            ui.copilotHint?.let {
                Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("💡 $it", color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onGoEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("編集タブで見直す") }
                    }
                }
            }
            if (ui.polishExhausted && !ui.running) {
                Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text("✓ 必須条件は満たしています。残りの微調整は勤務表タブでの手修正が早い場合があります。",
                        color = cs.onTertiaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(ui: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("勤務表の状態", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BigStat("未解決(必須)", ui.bestHard.toString(), Modifier.weight(1f))
                BigStat("調整(任意)", ui.bestSoft.toString(), Modifier.weight(1f))
                BigStat("違反 合計", ui.totalViolations.toString(), Modifier.weight(1f))
            }
            Text(
                "スタッフ ${ui.staff} 名 ・ ${ui.days} 日 ・ シフト ${ui.shifts} 種 ・ グループ ${ui.groups}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "2世代カバレッジ(MIN=OR): ${if (ui.use2) "ON" else "OFF"} ・ 初期 HARD ${ui.initHard}/SOFT ${ui.initSoft}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun V6DashboardCard(v6: V6PortReport?) {
    if (v6 == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("V6 1ヶ月俯瞰", fontWeight = FontWeight.Bold)
            Text(
                "人員の穴・負荷の偏り・入力ミスを勤務表から直接集計します。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BigStat("HARD Core", v6.hardCore.toString(), Modifier.weight(1f))
                BigStat("Guard", v6.hardGuard.toString(), Modifier.weight(1f))
                BigStat("充足", v6.coveragePct?.let { "$it%" } ?: "-", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (v6.topRiskShortage > 0) "最優先: ${v6.topRiskLabel} に不足 ${v6.topRiskShortage} 枠" else "最優先: 人員不足なし",
                color = if (v6.topRiskShortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                v6.dayRisks.forEach { d -> RiskChip(d.label, d.shortage, d.detail) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Apt=${"%.2f".format(v6.aptPenalty)} / Equalize=${"%.2f".format(v6.equPenalty)} / Demand=${v6.demand} / covU=${v6.covU}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (v6.sanityWarnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                v6.sanityWarnings.take(3).forEach {
                    Text("⚠ $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("負荷プロフィール", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            v6.staffProfiles.take(5).forEach { st ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${st.name} ${st.groupSymbol}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("違反${st.violationCount} / 出勤${st.workCount} / ${st.workloadText}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun RiskChip(label: String, shortage: Int, detail: String) {
    val cs = MaterialTheme.colorScheme
    val bg: Color; val fg: Color
    when {
        shortage <= 0 -> { bg = cs.tertiaryContainer; fg = cs.onTertiaryContainer }
        shortage == 1 -> { bg = Color(0xFFFEF3C7); fg = Color(0xFF7C5800) }
        else -> { bg = cs.errorContainer; fg = cs.onErrorContainer }
    }
    Box(
        Modifier
            .width(76.dp)
            .background(bg, RoundedCornerShape(16.dp))
            .padding(horizontal = 7.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 11.sp, color = fg, maxLines = 1)
            Text(if (shortage > 0) "不足$shortage" else "OK", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
            if (detail.isNotBlank()) Text(detail, fontSize = 10.sp, color = fg.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StaffCalendarCard(ui: UiState, onCellClick: (Int, Int) -> Unit) {
    if (ui.schedule.isEmpty() || ui.staff == 0) return
    var staffIdx by remember { mutableIntStateOf(0) }
    val si = staffIdx.coerceIn(0, (ui.staff - 1).coerceAtLeast(0))
    val row = ui.schedule.getOrNull(si) ?: return
    val labels = ui.v6?.dayRisks?.map { it.label } ?: (0 until ui.days).map { "${it + 1}日" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("スタッフ別カレンダー", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { staffIdx = (si - 1).floorMod(ui.staff) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("前") }
                OutlinedButton(onClick = { staffIdx = (si + 1).floorMod(ui.staff) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("次") }
            }
            Text(
                "${ui.staffNames.getOrNull(si) ?: si} / ${ui.staffGroupSymbols.getOrNull(si) ?: ""} — タップで担当可能シフトを巡回",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            row.indices.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { j ->
                        val k = row.getOrNull(j) ?: -1
                        val symbol = if (k < 0) "·" else ui.shiftSymbols.getOrNull(k) ?: k.toString()
                        val vio = ui.violationCells.containsKey("$si,$j")
                        CalendarCell(labels.getOrNull(j) ?: "${j + 1}日", symbol, vio, Modifier.weight(1f)) {
                            onCellClick(si, j)
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun CalendarCell(label: String, symbol: String, violation: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (violation) cs.errorContainer else cs.surfaceVariant
    val labelFg = if (violation) cs.onErrorContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant
    val symbolFg = if (violation) cs.onErrorContainer else cs.onSurface
    Box(
        modifier
            .height(58.dp)
            .padding(horizontal = 2.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = labelFg, maxLines = 1)
            Text(symbol, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = symbolFg, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsCard(ui: UiState, vm: MagiViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("最適化設定", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("並列ワーカー（同時に計算する数）: ${ui.workers}")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setWorkers((ui.workers - 1).coerceAtLeast(1)) },
                    enabled = !ui.running && ui.workers > 1, modifier = Modifier.height(48.dp).semantics { contentDescription = "同時計算数を減らす" }) { Text("−", fontSize = 20.sp) }
                Text("${ui.workers}", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                Button(onClick = { vm.setWorkers((ui.workers + 1).coerceAtMost(16)) },
                    enabled = !ui.running && ui.workers < 16, modifier = Modifier.height(48.dp).semantics { contentDescription = "同時計算数を増やす" }) { Text("＋", fontSize = 20.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Text("時間予算（計算の制限時間）: ${ui.budgetSec} 秒")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setBudget((ui.budgetSec - 60).coerceAtLeast(10)) },
                    enabled = !ui.running && ui.budgetSec > 10, modifier = Modifier.height(48.dp)) { Text("− 60秒") }
                Text("${ui.budgetSec} 秒", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
                Button(onClick = { vm.setBudget((ui.budgetSec + 60).coerceAtMost(1800)) },
                    enabled = !ui.running && ui.budgetSec < 1800, modifier = Modifier.height(48.dp)) { Text("＋ 60秒") }
            }
            Text("計算方式: ${ui.v6Algorithm}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                V6Algorithm.values().forEach { alg ->
                    val selected = ui.v6Algorithm == alg
                    if (selected) {
                        Button(onClick = { vm.setV6Algorithm(alg) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(alg.name) }
                    } else {
                        OutlinedButton(onClick = { vm.setV6Algorithm(alg) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(alg.name) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = ui.softPolish,
                    onCheckedChange = { vm.setSoftPolish(it) },
                    enabled = !ui.running,
                )
                Spacer(Modifier.width(8.dp))
                Text("仕上げ最適化（品質を磨く）", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ActionCard(ui: UiState, vm: MagiViewModel, onBgOptimize: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("実行", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.generateSimple() }, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("簡易作成") }
                Button(onClick = { vm.runV6FullOptimize() }, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("最適化") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.start() }, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("高速計算") }
                OutlinedButton(onClick = { vm.runLightOptimize() }, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("軽量") }
            }
            // バックグラウンド最適化: アプリを閉じても継続し、完了時に通知（仕様書 §6）
            OutlinedButton(onClick = onBgOptimize, enabled = ui.loaded && !ui.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("バックグラウンドで最適化（閉じてもOK・完了通知）") }
            if (ui.running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("計算中…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.stop() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("停止") }
                }
            }
            Text(
                "反復 ${ui.iters} ・ ${ui.itersPerSec} iter/s ・ ${ui.elapsedMs} ms",
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BreakdownCard(ui: UiState) {
    val labels = mapOf(
        "groupViol" to "グループ不整合", "pref" to "希望違反", "covU" to "人員不足", "c3n" to "禁止の並び",
        "low" to "下限割れ", "high" to "上限超過",
        "c1" to "窓の要件", "c2" to "個人の合計", "c3" to "必須の並び", "c3m" to "推奨の並び",
        "c3mn" to "回避の並び", "c41" to "群のレンジ", "c42" to "群ペア", "covO" to "過剰な配置",
    )
    var criticalOnly by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("違反の内訳", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("重大のみ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Switch(checked = criticalOnly, onCheckedChange = { criticalOnly = it })
            }
            BreakdownGroup("必須（満たすべき）", listOf("groupViol", "pref", "covU", "c3n"), 2, ui, labels)
            if (!criticalOnly) {
                BreakdownGroup("人数の範囲", listOf("low", "high"), 1, ui, labels)
                BreakdownGroup("任意（できれば）", listOf("c1", "c2", "c3", "c3m", "c3mn", "c41", "c42", "covO"), 0, ui, labels)
            }
        }
    }
}

@Composable
private fun BreakdownGroup(title: String, keys: List<String>, severity: Int, ui: UiState, labels: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        keys.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key -> SeverityChip(labels[key] ?: key, ui.breakdown[key] ?: 0, severity, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SeverityChip(label: String, count: Int, severity: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val active = count > 0
    val container: Color; val onContainer: Color
    when {
        !active -> { container = cs.surfaceVariant; onContainer = cs.onSurfaceVariant }
        severity >= 2 -> { container = cs.errorContainer; onContainer = cs.onErrorContainer }
        severity == 1 -> { container = cs.secondaryContainer; onContainer = cs.onSecondaryContainer }
        else -> { container = cs.primaryContainer; onContainer = cs.onPrimaryContainer }
    }
    Surface(color = container, shape = MaterialTheme.shapes.small, modifier = modifier.heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = onContainer,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = onContainer)
        }
    }
}

@Composable
private fun ScheduleGrid(ui: UiState, onCellClick: (Int, Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val win = 7
    var monthMode by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(0) }
    val maxPage = if (ui.days <= win) 0 else (ui.days - 1) / win
    val cur = page.coerceIn(0, maxPage)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("勤務表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GridModeButton("7日表示", "大きく編集", !monthMode, Modifier.weight(1f)) { monthMode = false }
                GridModeButton("1ヶ月表示", "全体を確認", monthMode, Modifier.weight(1f)) { monthMode = true }
            }
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints {
                val totalW = maxWidth
                if (!monthMode) {
                    val staffW = 64.dp
                    val cellW = ((totalW - staffW) / win).coerceIn(36.dp, 80.dp)
                    val startDay = cur * win
                    val endDay = minOf(startDay + win, ui.days)
                    Column(
                        Modifier.pointerInput(cur, maxPage) {
                            var dx = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (dx > 60f) page = (cur - 1).coerceAtLeast(0)
                                    else if (dx < -60f) page = (cur + 1).coerceAtMost(maxPage)
                                    dx = 0f
                                },
                            ) { _, amount -> dx += amount }
                        },
                    ) {
                        Text("◀ ▶ かスワイプで日付を送り、セルをタップでシフト選択。",
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { page = (cur - 1).coerceAtLeast(0) }, enabled = cur > 0,
                                modifier = Modifier.height(48.dp)) { Text("◀ 前") }
                            Text("${startDay + 1}〜${endDay}日 / 全${ui.days}日",
                                style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f))
                            Button(onClick = { page = (cur + 1).coerceAtMost(maxPage) }, enabled = cur < maxPage,
                                modifier = Modifier.height(48.dp)) { Text("次 ▶") }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Column {
                                HeaderCell("スタッフ", staffW)
                                ui.schedule.indices.forEach { i ->
                                    StaffCell(ui.staffNames.getOrNull(i) ?: i.toString(),
                                        ui.staffGroupSymbols.getOrNull(i) ?: "", w = staffW)
                                }
                            }
                            Column {
                                Row { for (j in startDay until endDay) HeaderCell((j + 1).toString(), cellW) }
                                ui.schedule.forEachIndexed { i, row ->
                                    Row {
                                        for (j in startDay until endDay) {
                                            val k = row.getOrNull(j) ?: -1
                                            val vio = ui.violationCells.containsKey("$i,$j")
                                            val symbol = if (k < 0) "·" else ui.shiftSymbols.getOrNull(k) ?: k.toString()
                                            val bg = if (k < 0) cs.surface else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                                            val fg = if (k < 0) cs.onSurfaceVariant else hexToColor(ui.shiftTextHex.getOrNull(k) ?: "")
                                            Cell(symbol, vio, bg, fg, w = cellW) { onCellClick(i, j) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val staffW = 60.dp
                    val d = ui.days.coerceAtLeast(1)
                    val cellW = ((totalW - staffW) / d).coerceAtLeast(6.dp)
                    Column {
                        Text("月全体を色で確認できます。日付をタップするとその日の7日表示へ移動します。",
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Column {
                                Box(Modifier.width(staffW).height(22.dp))
                                ui.schedule.indices.forEach { i ->
                                    Box(Modifier.width(staffW).height(20.dp).padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.CenterStart) {
                                        Text(ui.staffNames.getOrNull(i) ?: i.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Column {
                                Row {
                                    for (j in 0 until ui.days) {
                                        Box(Modifier.width(cellW).height(22.dp)
                                            .semantics { contentDescription = "${j + 1}日（タップで7日表示へ）" }
                                            .clickable { page = j / win; monthMode = false },
                                            contentAlignment = Alignment.Center) {
                                            if (cellW >= 14.dp) Text((j + 1).toString(),
                                                style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                        }
                                    }
                                }
                                ui.schedule.forEachIndexed { i, row ->
                                    Row {
                                        for (j in 0 until ui.days) {
                                            val k = row.getOrNull(j) ?: -1
                                            val vio = ui.violationCells.containsKey("$i,$j")
                                            val bg = if (k < 0) cs.surfaceVariant else hexToColor(ui.shiftColorHex.getOrNull(k) ?: "")
                                            val symA = if (k < 0) "未割当" else (ui.shiftSymbols.getOrNull(k) ?: "")
                                            val staffA = ui.staffNames.getOrNull(i) ?: ""
                                            Box(Modifier.width(cellW).height(20.dp).padding(0.5.dp)
                                                .background(bg, RoundedCornerShape(4.dp))
                                                .then(if (vio) Modifier.border(1.5.dp, cs.error, RoundedCornerShape(4.dp)) else Modifier)
                                                .semantics { contentDescription = "$staffA ${j + 1}日 $symA" + if (vio) "（違反）" else "" }
                                                .clickable { page = j / win; monthMode = false })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridModeButton(title: String, sub: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(56.dp)
            .background(if (selected) cs.primary else cs.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = if (selected) cs.onPrimary else cs.onSurfaceVariant, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.labelSmall,
                color = if (selected) cs.onPrimary else cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun LogsCard(ui: UiState, onExportLog: () -> Unit, onExportJson: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hasAny = ui.opLog.isNotEmpty() || ui.logs.isNotEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ログ", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onExportLog, enabled = hasAny, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("テキスト出力") }
                OutlinedButton(onClick = onExportJson, enabled = hasAny, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("JSON出力") }
            }
            // 操作ログ（監査・新しい順）
            Text("操作ログ（新しい順 ${ui.opLog.size}件）", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            if (ui.opLog.isEmpty()) {
                Text("操作履歴なし", color = cs.onSurfaceVariant, fontSize = 12.sp)
            } else {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .background(cs.surfaceVariant, RoundedCornerShape(12.dp)).padding(10.dp),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ui.opLog.take(60).forEach { line ->
                            val warn = line.contains("[W]") || line.contains("[E]")
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = if (warn) cs.error else cs.onSurface)
                        }
                    }
                }
            }
            // 診断ログ（エンジン）
            if (ui.logs.isNotEmpty()) {
                Text("診断ログ", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                ui.logs.take(6).forEach {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(width).height(40.dp).padding(2.dp)) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StaffCell(name: String, symbol: String, w: androidx.compose.ui.unit.Dp = 92.dp) {
    Box(Modifier.width(w).height(52.dp).padding(2.dp)) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (symbol.isNotBlank()) Text(symbol, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Cell(text: String, violation: Boolean, bg: Color, fg: Color, w: androidx.compose.ui.unit.Dp = 52.dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.width(w).height(52.dp).padding(2.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(bg, RoundedCornerShape(12.dp))
                .then(if (violation) Modifier.border(2.dp, cs.error, RoundedCornerShape(12.dp)) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = fg, textAlign = TextAlign.Center, maxLines = 1)
            // 違反はシフト色の上に「丸い赤枠＋右上の赤ドット」で重ねて示す（色覚多様性対応）
            if (violation) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(7.dp)
                        .background(cs.error, RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

private fun Int.floorMod(m: Int): Int = if (m == 0) 0 else ((this % m) + m) % m

// ============================================================================
// 大規模UI改良: ユニバーサルデザイン + スマホ特化シェル (ボトムナビ + ステータスヒーロー)
// ============================================================================

@Composable
private fun MagiTopBar(ui: UiState) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
                Text(
                    "MAGI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text("勤務表", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (ui.loaded) {
                val ok = ui.hasResult && ui.bestHard == 0L
                val label: String; val fg: Color; val bg: Color
                when {
                    ui.running -> { label = "実行中"; fg = MaterialTheme.colorScheme.onPrimaryContainer; bg = MaterialTheme.colorScheme.primaryContainer }
                    ok -> { label = "配布可"; fg = MaterialTheme.colorScheme.onTertiaryContainer; bg = MaterialTheme.colorScheme.tertiaryContainer }
                    ui.hasResult -> { label = "未解決 ${ui.bestHard}"; fg = MaterialTheme.colorScheme.onErrorContainer; bg = MaterialTheme.colorScheme.errorContainer }
                    else -> { label = "未計算"; fg = MaterialTheme.colorScheme.onSurfaceVariant; bg = MaterialTheme.colorScheme.surfaceVariant }
                }
                Surface(color = bg, shape = MaterialTheme.shapes.small) {
                    Text(label, color = fg, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun BottomCommandBar(ui: UiState, vm: MagiViewModel) {
    val cs = MaterialTheme.colorScheme
    // 一本指: 主要操作を画面下部に全幅・大ボタン(60dp)で常設。指の届く範囲で押し外しにくい。文脈で 停止/作成/最適化。
    Surface(color = cs.surface, tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.canUndo && !ui.running) {
                OutlinedButton(
                    onClick = { vm.undo() },
                    modifier = Modifier.height(60.dp).semantics { contentDescription = "直前の操作を元に戻す" },
                ) { Text("元に戻す") }
                Spacer(Modifier.width(10.dp))
            }
            when {
                ui.running -> Button(
                    onClick = { vm.stop() },
                    modifier = Modifier.weight(1f).height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("計算を止める", style = MaterialTheme.typography.titleMedium)
                }
                !ui.hasResult -> Button(
                    onClick = { vm.generateSimple() },
                    modifier = Modifier.weight(1f).height(60.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("勤務表を作成", style = MaterialTheme.typography.titleMedium)
                }
                else -> Button(
                    onClick = { vm.runV6FullOptimize() },
                    modifier = Modifier.weight(1f).height(60.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("最適化する", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun MagiBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple("ホーム", Icons.Filled.Home, "ホーム"),
        Triple("勤務表", Icons.Filled.DateRange, "勤務表"),
        Triple("編集", Icons.Filled.Edit, "初期設定と制約の編集"),
        Triple("分析", Icons.Filled.Assessment, "分析と違反"),
        Triple("設定", Icons.Filled.Settings, "設定とデータ"),
    )
    NavigationBar {
        items.forEachIndexed { i, item ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                icon = { Icon(item.second, contentDescription = item.third) },
                label = { Text(item.first, style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun StatusHero(ui: UiState) {
    val cs = MaterialTheme.colorScheme
    val container: Color; val onContainer: Color; val icon: ImageVector; val title: String; val sub: String
    when {
        !ui.hasResult -> {
            container = cs.secondaryContainer; onContainer = cs.onSecondaryContainer; icon = Icons.Filled.PlayArrow
            title = "まだ作成していません"; sub = "右下の「勤務表を作成」から始められます"
        }
        ui.bestHard == 0L -> {
            container = cs.tertiaryContainer; onContainer = cs.onTertiaryContainer; icon = Icons.Filled.CheckCircle
            title = "配布できます"; sub = "必須条件はすべて満たしています（残り調整 ${ui.bestSoft}）"
        }
        else -> {
            container = cs.errorContainer; onContainer = cs.onErrorContainer; icon = Icons.Filled.Warning
            title = "未解決の条件が ${ui.bestHard} 件"; sub = "右下の「最適化」をもう一度、または編集タブで条件を見直してください"
        }
    }
    Surface(color = container, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = onContainer)
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = onContainer)
                }
            }
            ui.v6?.let { v ->
                Text("充足 ${v.coveragePct ?: 0}%   不足 ${v.covU}   必要 ${v.demand}",
                    style = MaterialTheme.typography.labelLarge, color = onContainer)
            }
        }
    }
}

@Composable
private fun EmptyStateCard(onOpen: () -> Unit, onSample: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
            Text("勤務表データを開きましょう", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("保存済みの JSON を開くか、サンプルから始められます。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("データを開く", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = onSample, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("サンプルで試す", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DataActionsCard(
    ui: UiState,
    onOpenJson: () -> Unit, onSample: () -> Unit, onSaveJson: () -> Unit,
    onOpenCsv: () -> Unit, onSaveCsv: () -> Unit, onCheck: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("データ", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenJson, enabled = !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("JSON開く") }
                OutlinedButton(onClick = onSample, enabled = !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("サンプル") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveJson, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("JSON保存") }
                OutlinedButton(onClick = onCheck, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("違反チェック") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("CSV取込") }
                OutlinedButton(onClick = onSaveCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("CSV出力") }
            }
        }
    }
}

@Composable
private fun MessageBar(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(14.dp))
    }
}

@Composable
private fun AppearanceCard(themeMode: Int, onThemeMode: (Int) -> Unit, oneHand: Boolean = false, onOneHand: (Boolean) -> Unit = {}) {
    val options = listOf("システム", "ライト", "ダーク", "高コントラスト")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("外観", style = MaterialTheme.typography.titleMedium)
            Text("見やすさに合わせて配色を選べます。",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = oneHand, onCheckedChange = onOneHand)
                Spacer(Modifier.width(8.dp))
                Text("片手モード（内容を下方に寄せて親指で届きやすく）", fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            options.chunked(2).forEachIndexed { rowIdx, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { colIdx, label ->
                        val idx = rowIdx * 2 + colIdx
                        if (themeMode == idx) {
                            Button(onClick = { onThemeMode(idx) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            OutlinedButton(onClick = { onThemeMode(idx) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
