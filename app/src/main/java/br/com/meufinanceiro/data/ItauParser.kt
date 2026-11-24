package br.com.meufinanceiro.data

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Conservative parser for Itaú's text-based PDFs. Never infer missing transactions. */
object ItauParser {
    enum class DocumentType { CHECKING, CREDIT_CARD, UNKNOWN }
    data class InvoiceSummary(val amountCents: Long?, val dueDate: String?, val issueDate: String?)
    enum class InvoiceKind { PURCHASE, INSTALLMENT, INTERNATIONAL, REFUND, FEE, FINANCING, FINANCING_REVERSAL, PAYMENT, FUTURE_INSTALLMENT, OTHER }
    data class InvoiceEntry(
        val date: String, val description: String, val amountCents: Long,
        val kind: InvoiceKind, val installmentNumber: Int? = null,
        val installmentTotal: Int? = null, val section: String
    )
    data class InvoiceAudit(
        val domesticCents: Long?, val servicesCents: Long?, val currentCents: Long?,
        val internationalCents: Long?, val futureCents: Long?, val nextInvoiceCents: Long?,
        val computedDomesticCents: Long, val computedServicesCents: Long,
        val computedInternationalCents: Long, val computedFutureCents: Long
    )
    data class Result(
        val type: DocumentType,
        val rows: List<TransactionEntity>,
        val warnings: List<String>,
        val invoice: InvoiceSummary? = null,
        val candidateCount: Int = 0,
        val rejectedCount: Int = 0,
        val invoiceEntries: List<InvoiceEntry> = emptyList(),
        val invoiceAudit: InvoiceAudit? = null
    )

    private val fullDate = Regex("^(\\d{2}/\\d{2}/\\d{4})\\s+(.+)$")
    private val amount = Regex("-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}|-?\\d+,\\d{2}")
    private val isoFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)

    fun detect(text: String): DocumentType = when {
        text.contains("Lançamentos: compras e saques", true) || text.contains("Compras parceladas - próximas faturas", true) -> DocumentType.CREDIT_CARD
        text.contains("Saldo em conta", true) && text.contains("Lançamentos", true) -> DocumentType.CHECKING
        else -> DocumentType.UNKNOWN
    }

    fun parse(text: String, source: String): Result = when (detect(text)) {
        DocumentType.CHECKING -> parseChecking(text, source)
        DocumentType.CREDIT_CARD -> parseInvoice(text)
        DocumentType.UNKNOWN -> Result(DocumentType.UNKNOWN, emptyList(), listOf("Documento não reconhecido; nenhum lançamento foi salvo."))
    }

    private fun parseChecking(text: String, source: String): Result {
        val warnings = mutableListOf<String>()
        val rows = mutableListOf<TransactionEntity>()
        val lines = text.lines()
        var insideMovements = false
        var candidates = 0
        var rejected = 0
        var lastRowIndex = -1
        var previousWasContinuation = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("Lançamentos", ignoreCase = true)) { insideMovements = true; lastRowIndex = -1; continue }
            if (!insideMovements) continue
            if (trimmed.startsWith("Informações adicionais", true) ||
                trimmed.startsWith("Cheque especial", true) ||
                trimmed.startsWith("Saldo em conta", true)) { insideMovements = false; continue }
            if (trimmed.startsWith("Data ", true) || trimmed.isBlank()) { lastRowIndex = -1; continue }
            val match = fullDate.find(trimmed)
            if (match != null) {
                lastRowIndex = -1
                val tail = match.groupValues[2]
                // Dates with only a balance (right-hand column) are not transactions.
                val values = amount.findAll(tail).toList()
                if (values.isEmpty()) continue
                val before = tail.substring(0, values.first().range.first).trim()
                if (before.isBlank()) continue
                candidates++
                if (values.size != 1) { rejected++; continue } // ambiguous value + balance
                val date = try { LocalDate.parse(match.groupValues[1], isoFormatter).toString() }
                    catch (_: Exception) { rejected++; continue }
                val cents = try { parseCents(values.single().value) }
                    catch (_: Exception) { rejected++; continue }
                if (cents == 0L || before.length < 4 || before.contains("Saldo (R$)", true)) { rejected++; continue }
                val key = "$source|$date|${before.uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")}|$cents"
                rows.add(TransactionEntity(date = date, description = before.replace(Regex("\\s+"), " "),
                    amountCents = cents, source = source, fingerprint = sha256(key)))
                lastRowIndex = rows.lastIndex
                previousWasContinuation = false
            } else if (lastRowIndex >= 0 && line.isNotBlank() && !line.contains("R$")) {
                // Continuation lines are only appended when they contain no monetary value or new date.
                // PDF column extraction can interleave unrelated text: keep descriptions conservative.
                val continuation = trimmed
                if (continuation.length in 2..60 && !continuation.contains(Regex("\\d{2}/\\d{2}")) &&
                    !continuation.contains(Regex("\\d+,\\d{2}")) && !previousWasContinuation) {
                    val original = rows[lastRowIndex]
                    val updatedDescription = "${original.description} $continuation"
                    val key = "$source|${original.date}|${updatedDescription.uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")}|${original.amountCents}"
                    rows[lastRowIndex] = original.copy(description = updatedDescription, fingerprint = sha256(key))
                    previousWasContinuation = true
                }
            }
        }
        if (rows.isEmpty()) warnings.add("Nenhuma movimentação reconhecida no extrato.")
        if (rejected > 0) warnings.add("$rejected linhas ambíguas ou inválidas foram excluídas. Revise o extrato.")
        val repeated = rows.groupBy { it.fingerprint }.count { it.value.size > 1 }
        if (repeated > 0) warnings.add("Há $repeated grupos de lançamentos idênticos; importação bloqueada para evitar perda de operações reais.")
        return Result(DocumentType.CHECKING, rows, warnings, candidateCount = candidates, rejectedCount = rejected)
    }

    /** Invoice parsing is PREVIEW ONLY. Entries are never inserted into checking transactions. */
    private fun parseInvoice(text: String): Result {
        val due = Regex("Vencimento:\\s*(\\d{2}/\\d{2}/\\d{4})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        val issue = Regex("Emissão:\\s*(\\d{2}/\\d{2}/\\d{4})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        val invoiceAmount = summaryAmount(text, "Total desta fatura")
        val year = issue?.takeLast(4)?.toIntOrNull() ?: 0
        val issueMonth = issue?.substring(3, 5)?.toIntOrNull() ?: 0
        val entries = mutableListOf<InvoiceEntry>()
        val warnings = mutableListOf<String>()
        var section = ""
        var rejected = 0
        var candidates = 0
        // One logical transaction per line. PDF extractors that mix two columns must be rejected,
        // never silently accepted. No transaction dates or balances are inferred from headers.
        val entryLine = Regex("^(\\d{2})/(\\d{2})\\s+(.+?)\\s+(-?[\\d.]+,\\d{2})\\s*$")
        val installment = Regex("(?:^|\\s)(\\d{2})/(\\d{2})$")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.equals("Pagamentos efetuados", true) -> { section = "PAYMENTS"; continue }
                line.equals("Lançamentos: compras e saques", true) -> { section = "PURCHASES"; continue }
                line.equals("Lançamentos internacionais", true) -> { section = "INTERNATIONAL"; continue }
                line.equals("Lançamentos: produtos e serviços", true) -> { section = "SERVICES"; continue }
                line.equals("Compras parceladas - próximas faturas", true) -> { section = "FUTURE"; continue }
                line.startsWith("Limites de crédito", true) || line.startsWith("Encargos cobrados", true) -> { section = ""; continue }
                line.startsWith("Total dos lançamentos atuais", true) -> { section = ""; continue }
            }
            if (section.isEmpty()) continue
            val match = entryLine.matchEntire(line) ?: continue
            candidates++
            val day = match.groupValues[1].toIntOrNull() ?: 0
            val month = match.groupValues[2].toIntOrNull() ?: 0
            val rawDescription = match.groupValues[3].trim()
            val cents = runCatching { parseCents(match.groupValues[4]) }.getOrNull()
            if (day !in 1..31 || month !in 1..12 || cents == null || year == 0 ||
                rawDescription.length < 3 || rawDescription.contains("VALOR EM R$", true)) {
                rejected++; continue
            }
            val dateYear = if (month > issueMonth && issueMonth > 0) year - 1 else year
            val date = runCatching { LocalDate.of(dateYear, month, day).toString() }.getOrNull()
            if (date == null) { rejected++; continue }
            val inst = installment.find(rawDescription)
            val n = inst?.groupValues?.get(1)?.toIntOrNull()
            val total = inst?.groupValues?.get(2)?.toIntOrNull()
            if (n != null && total != null && (n < 1 || total < n || total > 99)) { rejected++; continue }
            val description = rawDescription.replace(Regex("\\s+"), " ")
            val kind = when (section) {
                "PAYMENTS" -> InvoiceKind.PAYMENT
                "FUTURE" -> InvoiceKind.FUTURE_INSTALLMENT
                "INTERNATIONAL" -> InvoiceKind.INTERNATIONAL
                "SERVICES" -> when {
                    description.contains("ESTORNO", true) -> InvoiceKind.REFUND
                    description.contains("CANC ", true) || description.contains("CREDITO PARC", true) -> InvoiceKind.FINANCING_REVERSAL
                    description.contains("PARC AUTOM", true) -> InvoiceKind.FINANCING
                    description.contains("IOF", true) || description.contains("JUROS", true) -> InvoiceKind.FEE
                    else -> InvoiceKind.OTHER
                }
                else -> when {
                    cents < 0 -> InvoiceKind.REFUND
                    n != null -> InvoiceKind.INSTALLMENT
                    else -> InvoiceKind.PURCHASE
                }
            }
            entries += InvoiceEntry(date, description, cents, kind, n, total, section)
        }
        fun sum(group: String) = entries.filter { it.section == group }.sumOf { it.amountCents }
        val audit = InvoiceAudit(
            domesticCents = summaryAmount(text, "Lançamentos no cartão"),
            servicesCents = summaryAmount(text, "Lançamentos produtos e serviços"),
            currentCents = summaryAmount(text, "Total dos lançamentos atuais"),
            internationalCents = summaryAmount(text, "Total transações inter. em R$"),
            futureCents = summaryAmount(text, "Total para próximas faturas"),
            nextInvoiceCents = summaryAmount(text, "Próxima fatura"),
            computedDomesticCents = sum("PURCHASES"),
            computedServicesCents = sum("SERVICES"),
            computedInternationalCents = sum("INTERNATIONAL"),
            computedFutureCents = sum("FUTURE")
        )
        if (entries.isEmpty()) warnings += "Nenhum item individual reconhecido: verifique a extração de texto do PDF."
        if (rejected > 0) warnings += "$rejected linhas de fatura inválidas foram descartadas."
        if (audit.domesticCents != null && audit.domesticCents != audit.computedDomesticCents)
            warnings += "Compras nacionais não conciliam com o subtotal da fatura."
        if (audit.servicesCents != null && audit.servicesCents != audit.computedServicesCents)
            warnings += "Produtos e serviços não conciliam com o subtotal da fatura."
        if (audit.internationalCents != null && audit.internationalCents != audit.computedInternationalCents)
            warnings += "Compras internacionais não conciliam com o subtotal da fatura."
        if (audit.nextInvoiceCents != null && audit.nextInvoiceCents != audit.computedFutureCents)
            warnings += "Parcelas da próxima fatura não conciliam com o subtotal indicado."
        warnings += "Prévia somente: itens do cartão NÃO são adicionados à conta corrente."
        warnings += "Financiamentos, cancelamentos, estornos, pagamentos e parcelas futuras são exibidos separadamente; nenhum é tratado como nova despesa automaticamente."
        return Result(DocumentType.CREDIT_CARD, emptyList(), warnings,
            invoice = InvoiceSummary(invoiceAmount, due, issue),
            candidateCount = candidates, rejectedCount = rejected,
            invoiceEntries = entries, invoiceAudit = audit)
    }

    private fun summaryAmount(text: String, label: String): Long? {
        val regex = Regex(Regex.escape(label) + "\\s+(?:R\\$\\s*)?(-?[\\d.]+,\\d{2})", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.get(1)?.let { runCatching { parseCents(it) }.getOrNull() }
    }

    fun parseCents(value: String): Long = BigDecimal(value.replace(".", "").replace(',', '.'))
        .movePointRight(2).longValueExact()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }
}
