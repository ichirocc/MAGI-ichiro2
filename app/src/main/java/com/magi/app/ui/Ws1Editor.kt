package com.magi.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
 * ws1 (初期設定) editor card. Edits the problem definition: period length (days),
 * use2 flag, shifts / groups / staff (rename + per-field edit, append-add), and the
 * group×shift bucket. Each change re-dimensions the working table consistently
 * (MagiViewModel.ws1* -> Ws1Ops) and re-runs the check; saving emits the full state.
 * Remove operations are deferred to a later increment.
 */
@Composable
fun Ws1Card(ui: UiState, vm: MagiViewModel) {
    val v = vm.ws1() ?: return
    var dialog by remember { mutableStateOf<Ws1Dialog?>(null) }
    var daysText by remember(v.days) { mutableStateOf(v.days.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("初期設定 (ws1)", fontWeight = FontWeight.Bold)
            Text("問題定義の編集。変更で表が再構成され再チェックされます。", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary)

            // --- period ---
            Spacer(Modifier.height(10.dp))
            Text("期間", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("${v.startDate} 〜 ${v.endDate}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                W1Field("日数(1-31)", daysText, Modifier.width(130.dp)) { daysText = it }
                TextButton(onClick = { daysText.toIntOrNull()?.let { vm.ws1ResizeDays(it) } }) { Text("変更") }
            }

            // --- use2 ---
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("P2 パターン (use2 / MIN=OR)", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = v.use2, onCheckedChange = { vm.ws1SetUse2(it) })
            }
            Divider()

            // --- shifts ---
            Spacer(Modifier.height(8.dp))
            Text("シフト (${v.shifts.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            v.shifts.forEachIndexed { k, s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.kigou}  ${s.name}  (P1 ${s.need1.ifBlank { "-" }} / P2 ${s.need2.ifBlank { "-" }})",
                        fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { dialog = Ws1Dialog.EditShift(k, s.name, s.kigou, s.need1, s.need2) }) { Text("編集") }
                    if (v.shifts.size > 1) {
                        TextButton(onClick = { dialog = Ws1Dialog.ConfirmDelete("shift", k, "シフト ${s.kigou}") }) { Text("削除") }
                    }
                }
            }
            TextButton(onClick = { dialog = Ws1Dialog.AddShift }) { Text("+ シフト追加") }
            Divider()

            // --- groups ---
            Spacer(Modifier.height(8.dp))
            Text("グループ (${v.groups.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            v.groups.forEachIndexed { g, gr ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${gr.kigou}  ${gr.name}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { dialog = Ws1Dialog.EditGroup(g, gr.name, gr.kigou) }) { Text("編集") }
                    if (vm.ws1CanRemoveGroup(g)) {
                        TextButton(onClick = { dialog = Ws1Dialog.ConfirmDelete("group", g, "群 ${gr.kigou}") }) { Text("削除") }
                    }
                }
            }
            TextButton(onClick = { dialog = Ws1Dialog.AddGroup }) { Text("+ 群追加") }
            Divider()

            // --- staff ---
            Spacer(Modifier.height(8.dp))
            Text("スタッフ (${v.staff.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            v.staff.forEachIndexed { i, st ->
                val gk = v.groups.getOrNull(st.groupIdx)?.kigou ?: "?"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${st.name}  [群 $gk]", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { dialog = Ws1Dialog.EditStaff(i, st.name, st.groupIdx) }) { Text("編集") }
                    if (v.staff.size > 1) {
                        TextButton(onClick = { dialog = Ws1Dialog.ConfirmDelete("staff", i, st.name) }) { Text("削除") }
                    }
                }
            }
            TextButton(onClick = { dialog = Ws1Dialog.AddStaff }) { Text("+ スタッフ追加") }
            Divider()

            // --- groupShift bucket ---
            Spacer(Modifier.height(8.dp))
            Text("担当可能シフト (群 × シフト)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            v.groups.forEachIndexed { g, gr ->
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${gr.kigou}: ", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    v.shifts.forEachIndexed { k, s ->
                        val on = v.groupShift.getOrNull(g)?.getOrNull(k) == 1
                        TextButton(onClick = { vm.ws1SetGroupShift(g, k, !on) }) {
                            Text(if (on) "[${s.kigou}]" else " ${s.kigou} ",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is Ws1Dialog.EditShift -> ShiftDialog("シフト編集", d.name, d.kigou, d.need1, d.need2,
            { n, kg, n1, n2 -> vm.ws1EditShift(d.k, n, kg, n1, n2); dialog = null }, { dialog = null })
        Ws1Dialog.AddShift -> ShiftDialog("シフト追加", "", "", "", "",
            { n, kg, n1, n2 -> vm.ws1AddShift(n, kg, n1, n2); dialog = null }, { dialog = null })
        is Ws1Dialog.EditGroup -> GroupDialog("グループ編集", d.name, d.kigou,
            { n, kg -> vm.ws1EditGroup(d.g, n, kg); dialog = null }, { dialog = null })
        Ws1Dialog.AddGroup -> GroupDialog("群追加", "", "",
            { n, kg -> vm.ws1AddGroup(n, kg); dialog = null }, { dialog = null })
        is Ws1Dialog.EditStaff -> StaffDialog("スタッフ編集", d.name, d.groupIdx, v.groups.map { it.kigou },
            { n, gi -> vm.ws1EditStaff(d.i, n, gi); dialog = null }, { dialog = null })
        Ws1Dialog.AddStaff -> StaffDialog("スタッフ追加", "", 0, v.groups.map { it.kigou },
            { n, gi -> vm.ws1AddStaff(n, gi); dialog = null }, { dialog = null })
        is Ws1Dialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = {
                TextButton(onClick = {
                    when (d.kind) {
                        "shift" -> vm.ws1RemoveShift(d.index)
                        "group" -> vm.ws1RemoveGroup(d.index)
                        "staff" -> vm.ws1RemoveStaff(d.index)
                    }
                    dialog = null
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } },
            title = { Text("削除の確認") },
            text = { Text("${d.label} を削除します。割当やインデックスが再構成されます。よろしいですか？") },
        )
        null -> Unit
    }
}

private sealed interface Ws1Dialog {
    data class EditShift(val k: Int, val name: String, val kigou: String, val need1: String, val need2: String) : Ws1Dialog
    object AddShift : Ws1Dialog
    data class EditGroup(val g: Int, val name: String, val kigou: String) : Ws1Dialog
    object AddGroup : Ws1Dialog
    data class EditStaff(val i: Int, val name: String, val groupIdx: Int) : Ws1Dialog
    object AddStaff : Ws1Dialog
    data class ConfirmDelete(val kind: String, val index: Int, val label: String) : Ws1Dialog
}

@Composable
private fun ShiftDialog(
    title: String, name0: String, kigou0: String, need10: String, need20: String,
    onOk: (String, String, String, String) -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(name0) }
    var kigou by remember { mutableStateOf(kigou0) }
    var need1 by remember { mutableStateOf(need10) }
    var need2 by remember { mutableStateOf(need20) }
    W1Shell(title, onClose, { onOk(name, kigou, need1, need2) }, kigou.isNotBlank()) {
        W1Text("記号 (kigou)", kigou) { kigou = it }
        W1Text("名称", name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            W1Field("P1 必要数", need1, Modifier.width(140.dp)) { need1 = it }
            W1Field("P2 必要数", need2, Modifier.width(140.dp)) { need2 = it }
        }
    }
}

@Composable
private fun GroupDialog(
    title: String, name0: String, kigou0: String,
    onOk: (String, String) -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(name0) }
    var kigou by remember { mutableStateOf(kigou0) }
    W1Shell(title, onClose, { onOk(name, kigou) }, kigou.isNotBlank()) {
        W1Text("記号 (kigou)", kigou) { kigou = it }
        W1Text("名称", name) { name = it }
    }
}

@Composable
private fun StaffDialog(
    title: String, name0: String, group0: Int, groupKigou: List<String>,
    onOk: (String, Int) -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(name0) }
    var gi by remember { mutableStateOf(group0.coerceIn(0, (groupKigou.size - 1).coerceAtLeast(0))) }
    W1Shell(title, onClose, { onOk(name, gi) }, name.isNotBlank() && groupKigou.isNotEmpty()) {
        W1Text("名称", name) { name = it }
        var open by remember { mutableStateOf(false) }
        Text("グループ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }) { Text(groupKigou.getOrNull(gi) ?: "(なし)") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            groupKigou.forEachIndexed { idx, kg ->
                DropdownMenuItem(text = { Text(kg, fontFamily = FontFamily.Monospace) },
                    onClick = { gi = idx; open = false })
            }
        }
    }
}

@Composable
private fun W1Shell(
    title: String, onClose: () -> Unit, onOk: () -> Unit, okEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onOk, enabled = okEnabled) { Text("OK") } },
        dismissButton = { TextButton(onClick = onClose) { Text("キャンセル") } },
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } },
    )
}

@Composable
private fun W1Text(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun W1Field(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label, fontSize = 12.sp) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
