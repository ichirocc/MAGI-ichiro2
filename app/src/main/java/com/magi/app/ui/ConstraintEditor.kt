package com.magi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ws3-5 constraint editor, rendered as a card in the single-scroll layout. Adding or
 * removing a constraint rebuilds state and re-runs the unified violation check
 * (see MagiViewModel.mutateConstraints -> refreshCheck), so the breakdown/score/v6
 * panels above update automatically. Edited constraints are written back on JSON save.
 */
@Composable
fun ConstraintsCard(ui: UiState, vm: MagiViewModel) {
    var addFamily by remember { mutableStateOf<String?>(null) }
    val families = vm.constraintFamilies()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("制約の編集 (ws3-5)", fontWeight = FontWeight.Bold)
            Text(
                "追加・削除すると即再チェックされ、保存にも反映されます。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            )
            families.forEachIndexed { fi, fam ->
                if (fi > 0) Spacer(Modifier.height(6.dp))
                Spacer(Modifier.height(8.dp))
                Text(fam.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (fam.rows.isEmpty()) {
                    Text("(なし)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    fam.rows.forEachIndexed { idx, row ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(row, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.removeConstraint(fam.key, idx) }) { Text("削除") }
                        }
                    }
                }
                TextButton(onClick = { addFamily = fam.key }) { Text("+ 追加") }
                Divider()
            }
        }
    }

    val fam = addFamily
    if (fam != null) AddConstraintDialog(fam, vm) { addFamily = null }
}

@Composable
private fun AddConstraintDialog(family: String, vm: MagiViewModel, onClose: () -> Unit) {
    val shifts = vm.shiftKigouList()
    val groups = vm.groupKigouList()
    val shiftsOpt = listOf("") + shifts

    when (family) {
        "cons1" -> {
            var d1 by remember { mutableStateOf("") }
            var sk by remember { mutableStateOf(shifts.firstOrNull() ?: "") }
            var d2 by remember { mutableStateOf("") }
            Shell("期間の要件を追加", onClose, { vm.addCons1(d1, sk, d2); onClose() },
                d1.isNotBlank() && sk.isNotBlank() && d2.isNotBlank()) {
                NumField("窓(日)", d1) { d1 = it }
                Picker("シフト", shifts, sk) { sk = it }
                NumField("必要数(以上)", d2) { d2 = it }
            }
        }
        "cons2" -> {
            var sk by remember { mutableStateOf(shifts.firstOrNull() ?: "") }
            var c by remember { mutableStateOf("") }
            Shell("個人の合計を追加", onClose, { vm.addCons2(sk, c); onClose() },
                sk.isNotBlank() && c.isNotBlank()) {
                Picker("シフト", shifts, sk) { sk = it }
                NumField("合計(以上)", c) { c = it }
            }
        }
        "cons41" -> {
            var gk by remember { mutableStateOf(groups.firstOrNull() ?: "") }
            var sk by remember { mutableStateOf(shifts.firstOrNull() ?: "") }
            var l by remember { mutableStateOf("") }
            var u by remember { mutableStateOf("") }
            Shell("群レンジを追加", onClose, { vm.addCons41(gk, sk, l, u); onClose() },
                gk.isNotBlank() && sk.isNotBlank()) {
                Picker("群", groups, gk) { gk = it }
                Picker("シフト", shifts, sk) { sk = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("下限(空=0)", l) { l = it }
                    NumField("上限(空=無制限)", u) { u = it }
                }
            }
        }
        "cons42" -> {
            var g1 by remember { mutableStateOf(groups.firstOrNull() ?: "") }
            var s1 by remember { mutableStateOf(shifts.firstOrNull() ?: "") }
            var g2 by remember { mutableStateOf(groups.firstOrNull() ?: "") }
            var s2 by remember { mutableStateOf(shifts.firstOrNull() ?: "") }
            Shell("群ペアの競合を追加", onClose, { vm.addCons42(g1, g2, s1, s2); onClose() },
                g1.isNotBlank() && s1.isNotBlank() && g2.isNotBlank() && s2.isNotBlank()) {
                Picker("群1", groups, g1) { g1 = it }
                Picker("シフト1", shifts, s1) { s1 = it }
                Picker("群2", groups, g2) { g2 = it }
                Picker("シフト2", shifts, s2) { s2 = it }
            }
        }
        "cons3", "cons3n", "cons3m", "cons3mn" -> {
            var a by remember { mutableStateOf(shifts.firstOrNull() ?: "") }
            var b by remember { mutableStateOf("") }
            var c by remember { mutableStateOf("") }
            var d by remember { mutableStateOf("") }
            var e by remember { mutableStateOf("") }
            val title = when (family) {
                "cons3n" -> "禁止の並びを追加"
                "cons3m" -> "推奨の並びを追加"
                "cons3mn" -> "回避の並びを追加"
                else -> "必須の並びを追加"
            }
            Shell(title, onClose, { vm.addCons3(family, listOf(a, b, c, d, e)); onClose() }, a.isNotBlank()) {
                Text("並び (上から順・最大5連日 / 空=ここで終了)", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Picker("1番目", shifts, a) { a = it }
                Picker("2番目", shiftsOpt, b) { b = it }
                Picker("3番目", shiftsOpt, c) { c = it }
                Picker("4番目", shiftsOpt, d) { d = it }
                Picker("5番目", shiftsOpt, e) { e = it }
            }
        }
        else -> onClose()
    }
}

@Composable
private fun Shell(
    title: String,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    addEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onAdd, enabled = addEnabled) { Text("追加") } },
        dismissButton = { TextButton(onClick = onClose) { Text("キャンセル") } },
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } },
    )
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.width(150.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun Picker(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(if (selected.isBlank()) "(なし)" else selected)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(if (opt.isBlank()) "(なし)" else opt, fontFamily = FontFamily.Monospace) },
                        onClick = { onSelect(opt); open = false },
                    )
                }
            }
        }
    }
}
