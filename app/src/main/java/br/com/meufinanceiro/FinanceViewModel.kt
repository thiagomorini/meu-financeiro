package br.com.meufinanceiro

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.meufinanceiro.data.FinanceDatabase
import br.com.meufinanceiro.data.ImportService
import br.com.meufinanceiro.data.ItauParser
import kotlinx.coroutines.launch

class FinanceViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = FinanceDatabase.get(app).transactions()
    private val importer = ImportService(app)
    val rows = dao.observeAll()
    var previewResult by androidx.compose.runtime.mutableStateOf<ItauParser.Result?>(null)
        private set
    var error by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var busy by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun preview(uri: Uri) = viewModelScope.launch {
        busy = true; error = null; previewResult = null
        try { previewResult = importer.preview(uri) }
        catch (e: Exception) { error = "Falha ao ler PDF: ${e.message?.take(140)}" }
        finally { busy = false }
    }
    fun discard() { previewResult = null }
    fun confirm() = viewModelScope.launch {
        val result = previewResult ?: return@launch
        if (result.warnings.any { it.contains("idênticos") }) return@launch
        busy = true
        try {
            val inserted = dao.insertAll(result.rows).count { it != -1L }
            error = "$inserted movimentações novas salvas; duplicadas foram ignoradas."
            previewResult = null
        } catch (e: Exception) { error = "Falha ao salvar lançamentos: ${e.message?.take(120)}" }
        finally { busy = false }
    }
}
