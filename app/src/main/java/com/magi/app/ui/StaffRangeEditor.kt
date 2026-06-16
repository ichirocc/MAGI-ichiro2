package com.magi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * ws5 移植: 個人別の回数（上下限 = LimMin/LimMax）。
 * staffRange["i,k"] = Range(lo, hi) を編集する。空＝制限なし。
 * モデル(staffRange)・エンジン(ct)は既存のため不変、UI のみ追加。
 */
@Composable
fun StaffRangeCard(ui: UiState, vm: MagiViewModel) {
    var dialog by remember { mutableStateOf<StaffRangeEdit?>(null) }
    val rows = vm.staffRangeOverrides()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("個人別の回数（上下限）", style = MaterialTheme.typography.titleMedium)
            Text(
                "各スタッフが各シフトを担当する回数の下限・上限。設定した分だけ制約になります（空＝制限なし）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rows.isEmpty()) {
                Text(
                    "（設定なし）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${r.staffName}  ${r.kigou}  下限 ${r.lo.ifBlank { "-" }} / 上限 ${r.hi.ifBlank { "-" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { dialog = StaffRangeEdit(r.i, r.k, r.lo, r.hi) },
                            enabled = !ui.running,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("編集") }
                        TextButton(
                            onClick = { vm.removeStaffRange(r.i, r.k) },
                            enabled = !ui.running,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("削除") }
                    }
                }
            }
            OutlinedButton(
                onClick = { dialog = StaffRangeEdit(0, 0, "", "") },
                enabled = ui.loaded && !ui.running,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("＋ 上下限を追加") }
        }
    }
    dialog?.let { d ->
        StaffRangeDialog(
            init = d,
            staff = ui.staffNames,
            shifts = vm.shiftKigouList(),
            onApply = { i, k, lo, hi -> vm.setStaffRange(i, k, lo, hi); dialog = null },
            onClose = { dialog = null },
        )
    }
}

private data class StaffRangeEdit(val i: Int, val k: Int, val lo: String, val hi: String)

@Composable
private fun StaffRangeDialog(
    init: StaffRangeEdit,
    staff: List<String>,
    shifts: List<String>,
    onApply: (Int, Int, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var i by remember { mutableStateOf(init.i) }
    var k by remember { mutableStateOf(init.k) }
    var lo by remember { mutableStateOf(init.lo) }
    var hi by remember { mutableStateOf(init.hi) }
    var openS by remember { mutableStateOf(false) }
    var openK by remember { mutableStateOf(false) }
    val ok = i in staff.indices && k in shifts.indices && (lo.isNotBlank() || hi.isNotBlank())
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = { if (ok) onApply(i, k, lo.trim(), hi.trim()) }, enabled = ok) { Text("適用") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("キャンセル") } },
        title = { Text("個人別の回数") },
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
                Text("シフト", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                NumberStepper("下限", lo, { lo = it }, min = 0, blankLabel = "なし")
                NumberStepper("上限", hi, { hi = it }, min = 0, blankLabel = "なし")
            }
        },
    )
}
