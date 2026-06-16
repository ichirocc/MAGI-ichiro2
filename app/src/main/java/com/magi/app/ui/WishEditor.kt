package com.magi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * ws3 移植: 希望シフト wishes["i,j"]=シフトindex（スタッフ i が j 日目に希望するシフト）。
 * 採点は pref（hard1 のソフト寄り）。モデル(wishes)・エンジン(pref)は既存のため不変、UI のみ追加。
 * 注意: これは「希望」であり、勤務表セルの「割当」変更とも、cons3系（連勤の並び）とも別概念。
 * 一本指対応: スタッフ/希望シフトはタップで選ぶプルダウン、日は ＋− ステッパー。
 */
@Composable
fun WishCard(ui: UiState, vm: MagiViewModel) {
    var dialog by remember { mutableStateOf<WishEdit?>(null) }
    val rows = vm.wishOverrides()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("希望シフト", style = MaterialTheme.typography.titleMedium)
            Text(
                "各スタッフが特定の日に希望するシフトを登録します（できれば叶える＝任意）。勤務表の割当変更とは別です。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rows.isEmpty()) {
                Text(
                    "（希望なし）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${r.staffName}  ${r.day}日  希望 ${r.kigou}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { dialog = WishEdit(r.i, r.j, r.k) },
                            enabled = !ui.running,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("編集") }
                        TextButton(
                            onClick = { vm.removeWish(r.i, r.j) },
                            enabled = !ui.running,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("削除") }
                    }
                }
            }
            OutlinedButton(
                onClick = { dialog = WishEdit(0, 0, 0) },
                enabled = ui.loaded && !ui.running,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("＋ 希望を追加") }
        }
    }
    dialog?.let { d ->
        WishDialog(
            initI = d.i,
            initJ = d.j,
            initK = d.k,
            days = ui.days,
            staff = ui.staffNames,
            shifts = vm.shiftKigouList(),
            onApply = { i, j, k -> vm.setWish(i, j, k); dialog = null },
            onClose = { dialog = null },
        )
    }
}

private data class WishEdit(val i: Int, val j: Int, val k: Int)

@Composable
private fun WishDialog(
    initI: Int,
    initJ: Int,
    initK: Int,
    days: Int,
    staff: List<String>,
    shifts: List<String>,
    onApply: (Int, Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    val maxDay = days.coerceAtLeast(1)
    var i by remember { mutableStateOf(initI) }
    var day by remember { mutableStateOf((initJ + 1).coerceIn(1, maxDay)) }
    var k by remember { mutableStateOf(initK) }
    var openS by remember { mutableStateOf(false) }
    var openK by remember { mutableStateOf(false) }
    val ok = i in staff.indices && k in shifts.indices && day in 1..maxDay
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = { if (ok) onApply(i, day - 1, k) }, enabled = ok) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("キャンセル") } },
        title = { Text("希望シフト") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("スタッフ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { openS = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(staff.getOrNull(i) ?: "(選択)")
                    }
                    DropdownMenu(expanded = openS, onDismissRequest = { openS = false }) {
                        staff.forEachIndexed { idx, n ->
                            DropdownMenuItem(text = { Text(n) }, onClick = { i = idx; openS = false })
                        }
                    }
                }
                Text("日", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { day = (day - 1).coerceAtLeast(1) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "日を減らす" }) { Text("−") }
                    Text("${day}日", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
                    Button(onClick = { day = (day + 1).coerceAtMost(maxDay) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "日を増やす" }) { Text("＋") }
                }
                Text("希望シフト", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { openK = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(shifts.getOrNull(k) ?: "(選択)")
                    }
                    DropdownMenu(expanded = openK, onDismissRequest = { openK = false }) {
                        shifts.forEachIndexed { idx, kg ->
                            DropdownMenuItem(text = { Text(kg) }, onClick = { k = idx; openK = false })
                        }
                    }
                }
            }
        },
    )
}
