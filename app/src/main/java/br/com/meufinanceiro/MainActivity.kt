package br.com.meufinanceiro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.meufinanceiro.data.TransactionEntity
import java.text.NumberFormat
import java.util.Locale

private val Bg = Color(0xFF0B1220)
private val Surface = Color(0xFF172338)
private val Accent = Color(0xFF36D6C0)
private val Income = Color(0xFF43C98B)
private val Expense = Color(0xFFF07A82)
private val Muted = Color(0xFF9BAEC5)

class MainActivity : ComponentActivity() {
    private var receivedUri by mutableStateOf<Uri?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receivedUri = incoming(intent)
        setContent {
            val vm: FinanceViewModel = viewModel()
            val rows by vm.rows.collectAsState(initial = emptyList())
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.preview(uri)
            }
            LaunchedEffect(receivedUri) { receivedUri?.let { vm.preview(it); receivedUri = null } }
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface, onBackground = Color.White, onSurface = Color.White)) {
                Dashboard(rows, vm, onImport = { launcher.launch(arrayOf("application/pdf")) })
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receivedUri = incoming(intent) }
    @Suppress("DEPRECATION")
    private fun incoming(intent: Intent?): Uri? = when(intent?.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
        else -> null
    }
}

@Composable
private fun Dashboard(rows: List<TransactionEntity>, vm: FinanceViewModel, onImport: () -> Unit) {
    val money = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")) }
    val incoming = rows.filter { it.amountCents > 0 }.sumOf { it.amountCents }
    val outgoing = rows.filter { it.amountCents < 0 }.sumOf { -it.amountCents }
    val preview = vm.previewResult
    Column(Modifier.fillMaxSize().background(Bg).padding(20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("MEU FINANCEIRO", color = Accent, style = MaterialTheme.typography.labelMedium)
        Text("Visão geral", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Movimentação importada", color = Muted)
                Spacer(Modifier.height(8.dp))
                Text(money.format((incoming - outgoing) / 100.0), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Resultado dos lançamentos importados — não é saldo bancário", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("Entradas", color = Muted); Text(money.format(incoming / 100.0), color = Income) }
                    Column { Text("Saídas", color = Muted); Text(money.format(outgoing / 100.0), color = Expense) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Importar PDF Itaú")
        }
        if (vm.busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        vm.error?.let { Text(it, color = Expense, modifier = Modifier.padding(vertical = 8.dp)) }
        if (preview != null) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Prévia: ${if (preview.type == br.com.meufinanceiro.data.ItauParser.DocumentType.CREDIT_CARD) "Fatura Itaú" else "Extrato Itaú"} · ${preview.rows.size} lançamentos", fontWeight = FontWeight.Bold)
                    preview.invoice?.let { invoice ->
                        Text("Total da fatura: ${invoice.amountCents?.let { money.format(it / 100.0) } ?: "não identificado"}", color = Accent)
                        Text("Emissão: ${invoice.issueDate ?: "não identificada"} · Vencimento: ${invoice.dueDate ?: "não identificado"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.type == br.com.meufinanceiro.data.ItauParser.DocumentType.CREDIT_CARD) {
                        Text("Itens identificados: ${preview.invoiceEntries.size} · Linhas rejeitadas: ${preview.rejectedCount}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        val groups = preview.invoiceEntries.groupBy { it.kind }
                        groups.forEach { (kind, items) ->
                            Text("${kind.name}: ${items.size}", color = Accent, style = MaterialTheme.typography.bodySmall)
                        }
                        preview.invoiceEntries.take(8).forEach { entry ->
                            Text("${entry.date} · ${entry.description.take(30)} · ${money.format(entry.amountCents / 100.0)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Fatura em modo somente leitura. A gravação de itens do cartão será liberada após conciliação.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.type == br.com.meufinanceiro.data.ItauParser.DocumentType.CHECKING) {
                        Text("Candidatas: ${preview.candidateCount} · Rejeitadas: ${preview.rejectedCount}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    preview.warnings.forEach { Text(it, color = Color(0xFFF2BD65), style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    Text("Confira os valores e as descrições antes de salvar.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    preview.rows.take(4).forEach { Text("${it.date} · ${it.description.take(32)} · ${money.format(it.amountCents / 100.0)}", style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { vm.discard() }) { Text("Cancelar") }
                        Button(onClick = { vm.confirm() }, enabled = preview.rows.isNotEmpty() && preview.warnings.none { it.contains("idênticos") }) { Text("Confirmar") }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Movimentações", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) Text("Nenhuma movimentação importada.", color = Muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.description, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        Text(item.date, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(money.format(item.amountCents / 100.0), color = if (item.amountCents >= 0) Income else Expense)
                }
            }
        }
    }
}
