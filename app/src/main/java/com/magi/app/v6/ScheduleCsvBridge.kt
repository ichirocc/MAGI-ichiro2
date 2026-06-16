package com.magi.app.v6

import com.magi.app.model.MagiState

object ScheduleCsvBridge {
    fun build(state: MagiState, schedule: Array<IntArray>): String {
        val p = Problem(state)
        val s = normalizeSchedule(schedule, p)
        val out = StringBuilder()
        val header = ArrayList<String>()
        header.add("スタッフ \\ 日付")
        for (j in 0 until p.T) header.add(formatDay(state.startDate, j))
        appendCsvRow(out, header)

        for (i in 0 until p.S) {
            val line = ArrayList<String>()
            line.add(state.staff[i].name)
            for (j in 0 until p.T) {
                val k = s[i][j]
                val symbol = state.shifts.getOrNull(k)?.kigou ?: ""
                line.add(symbol)
            }
            appendCsvRow(out, line)
        }

        appendCsvRow(out, emptyList())
        val sumHeader = ArrayList<String>()
        sumHeader.add("集計")
        for (shift in state.shifts) sumHeader.add(shift.kigou)
        appendCsvRow(out, sumHeader)

        val counts = countMatrix(p, s)
        for (i in 0 until p.S) {
            val row = ArrayList<String>()
            row.add(state.staff[i].name)
            for (k in 0 until p.K) row.add(counts[i][k].toString())
            appendCsvRow(out, row)
        }
        return out.toString()
    }

    fun parse(text: String, state: MagiState, base: Array<IntArray>): ScheduleRunResult {
        val rows = parseCsvRows(text)
        val p = Problem(state)
        val schedule = normalizeSchedule(base, p)
        val nameToI = LinkedHashMap<String, Int>()
        for (i in state.staff.indices) {
            nameToI[state.staff[i].name.trim()] = i
        }
        val kigouToK = LinkedHashMap<String, Int>()
        for (k in state.shifts.indices) {
            kigouToK[state.shifts[k].kigou.trim()] = k
        }
        var matched = 0
        var rr = 1
        while (rr < rows.size) {
            val r = rows[rr]
            if (r.isNotEmpty() && r[0].trim().isNotEmpty()) {
                val staffIndex = nameToI[r[0].trim()]
                if (staffIndex != null) {
                    matched++
                    val last = minOf(p.T, r.size - 1)
                    var j = 0
                    while (j < last) {
                        val k = kigouToK[r[j + 1].trim()]
                        if (k != null) schedule[staffIndex][j] = k
                        j++
                    }
                }
            }
            rr++
        }
        val report = UnifiedViolationChecker.check(state, schedule)
        val log = MirrorLog(tag = "CSVImport", message = "CSV取込: staff一致 ${matched}行")
        val logs = ArrayList<MirrorLog>()
        logs.add(log)
        logs.addAll(report.logs)
        return ScheduleRunResult(schedule, report.copy(logs = logs))
    }
}

private fun appendCsvRow(out: StringBuilder, values: List<String>) {
    var idx = 0
    while (idx < values.size) {
        if (idx > 0) out.append(',')
        out.append(csvEscapeCell(values[idx]))
        idx++
    }
    out.append('\n')
}

private fun csvEscapeCell(value: String): String {
    var mustQuote = false
    for (ch in value) {
        if (ch == ',' || ch == '"' || ch == '\n' || ch == '\r') {
            mustQuote = true
            break
        }
    }
    val escaped = value.replace("\"", "\"\"")
    return if (mustQuote) "\"$escaped\"" else escaped
}

private fun parseCsvRows(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    val row = ArrayList<String>()
    val cell = StringBuilder()
    var inQuote = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inQuote && c == '"' && i + 1 < text.length && text[i + 1] == '"') {
            cell.append('"')
            i++
        } else if (c == '"') {
            inQuote = !inQuote
        } else if (!inQuote && c == ',') {
            row.add(cell.toString())
            cell.setLength(0)
        } else if (!inQuote && (c == '\n' || c == '\r')) {
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            row.add(cell.toString())
            cell.setLength(0)
            rows.add(ArrayList(row))
            row.clear()
        } else {
            cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(ArrayList(row))
    }
    return rows
}
