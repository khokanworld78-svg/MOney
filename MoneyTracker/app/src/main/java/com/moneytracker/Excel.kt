package com.moneytracker

import android.content.SharedPreferences
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One-time column mapping: 0-based columns for Date, Time, Description, Amount, Account, Balance, TransactionID. */
class Mapping(val sheet: String, val cols: IntArray) {
    fun spec() = cols.joinToString(",") { (it + 1).toString() }
    companion object {
        fun parse(sheet: String, spec: String): Mapping? {
            val c = spec.split(",").mapNotNull { it.trim().toIntOrNull() }
            return if (c.size == 7 && c.all { it in 1..60 } && c.toSet().size == 7)
                Mapping(sheet.ifBlank { "Transactions" }, c.map { it - 1 }.toIntArray()) else null
        }
        fun load(p: SharedPreferences): Mapping =
            parse(p.getString("sheet", "Transactions")!!, p.getString("cols", "1,2,3,4,5,6,7")!!)
                ?: Mapping("Transactions", intArrayOf(0, 1, 2, 3, 4, 5, 6))
    }
}

class Conflict(val id: String, val app: Txn, val excel: Txn)
class ExRow(val row: Int, val id: String?, val t: Txn)
class Plan(
    val bytes: ByteArray, val hash: String,
    val fromExcel: List<Txn>, val toExcel: List<Txn>, val conflicts: List<Conflict>,
    val rowIds: Map<Int, String>, val opening: Map<String, Long>, val external: Boolean
)

object Excel {
    const val DAILY = "Daily Summary"
    const val ACCT = "Account Summary"
    private val HEAD = listOf("Date", "Time", "Description", "Amount", "Account", "Balance After Payment", "Transaction ID")
    private val fmt = DataFormatter()

    fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun open(b: ByteArray): XSSFWorkbook = if (b.isEmpty()) XSSFWorkbook() else XSSFWorkbook(ByteArrayInputStream(b))

    fun canon(s: String): String? {
        val x = s.lowercase()
        return when { "sbi" in x -> "SBI"; "slice" in x -> "Slice"; "cash" in x -> "Cash"; else -> null }
    }
    private fun text(c: Cell?): String = if (c == null) "" else fmt.formatCellValue(c).trim()
    private fun num(c: Cell?): Long? {
        if (c == null || text(c).isEmpty()) return null
        return try { java.math.BigDecimal.valueOf(c.numericCellValue).multiply(java.math.BigDecimal(100)).setScale(0, java.math.RoundingMode.HALF_UP).toLong() }
        catch (e: Exception) { toPaise(text(c)) }
    }
    private fun date(c: Cell?): LocalDate? {
        if (c == null) return null
        try { if (DateUtil.isCellDateFormatted(c)) return c.dateCellValue.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() } catch (e: Exception) {}
        val s = text(c)
        for (p in listOf("dd-MM-yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "d-M-yyyy", "d/M/yyyy"))
            try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(p)) } catch (e: Exception) {}
        return null
    }
    private fun time(c: Cell?): String {
        if (c == null) return "00:00"
        try { if (DateUtil.isCellDateFormatted(c)) return c.dateCellValue.toInstant().atZone(ZoneId.systemDefault()).toLocalTime().format(HM) } catch (e: Exception) {}
        val m = Regex("(\\d{1,2}):(\\d{2})").find(text(c)) ?: return "00:00"
        return "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
    }
    private fun key(t: Txn) = "${t.date}|${t.time}|${t.description.trim().lowercase()}|${t.amountPaise}|${t.account}"
    private fun same(a: Txn, b: Txn) = key(a) == key(b)

    private fun read(sh: Sheet, m: Mapping): List<ExRow> {
        val out = mutableListOf<ExRow>()
        for (i in 1..sh.lastRowNum) {
            val r = sh.getRow(i) ?: continue
            val d = date(r.getCell(m.cols[0])) ?: continue
            val amt = num(r.getCell(m.cols[3])) ?: continue
            val acc = canon(text(r.getCell(m.cols[4]))) ?: continue
            val id = text(r.getCell(m.cols[6])).ifEmpty { null }
            out += ExRow(i, id, Txn(id ?: "", d.toString(), time(r.getCell(m.cols[1])), text(r.getCell(m.cols[2])), amt, acc))
        }
        return out
    }

    /** Read-only comparison of the workbook with the local database (nothing is modified). */
    fun plan(bytes: ByteArray, m: Mapping, local: List<Txn>, lastHash: String): Plan {
        val hash = sha(bytes)
        val wb = open(bytes)
        try {
            val sh = wb.getSheet(m.sheet)
            val rows = if (sh != null) read(sh, m) else emptyList()
            val byId = local.associateBy { it.id }
            val byKey = local.groupBy { key(it) }.mapValues { it.value.toMutableList() }
            val matched = mutableSetOf<String>()
            val from = mutableListOf<Txn>(); val conf = mutableListOf<Conflict>(); val ids = mutableMapOf<Int, String>()
            for (r in rows) {
                if (r.id != null && r.id in matched) continue            // duplicate row in Excel
                var l = r.id?.let { byId[it] }
                if (l == null) { val c = byKey[key(r.t)]; if (c != null) l = c.firstOrNull { it.id !in matched } }
                if (l != null && l.id !in matched) {
                    matched += l.id; ids[r.row] = l.id
                    if (r.id != null && !same(l, r.t)) conf += Conflict(l.id, l, r.t.copy(id = l.id))
                } else {
                    val n = r.t.copy(id = r.id ?: UUID.randomUUID().toString()); ids[r.row] = n.id; from += n
                }
            }
            val opening = mutableMapOf<String, Long>()
            val a = wb.getSheet(ACCT)
            if (a != null) for (i in 1..a.lastRowNum) {
                val r = a.getRow(i) ?: continue
                val n = canon(text(r.getCell(0))) ?: continue
                val v = num(r.getCell(1)) ?: continue
                opening[n] = v
            }
            return Plan(bytes, hash, from, local.filter { it.id !in matched }, conf, ids, opening, lastHash.isNotEmpty() && lastHash != hash)
        } finally { wb.close() }
    }

    private fun style(wb: Workbook, f: String): CellStyle =
        wb.createCellStyle().apply { setDataFormat(wb.creationHelper.createDataFormat().getFormat(f)) }
    private fun cell(r: Row, i: Int, st: CellStyle?): Cell = r.getCell(i) ?: r.createCell(i).also { if (st != null) it.cellStyle = st }
    private fun dt(s: String) = java.util.Date.from(LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant())
    private fun fresh(wb: Workbook, name: String, head: List<String>, bold: CellStyle): Sheet {
        val s = wb.getSheet(name) ?: wb.createSheet(name)
        for (i in s.lastRowNum downTo 0) { val r = s.getRow(i); if (r != null) s.removeRow(r) }
        val h = s.createRow(0)
        head.forEachIndexed { i, t -> val c = h.createCell(i); c.setCellValue(t); c.cellStyle = bold }
        return s
    }
    private fun fill(r: Row, m: Mapping, t: Txn, d: CellStyle, mo: CellStyle) {
        cell(r, m.cols[0], d).setCellValue(dt(t.date))
        cell(r, m.cols[1], null).setCellValue(t.time)
        cell(r, m.cols[2], null).setCellValue(t.description)
        cell(r, m.cols[3], mo).setCellValue(t.amountPaise / 100.0)
        cell(r, m.cols[4], null).setCellValue(t.account)
    }

    /** Writes the merged result into a copy of the workbook bytes (other sheets/formatting are kept). */
    fun write(bytes: ByteArray, m: Mapping, p: Plan, all: List<Txn>, opening: Map<String, Long>, keepApp: Set<String>): ByteArray {
        val wb = open(bytes)
        try {
            val dateSt = style(wb, "dd-mm-yyyy"); val moneySt = style(wb, "\"₹\"#,##0.00")
            val bold = wb.createCellStyle().apply { setFont(wb.createFont().apply { setBold(true) }) }
            val sh = wb.getSheet(m.sheet) ?: wb.createSheet(m.sheet)
            if (sh.physicalNumberOfRows == 0) {
                val h = sh.createRow(0)
                HEAD.forEachIndexed { i, t -> val c = h.createCell(m.cols[i]); c.setCellValue(t); c.cellStyle = bold }
            }
            val byId = all.associateBy { it.id }; val bal = runningBalances(all, opening)
            for ((row, id) in p.rowIds) {
                val r = sh.getRow(row) ?: continue
                cell(r, m.cols[6], null).setCellValue(id)
                val t = byId[id] ?: continue
                if (id in keepApp) fill(r, m, t, dateSt, moneySt)
                cell(r, m.cols[5], moneySt).setCellValue((bal[id] ?: 0L) / 100.0)
            }
            for (t in p.toExcel) {
                val r = sh.createRow(sh.lastRowNum + 1)
                fill(r, m, t, dateSt, moneySt)
                cell(r, m.cols[5], moneySt).setCellValue((bal[t.id] ?: 0L) / 100.0)
                cell(r, m.cols[6], null).setCellValue(t.id)
            }
            val ds = fresh(wb, DAILY, listOf("Date", "SBI Spending", "Slice Spending", "Cash Spending", "Total Spending"), bold)
            var n = 1
            for ((d, list) in all.groupBy { it.date }.toSortedMap()) {
                val r = ds.createRow(n++)
                cell(r, 0, dateSt).setCellValue(dt(d))
                ACCOUNTS.forEachIndexed { i, a -> cell(r, i + 1, moneySt).setCellValue(list.filter { it.account == a }.sumOf { it.amountPaise } / 100.0) }
                cell(r, 4, moneySt).setCellValue(list.sumOf { it.amountPaise } / 100.0)
            }
            val ac = fresh(wb, ACCT, listOf("Account", "Opening Balance", "Total Expenses", "Current Balance"), bold)
            ACCOUNTS.forEachIndexed { i, a ->
                val r = ac.createRow(i + 1)
                val o = opening[a] ?: 0L; val e = all.filter { it.account == a }.sumOf { it.amountPaise }
                r.createCell(0).setCellValue(a)
                cell(r, 1, moneySt).setCellValue(o / 100.0); cell(r, 2, moneySt).setCellValue(e / 100.0); cell(r, 3, moneySt).setCellValue((o - e) / 100.0)
            }
            val out = ByteArrayOutputStream(); wb.write(out); return out.toByteArray()
        } finally { wb.close() }
    }
}
