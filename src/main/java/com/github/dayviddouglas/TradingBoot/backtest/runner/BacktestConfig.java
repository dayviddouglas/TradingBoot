package com.github.dayviddouglas.TradingBoot.backtest.runner;

/**
 * Configuração imutável de uma execução de backtest.
 *
 * Centraliza os parâmetros utilizados pelo {@link BacktestRunner} e pelo {@link SimpleBacktester},
 * eliminando constantes dispersas e permitindo ajustes sem alterar a lógica de execução.
 *
 * A instância {@link #DEFAULT} representa a configuração padrão utilizada pelo
 * {@link BacktestRunner} quando executado de forma standalone.
 *
 * @param dataDir        diretório base contendo os arquivos de histórico de candles
 *                       no formato {@code {symbol}_60.json}
 * @param strategiesFile caminho do arquivo strategies.json com os profiles de configuração
 * @param profitPayout   payout fixo simulado para trades vencedores; representa o retorno
 *                       em R por unidade de stake (ex: {@code 0.70} = 70% de retorno)
 * @param tradeDuration  duração padrão do trade em barras, utilizada quando o profile
 *                       não possui {@code TradeConfig} configurado
 */
public record BacktestConfig(
        String dataDir,
        String strategiesFile,
        double profitPayout,
        int tradeDuration
) {

    /**
     * Configuração padrão utilizada pelo {@link BacktestRunner} standalone.
     * Aponta para o diretório de histórico local, o strategies.json do projeto
     * e utiliza payout de 70% com duração padrão de 15 barras.
     */
    public static final BacktestConfig DEFAULT = new BacktestConfig(
            "data/history",
            "src/main/resources/configs/strategies.json",
            0.70,
            15
    );

    /**
     * Verifica se o payout configurado é um valor válido para simulação.
     * Valores fora do intervalo {@code (0.0, 1.0]} indicam configuração incorreta.
     *
     * @return {@code true} se {@code profitPayout} estiver entre 0 (exclusivo) e 1 (inclusivo)
     */
    public boolean hasValidPayout() {
        return profitPayout > 0.0 && profitPayout <= 1.0;
    }
}