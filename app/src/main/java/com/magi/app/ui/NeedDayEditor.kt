package com.magi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * ws2 移植: 日別の必要人数（例外）。
 * 通常はシフト既定の need1/need2 を使うが、特定日だけ必要数を変えたい場合に
 * needDay1/needDay2 の疎な上書きを編集する。モデル・エンジンは不変。
 */
@Composable
fun NeedDayCard(ui: UiState, vm: MagiViewModel) {
    var dialog by remember { mutableStateOf<NeedDayEdit?>(null) }
    val overrides = vm.needDayOverrides()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("日別の必要人数（例外）", style = MaterialTheme.typography.titleMedium)
            Text(
                "通常はシフト既定の必要数（P1/P2）を使います。特定の日だけ人数を変えたいときに追加します。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (overrides.isEmpty()) {
                Text(
                    "（例外なし — すべて既定値）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                overrides.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${o.kigou}  ${o.j + 1}日  P1 ${o.p1.ifBlank { "-" }} / P2 ${o.p2.ifBlank { "-" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { dialog = NeedDayEdit(o.k, o.j, o.p1, o.p2) },
                            enabled = !ui.running,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("編集") }
                        TextButton(
                            onClick = { vm.removeNeedDay(o.k, o.j) },
                            enabled = !ui.running,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("削除") }
                    }
                }
            }
            OutlinedButton(
                onClick = { dialog = NeedDayEdit(0, 0, "", "") },
                enabled = ui.loaded && !ui.running,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("＋ 例外を追加") }
        }
    }
    dialog?.let { d ->
        NeedDayDialog(
            init = d,
            shifts = vm.shiftKigouList(),
            maxDay = ui.days,
            onApply = { k, j, p1, p2 -> vm.setNeedDay(k, j, p1, p2); dialog = null },
            onClose = { dialog = null },
        )
    }
}

/** 一本指: 数値は「＋ −」の大ボタンで増減（キーボード不要）。空＝既定/なし。同package(StaffRange)と共有。 */
@Composable
internal fun NumberStepper(label: String, value: String, onChange: (String) -> Unit, min: Int, blankLabel: String) {
    val n = value.toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { when { n == null -> {}; n <= min -> onChange(""); else -> onChange((n - 1).toString()) } },
            modifier = Modifier.height(48.dp).semantics { contentDescription = "$label を減らす" },
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            Text(if (value.isBlank()) blankLabel else value, style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = { onChange(((n ?: (min - 1)) + 1).coerceAtLeast(min).toString()) },
            modifier = Modifier.height(48.dp).semantics { contentDescription = "$label を増やす" },
        ) { Text("＋", style = MaterialTheme.typography.titleLarge) }
    }
}

private data class NeedDayEdit(val k: Int, val j: Int, val p1: String, val p2: String)

@Composable
private fun NeedDayDialog(
    init: NeedDayEdit,
    shifts: List<String>,
    maxDay: Int,
    onApply: (Int, Int, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var k by remember { mutableStateOf(init.k) }
    var dayText by remember { mutableStateOf((init.j + 1).toString()) }
    var p1 by remember { mutableStateOf(init.p1) }
    var p2 by remember { mutableStateOf(init.p2) }
    var open by remember { mutableStateOf(false) }
    val day = dayText.toIntOrNull()
    val ok = k in shifts.indices && day != null && day in 1..maxDay && (p1.isNotBlank() || p2.isNotBlank())
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = { if (ok) onApply(k, day!! - 1, p1.trim(), p2.trim()) }, enabled = ok) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("キャンセル") } },
        title = { Text("日別の必要人数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("シフト", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(shifts.getOrNull(k) ?: "(選択)")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        shifts.forEachIndexed { idx, kg ->
                            DropdownMenuItem(text = { Text(kg) }, onClick = { k = idx; open = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = dayText,
                    onValueChange = { dayText = it.filter { c -> c.isDigit() } },
                    label = { Text("日 (1〜$maxDay)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberStepper("P1 必要数", p1, { p1 = it }, min = 0, blankLabel = "既定")
                NumberStepper("P2 必要数", p2, { p2 = it }, min = 0, blankLabel = "既定")
            }
        },
    )
}
