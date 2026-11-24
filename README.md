# Meu Financeiro — v0.1.2 (código-fonte)

## Evolução da v0.1.1
- Prévia de itens de fatura com classificação separada: compras, parcelas, compras internacionais, pagamentos, estornos, encargos, refinanciamentos, cancelamentos e parcelas futuras.
- Extração de PDF com `sortByPosition = false` para preservar preferencialmente a ordem de fluxo do documento (o resultado varia por PDF).
- Auditoria de subtotais de compras nacionais, serviços, internacionais e parcelas futuras, com alertas quando divergentes.
- A tela mostra os grupos e os primeiros itens da fatura, **sem salvá-los** como movimentações da conta corrente.
- Testes unitários sintéticos sem informações bancárias reais.

## Limitações importantes
- **Não há APK**: este ambiente não possui SDK Android/Gradle disponíveis para compilação e execução.
- O parser usa texto extraído de PDF; layouts com colunas misturadas podem produzir itens incompletos ou incorretos. As divergências de subtotal devem ser tratadas como bloqueios de importação definitiva.
- A fatura ainda não tem persistência própria, conciliação entre faturas, identidade do cartão, estornos vinculados, tratamento de compras internacionais/IOF ou regras de parcelamento consolidadas. Esses recursos são necessários antes de liberar importação do cartão.
- A auditoria das parcelas futuras compara as linhas detalhadas com o subtotal "Próxima fatura", não com "Total para próximas faturas" (que inclui meses posteriores).
- A conta corrente continua com a lógica anterior, inclusive suas limitações conhecidas de duplicidades.
- O app ainda não oferece criptografia local ou backup criptografado; não o utilize para dados bancários sensíveis em produção.

## Como executar
Abra a pasta no Android Studio, sincronize as dependências e rode os testes unitários. Depois, execute em um dispositivo Android API 26+ e valide o comportamento da extração de texto antes de confirmar qualquer extrato.

## Privacidade
Nenhum PDF real, CPF, endereço, cartão ou dado bancário pessoal foi incluído no código-fonte ou ZIP.

## Compilar pelo GitHub Actions (sem computador)
1. Crie um repositório **privado** no GitHub.
2. Envie o conteúdo desta pasta para a raiz do repositório, incluindo `.github/workflows/android-apk.yml`.
3. Abra **Actions > Gerar APK Android > Run workflow > Run workflow**.
4. Aguarde o job terminar. Se ficar verde, abra a execução e baixe o artefato `MeuFinanceiro-v0.1.2-debug-apk` (um ZIP contendo o APK).
5. Extraia o ZIP no Android e instale o APK de desenvolvimento.

**Atenção:** o projeto ainda não foi compilado/validado. Se o job falhar, abra a etapa vermelha e compartilhe o log de erro (sem dados pessoais) para correção. Não envie PDFs bancários ao repositório. APK debug é somente para testes.
