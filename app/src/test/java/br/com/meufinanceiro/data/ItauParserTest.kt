package br.com.meufinanceiro.data

import org.junit.Assert.*
import org.junit.Test

class ItauParserTest {
    @Test fun moneyUsesIntegerCents() {
        assertEquals(-1200000L, ItauParser.parseCents("-12.000,00"))
        assertEquals(1906531L, ItauParser.parseCents("19.065,31"))
    }
    @Test fun checkingSkipsBalanceOnlyAndPreservesSameDayTransactions() {
        val text = """Saldo em conta
Lançamentos
Data Lançamentos Descrição Valor (R$) Saldo (R$)
18/11/2026                                            752,66
18/11/2026 COMPRA DÉBITO LOJA A -50,00
18/11/2026 COMPRA DÉBITO LOJA B -12,00
"""
        val r = ItauParser.parse(text, "ITAU_CHECKING")
        assertEquals(ItauParser.DocumentType.CHECKING, r.type)
        assertEquals(2, r.rows.size)
        assertEquals(2, r.candidateCount)
        assertEquals(-5000L, r.rows.first().amountCents)
    }
    @Test fun invoiceExtractsHeaderButNeverImportsPurchases() {
        val text = """Lançamentos: compras e saques
Emissão: 08/11/2026
Vencimento: 15/11/2026
= Total desta fatura 19.065,31
07/10 COMPRA EXEMPLO 120,00"""
        val r = ItauParser.parse(text, "ITAU_CARD")
        assertEquals(ItauParser.DocumentType.CREDIT_CARD, r.type)
        assertTrue(r.rows.isEmpty())
        assertEquals(1906531L, r.invoice?.amountCents)
        assertEquals("15/11/2026", r.invoice?.dueDate)
    }
    @Test fun duplicateMovementsWarnInsteadOfSilentlyDropping() {
        val text = "Saldo em conta\nLançamentos\n22/11/2026 COMPRA DÉBITO LOJA -10,00\n22/11/2026 COMPRA DÉBITO LOJA -10,00"
        assertTrue(ItauParser.parse(text, "ITAU_CHECKING").warnings.any { it.contains("idênticos") })
    }
}
