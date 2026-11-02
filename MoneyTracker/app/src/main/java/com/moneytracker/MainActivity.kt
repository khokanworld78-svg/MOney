package com.moneytracker

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.LocalTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme(); val ctx = LocalContext.current
            val cs = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
                     else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = cs) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

@Composable fun App(vm: MainVM = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val items = listOf("Dashboard" to Icons.Default.Home, "Add" to Icons.Default.Add, "Accounts" to Icons.Default.AccountBox,
        "History" to Icons.Default.List, "Excel Sync" to Icons.Default.Refresh)
    Scaffold(bottomBar = {
        NavigationBar { items.forEachIndexed { i, (n, ic) ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, n) }, label = { Text(n, maxLines = 1, fontSize = 10.sp) })
        } }
    }) { pad ->
        Box(Modifier.padding(pad).padding(12.dp).statusBarsPadding()) {
            when (tab) { 0 -> Dashboard(vm); 1 -> AddExpense(vm) { tab = 0 }; 2 -> AccountsScreen(vm); 3 -> History(vm); else -> SyncScreen(vm) }
        }
    }
}

@Composable fun TxRow(t: Txn) = ListItem(
    headlineContent = { Text(t.description) },
    supportingContent = { Text("${LocalDate.parse(t.date).format(DISP)} ${t.time} • ${t.account}" + if (t.category.isNotBlank()) " • ${t.category}" else "") },
    trailingContent = { Text("-" + rupees(t.amountPaise)) })

@Composable fun Dashboard(vm: MainVM) {
    val accs by vm.accounts.collectAsState(); val tx by vm.txns.collectAsState()
    val bal = balances(accs, tx); val today = LocalDate.now().toString(); val month = today.take(7)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Total Available Money"); Text(rupees(bal.values.sum()), style = MaterialTheme.typography.headlineMedium) } } }
        items(ACCOUNTS) { a -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (a == "Cash") "Cash in Hand" else "$a Bank"); Text(rupees(bal[a] ?: 0L)) } } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Today's expenses: " + rupees(tx.filter { it.date == today }.sumOf { it.amountPaise }))
            Text("This month: " + rupees(tx.filter { it.date.startsWith(month) }.sumOf { it.amountPaise })) } } }
        item { Text("Recent transactions", style = MaterialTheme.typography.titleMedium) }
        items(tx.take(8)) { TxRow(it) }
    }
}

@Composable fun AddExpense(vm: MainVM, done: () -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().format(DISP)) }
    var time by remember { mutableStateOf(LocalTime.now().format(HM)) }
    var desc by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    var acc by remember { mutableStateOf("SBI") }; var cat by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }; var err by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(date, { date = it }, label = { Text("Date DD-MM-YYYY") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(time, { time = it }, label = { Text("Time HH:MM") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(amt, { amt = it }, label = { Text("Amount (₹)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ACCOUNTS.forEach { FilterChip(selected = acc == it, onClick = { acc = it }, label = { Text(it) }) } }
        OutlinedTextField(cat, { cat = it }, label = { Text("Category (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val d = runCatching { LocalDate.parse(date.trim(), DISP) }.getOrNull()
            val tm = runCatching { LocalTime.parse(time.trim(), HM) }.getOrNull()
            val p = toPaise(amt)
            if (d == null) err = "Invalid date (use DD-MM-YYYY)"
            else if (tm == null) err = "Invalid time (use HH:MM, 24-hour)"
            else if (desc.isBlank()) err = "Enter a description"
            else if (p == null || p <= 0) err = "Enter a valid amount"
            else { vm.addExpense(Txn(date = d.toString(), time = tm.format(HM), description = desc.trim(), amountPaise = p, account = acc, category = cat.trim(), notes = notes.trim())); done() }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save expense") }
        if (vm.status.isNotEmpty()) Text(vm.status)
    }
}

@Composable fun AccountsScreen(vm: MainVM) {
    val accs by vm.accounts.collectAsState(); val tx by vm.txns.collectAsState(); val bal = balances(accs, tx)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ACCOUNTS.forEach { n ->
            val o = accs.find { it.name == n }?.openingPaise ?: 0L
            var v by remember(o) { mutableStateOf((o / 100.0).toString()) }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(n, style = MaterialTheme.typography.titleMedium)
                Text("Current balance: " + rupees(bal[n] ?: 0L))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(v, { v = it }, label = { Text("Opening balance (₹)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Button(onClick = { toPaise(v)?.let { vm.setOpening(n, it) } }) { Text("Save") }
                }
            } }
        }
    }
}

@Composable fun History(vm: MainVM) {
    val tx by vm.txns.collectAsState()
    LazyColumn { items(tx) { TxRow(it); HorizontalDivider() } }
}

@Composable fun RowScope.B(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.weight(1f), enabled = enabled) { Text(text, maxLines = 1, fontSize = 12.sp) }

@Composable fun SyncScreen(vm: MainVM) {
    val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) vm.setFile(u) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(xlsx)) { u: Uri? -> if (u != null) vm.setFile(u) }
    val bk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u: Uri? -> if (u != null) vm.exportBackup(u) }
    val rs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) vm.restoreBackup(u) }
    val logs by vm.logs.collectAsState()
    var showMap by remember { mutableStateOf(false) }
    val can = vm.uri != null && !vm.busy
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Workbook: " + (vm.uri?.let { Uri.decode(it).substringAfterLast('/') } ?: "none selected"))
        Text("Last sync: " + if (vm.lastSync == 0L) "never" else fmtTs(vm.lastSync))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { B("Sync Now", can) { vm.preview("BOTH") }; B("Import from Excel", can) { vm.preview("IMPORT") } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { B("Export to Excel", can) { vm.preview("EXPORT") }; B("Change Excel File") { open.launch(arrayOf(xlsx, "*/*")) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { B("New workbook") { create.launch("MoneyTracker.xlsx") }; B("Column mapping") { showMap = true } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { B("Backup app data") { bk.launch("moneytracker-backup.json") }; B("Restore backup") { rs.launch(arrayOf("application/json", "*/*")) } }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.status.isNotEmpty()) Text(vm.status, color = if (vm.status.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        vm.plan?.let { p -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Review changes (${vm.mode})", style = MaterialTheme.typography.titleMedium)
            if (p.external) Text("⚠ The workbook was modified outside this app since the last sync.", color = MaterialTheme.colorScheme.error)
            Text("New in Excel → app: ${p.fromExcel.size}")
            p.fromExcel.take(10).forEach { Text("  + ${LocalDate.parse(it.date).format(DISP)} ${it.description} ${rupees(it.amountPaise)} ${it.account}", fontSize = 12.sp) }
            Text("New in app → Excel: ${p.toExcel.size}")
            p.toExcel.take(10).forEach { Text("  + ${LocalDate.parse(it.date).format(DISP)} ${it.description} ${rupees(it.amountPaise)} ${it.account}", fontSize = 12.sp) }
            Text("Conflicts: ${p.conflicts.size}")
            p.conflicts.forEach { c ->
                Text("App: ${c.app.description} ${rupees(c.app.amountPaise)} ${c.app.account} ${c.app.date}\nExcel: ${c.excel.description} ${rupees(c.excel.amountPaise)} ${c.excel.account} ${c.excel.date}", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.choices[c.id] == false, onClick = { vm.choices[c.id] = false }, label = { Text("Keep app") })
                    FilterChip(selected = vm.choices[c.id] == true, onClick = { vm.choices[c.id] = true }, label = { Text("Keep Excel") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                B("Apply changes", !vm.busy && p.conflicts.all { vm.choices.containsKey(it.id) }) { vm.apply() }
                OutlinedButton(onClick = { vm.plan = null }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
            Text("A backup copy of the workbook is saved in the app before any change.", fontSize = 11.sp)
        } } }
        Text("Synchronization log", style = MaterialTheme.typography.titleMedium)
        logs.take(10).forEach { Text("${fmtTs(it.at)}  ${it.status}  ${it.message}", fontSize = 12.sp) }
    }
    if (showMap) {
        var sheet by remember { mutableStateOf(vm.mapping.sheet) }; var spec by remember { mutableStateOf(vm.mapping.spec()) }
        AlertDialog(onDismissRequest = { showMap = false }, title = { Text("Column mapping") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Row 1 is the header. Give column numbers (A=1, B=2...) for: Date, Time, Description, Amount, Account, Balance, Transaction ID.", fontSize = 12.sp)
                OutlinedTextField(sheet, { sheet = it }, label = { Text("Transactions sheet name") })
                OutlinedTextField(spec, { spec = it }, label = { Text("Columns, e.g. 1,2,3,4,5,6,7") }) } },
            confirmButton = { TextButton(onClick = { if (vm.saveMapping(sheet, spec)) showMap = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showMap = false }) { Text("Cancel") } })
    }
}
