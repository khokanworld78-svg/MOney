package com.moneytracker

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainVM(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val dao = Db.get(ctx).dao()
    private val prefs = ctx.getSharedPreferences("sync", 0)

    val accounts = dao.accounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val txns = dao.txns().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val logs = dao.logs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var uri by mutableStateOf(prefs.getString("uri", null))
    var lastSync by mutableStateOf(prefs.getLong("last", 0L))
    var mapping by mutableStateOf(Mapping.load(prefs))
    var plan by mutableStateOf<Plan?>(null)
    var mode by mutableStateOf("BOTH")                       // BOTH | IMPORT | EXPORT
    val choices = mutableStateMapOf<String, Boolean>()       // conflict id -> true = keep Excel, false = keep app

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ACCOUNTS.forEach { dao.addAccount(Account(it)) }
            if (dao.lastLog()?.status == "STARTED") {
                dao.markInterrupted()
                status = "The previous sync was interrupted. No data was lost - run Sync Now to continue safely."
            }
        }
    }

    private fun launchGuard(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        busy = true
        try { block() } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            status = "Error: $msg"
            dao.log(SyncLog(at = System.currentTimeMillis(), status = "ERROR", message = msg))
        } finally { busy = false }
    }

    fun addExpense(t: Txn) { viewModelScope.launch(Dispatchers.IO) {
        dao.upsert(t); status = "Saved ${rupees(t.amountPaise)} from ${t.account}."
        if (uri != null) autoSync()
    } }

    fun setOpening(n: String, p: Long) { viewModelScope.launch(Dispatchers.IO) { dao.upsertAccount(Account(n, p)); status = "Opening balance updated." } }

    fun setFile(u: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {}
        prefs.edit().putString("uri", u.toString()).remove("hash").apply()
        uri = u.toString(); plan = null; status = "Workbook connected. Tap Sync Now to review changes."
    }

    fun saveMapping(sheet: String, spec: String): Boolean {
        val m = Mapping.parse(sheet, spec) ?: return false
        prefs.edit().putString("sheet", m.sheet).putString("cols", m.spec()).apply(); mapping = m; return true
    }

    private fun readBytes(): ByteArray =
        ctx.contentResolver.openInputStream(Uri.parse(uri!!))?.use { it.readBytes() } ?: throw IOException("Cannot open the workbook. Re-select it with Change File.")

    private suspend fun buildPlan(): Plan = Excel.plan(readBytes(), mapping, dao.txnsNow(), prefs.getString("hash", "")!!)

    fun preview(m: String) { launchGuard {
        mode = m; choices.clear(); val p = buildPlan(); plan = p
        status = if (p.fromExcel.isEmpty() && p.toExcel.isEmpty() && p.conflicts.isEmpty() && !p.external) "Already in sync." else "Review the changes below, then tap Apply."
    } }

    fun apply() { launchGuard { doApply() } }

    private suspend fun doApply() {
        val p0 = plan ?: return
        val p = buildPlan()
        if (p.hash != p0.hash) { plan = p; status = "The file changed since the preview. Please review again."; return }
        val now = System.currentTimeMillis()
        val logId = dao.log(SyncLog(at = now, status = "STARTED", message = "Mode $mode"))
        try {
            val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
            if (p.bytes.isNotEmpty()) File(dir, "backup-$now.xlsx").writeBytes(p.bytes)      // backup before any change
            val imp = mode != "EXPORT"; val exp = mode != "IMPORT"
            if (imp) {
                dao.upsertAll(p.fromExcel)
                p.conflicts.filter { choices[it.id] == true }.forEach { dao.upsert(it.excel.copy(updatedAt = now)) }
                val accs = dao.accountsNow()
                p.opening.forEach { (n, v) -> accs.find { it.name == n }?.takeIf { it.openingPaise == 0L }?.let { dao.upsertAccount(it.copy(openingPaise = v)) } }
            }
            if (exp) {
                val all = dao.txnsNow(); val op = dao.accountsNow().associate { it.name to it.openingPaise }
                val keepApp = p.conflicts.filter { choices[it.id] == false }.map { it.id }.toSet()
                val out = Excel.write(p.bytes, mapping, p, all, op, keepApp)
                ctx.contentResolver.openOutputStream(Uri.parse(uri!!), "wt")?.use { it.write(out) } ?: throw IOException("Cannot write to the workbook.")
                if (Excel.sha(readBytes()) != Excel.sha(out)) throw IOException("Verification failed. A backup is in the app's backups folder.")
                prefs.edit().putString("hash", Excel.sha(out)).apply()
            } else prefs.edit().putString("hash", p.hash).apply()
            lastSync = System.currentTimeMillis(); prefs.edit().putLong("last", lastSync).apply()
            val msg = "Imported ${if (imp) p.fromExcel.size else 0}, exported ${if (exp) p.toExcel.size else 0}, conflicts ${p.conflicts.size}"
            dao.finish(logId, "DONE", msg); status = "Sync complete. $msg"; plan = null; choices.clear()
        } catch (e: Exception) { dao.finish(logId, "ERROR", e.message ?: e.toString()); throw e }
    }

    private suspend fun autoSync() {
        try {
            val p = buildPlan()
            if (!p.external && p.conflicts.isEmpty() && p.fromExcel.isEmpty()) { plan = p; mode = "BOTH"; doApply() }
            else status = "Saved locally. The workbook has changes to review in Excel Sync."
        } catch (e: Exception) { status = "Saved locally. Excel update failed: ${e.message}" }
    }

    fun exportBackup(u: Uri) { launchGuard {
        val j = JSONObject()
        j.put("accounts", JSONArray().also { ar -> dao.accountsNow().forEach { ar.put(JSONObject().put("name", it.name).put("opening", it.openingPaise)) } })
        j.put("txns", JSONArray().also { ar -> dao.txnsNow().forEach { ar.put(JSONObject().put("id", it.id).put("date", it.date).put("time", it.time).put("description", it.description).put("amount", it.amountPaise).put("account", it.account).put("category", it.category).put("notes", it.notes)) } })
        ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(j.toString().toByteArray()) }
        status = "Backup saved."
    } }

    fun restoreBackup(u: Uri) { launchGuard {
        val j = JSONObject(String(ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }))
        val a = j.getJSONArray("accounts")
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); dao.upsertAccount(Account(o.getString("name"), o.getLong("opening"))) }
        val t = j.getJSONArray("txns")
        val list = (0 until t.length()).map { val o = t.getJSONObject(it); Txn(o.getString("id"), o.getString("date"), o.getString("time"), o.getString("description"), o.getLong("amount"), o.getString("account"), o.optString("category"), o.optString("notes")) }
        dao.upsertAll(list); status = "Restored ${list.size} transactions."
    } }
}
