package com.github.dayviddouglas.TradingBot.backtest;

/**
 * Configuração imutável de um backtest.
 *
 * Centraliza os parâmetros que antes estavam dispersos como
 * constantes no BacktestRunner, facilitando ajustes sem
 * alterar a lógica de execução.
 *
 * @param dataDir         diretório com os arquivos de histórico
 * @param strategiesFile  caminho do strategies.json
 * @param profitPayout    payout fixo simulado para vitórias (ex: 0.95)
 * @param tradeDuration   duração padrão do trade em barras quando não configurado
 */
public record BacktestConfig(
        String dataDir,
        String strategiesFile,
        double profitPayout,
        int tradeDuration
) {
    /**
     * Configuração padrão usada pelo BacktestRunner standalone.
     */
    public static final BacktestConfig DEFAULT = new BacktestConfig(
            "data/history",
            "src/main/resources/configs/strategies.json",
            0.70,
            15
    );

    /**
     * Verifica se o payout configurado é válido.
     *
     * @return true se profitPayout está entre 0 e 1
     */
    public boolean hasValidPayout() {
        return profitPayout > 0.0 && profitPayout <= 1.0;
    }
}