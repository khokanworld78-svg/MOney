package com.moneytracker

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

val ACCOUNTS = listOf("SBI", "Slice", "Cash")
val DISP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun rupees(p: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(p / 100.0)

fun toPaise(s: String): Long? =
    s.replace(",", "").replace("₹", "").trim().toBigDecimalOrNull()
        ?.multiply(BigDecimal(100))?.setScale(0, RoundingMode.HALF_UP)?.toLong()

fun fmtTs(ms: Long): String = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date(ms))

/** Balances are always derived from opening balance minus the transaction history. */
fun balances(accs: List<Account>, tx: List<Txn>): Map<String, Long> =
    ACCOUNTS.associateWith { n -> (accs.find { it.name == n }?.openingPaise ?: 0L) - tx.filter { it.account == n }.sumOf { it.amountPaise } }

/** Running "balance after payment" per transaction id. */
fun runningBalances(all: List<Txn>, opening: Map<String, Long>): Map<String, Long> {
    val run = ACCOUNTS.associateWith { opening[it] ?: 0L }.toMutableMap()
    val out = HashMap<String, Long>()
    for (t in all.sortedWith(compareBy({ it.date }, { it.time }))) {
        run[t.account] = (run[t.account] ?: 0L) - t.amountPaise
        out[t.id] = run[t.account] ?: 0L
    }
    return out
}
