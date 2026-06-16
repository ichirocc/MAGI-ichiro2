package com.magi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** "#rrggbb"/"#rgb" -> Compose Color。同パッケージ(ScheduleGrid)と共有。不正値はグレー。 */
internal fun hexToColor(hex: String): Color {
    val h = hex.trim().removePrefix("#")
    val full = when (h.length) {
        3 -> buildString { h.forEach { append(it); append(it) } }
        6 -> h
        else -> "888888"
    }
    val v = full.toIntOrNull(16) ?: 0x888888
    return Color((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
}

private val COLOR_PALETTE = listOf(
    "#8195a8", "#a96bff", "#4fa89c", "#d7a13b", "#6fa56b", "#5fb3d4",
    "#a89a86", "#6e6354", "#9d8a64", "#c0563f", "#3f6fc0", "#7a4fd0",
)

/**
 * colors 移植: シフトの表示色設定。
 * shiftColors[kigou] の上書きを編集。既定はカテゴリ別の色（resolveShiftColor）。
 * 表示専用のため採点・エンジンに影響しない。勤務表グリッドに反映される。
 */
@Composable
fun ShiftColorCard(ui: UiState, vm: MagiViewModel) {
    var target by remember { mutableStateOf<String?>(null) }
    val shifts = vm.shiftColorList()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("シフトの表示色", style = MaterialTheme.typography.titleMedium)
            Text(
                "勤務表に表示される各シフトの色。タップして変更できます（既定はシフト種別ごとの色）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shifts.isEmpty()) {
                Text(
                    "（データ未読込）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                shifts.forEach { sc ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = !ui.running) { target = sc.kigou },
                    ) {
                        Swatch(sc.hex, 30.dp)
                        Text(
                            "  ${sc.kigou}${if (sc.name.isNotBlank() && sc.name != sc.kigou) " (${sc.name})" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (sc.custom) "指定" else "既定",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    target?.let { kg ->
        val current = shifts.firstOrNull { it.kigou == kg }
        ColorPickerDialog(
            kigou = kg,
            currentHex = current?.hex ?: "",
            onPick = { hex -> vm.setShiftColor(kg, hex); target = null },
            onReset = { vm.resetShiftColor(kg); target = null },
            onClose = { target = null },
        )
    }
}

@Composable
private fun Swatch(hex: String, sizeDp: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(sizeDp)
            .background(hexToColor(hex), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun ColorPickerDialog(
    kigou: String,
    currentHex: String,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onReset) { Text("既定に戻す") } },
        dismissButton = { TextButton(onClick = onClose) { Text("閉じる") } },
        title = { Text("「$kigou」の色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(currentHex, 28.dp)
                    Text("  現在の色", style = MaterialTheme.typography.bodyMedium)
                }
                Text("色を選ぶ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                COLOR_PALETTE.chunked(6).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { hex ->
                            val selected = hex.equals(currentHex, ignoreCase = true)
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(hexToColor(hex), RoundedCornerShape(8.dp))
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onPick(hex) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Text("✓", color = hexToColor(pickFg(hex)), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        },
    )
}

/** 簡易: 明度から文字色を選ぶ（チェック印用）。 */
private fun pickFg(bgHex: String): String {
    val h = bgHex.trim().removePrefix("#")
    val v = (if (h.length == 6) h else "888888").toIntOrNull(16) ?: 0x888888
    val r = (v shr 16) and 0xFF; val g = (v shr 8) and 0xFF; val b = v and 0xFF
    val lum = (0.299 * r + 0.587 * g + 0.114 * b)
    return if (lum > 140) "#14110d" else "#fbf4e8"
}

