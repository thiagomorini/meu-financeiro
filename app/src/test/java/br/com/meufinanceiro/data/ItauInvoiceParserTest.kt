package br.com.meufinanceiro.data

import org.junit.Assert.*
import org.junit.Test

class ItauInvoiceParserTest {
    @Test fun separatesPurchasesPaymentsRefundsAndFutureInstallments() {
        val sample = """
            Emissão: 08/11/2026
            Vencimento: 15/11/2026
            Total desta fatura 1.250,00
            Pagamentos efetuados
            14/10 Pagamento via conta -100,00
            Lançamentos: compras e saques
            07/10 LIVRARIA 01/03 50,00
            08/10 MERCADO 75,00
            Lançamentos: produtos e serviços
            09/10 ESTORNO ENCARGOS -10,00
            10/10 PARC AUTOMATIC 01/12 300,00
            11/10 CANC PARC DE R 01/12 -300,00
            Compras parceladas - próximas faturas
            07/10 LIVRARIA 02/03 50,00
            Total para próximas faturas 50,00
            Limites de crédito Valor em R$
        """.trimIndent()
        val result = ItauParser.parse(sample, "ITAU")
        assertEquals(ItauParser.DocumentType.CREDIT_CARD, result.type)
        assertTrue(result.rows.isEmpty())
        assertEquals(7, result.invoiceEntries.size)
        assertEquals(1, result.invoiceEntries.count { it.kind == ItauParser.InvoiceKind.FUTURE_INSTALLMENT })
        assertEquals(1, result.invoiceEntries.count { it.kind == ItauParser.InvoiceKind.FINANCING_REVERSAL })
        assertEquals(1, result.invoiceEntries.count { it.kind == ItauParser.InvoiceKind.REFUND })
        assertEquals(12500L, result.invoiceAudit?.computedDomesticCents)
        assertEquals(5000L, result.invoiceAudit?.computedFutureCents)
    }

    @Test fun doesNotSaveCardTransactionsAsChecking() {
        val result = ItauParser.parse("Emissão: 08/11/2026\nLançamentos: compras e saques\n01/11 TESTE 10,00\nCompras parceladas - próximas faturas", "ITAU")
        assertTrue(result.rows.isEmpty())
    }
}
