package br.com.meufinanceiro.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImportService(private val context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }
    suspend fun preview(uri: Uri): ItauParser.Result = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Não foi possível abrir o arquivo")
        require(bytes.size <= 15 * 1024 * 1024) { "PDF acima do limite de 15 MB" }
        val text = PDDocument.load(bytes).use { PDFTextStripper().apply { sortByPosition = false }.getText(it) }
        // No raw statement or personal data is logged or uploaded.
        ItauParser.parse(text, "ITAU_CHECKING")
    }
}
