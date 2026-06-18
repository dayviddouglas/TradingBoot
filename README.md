<div align="center">

<img src="assets/logo_tradingboot.png" alt="Logo_tradingboot" width="400" height="200">

**Sistema de estudo em trading algorítmico com backtest, regime de mercado,
execução automatizada e relatórios operacionais.**

![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-brightgreen?logo=springboot)
![Maven](https://img.shields.io/badge/Maven-Build-red?logo=apachemaven)
![WebSocket](https://img.shields.io/badge/WebSocket-Deriv-blue?logo=websocket)
![License](https://img.shields.io/badge/License-MIT-blue)

<a href="https://dayviddouglas.github.io/TradingBoot/" title="Abrir documentação técnica do TradingBoot">
<img src="https://img.shields.io/badge/Explorar-JavaDoc-24292F?style=for-the-badge&logo=github&logoColor=white" alt="Explorar JavaDoc">
</a>
</div>

---


Grande parte do conteúdo sobre trading é construída em cima de recortes,
opiniões, exemplos isolados e interpretações que raramente passam por
validação estatística rigorosa. Estratégias parecem funcionar quando
observadas em gráficos escolhidos a dedo, em períodos específicos ou
em condições que dificilmente se repetem da mesma forma no mercado real.
Quando confrontadas com histórico amplo, payout variável, custos implícitos,
mudanças de regime e execução em tempo real, muitas dessas ideias perdem
consistência rapidamente.

TradingBoot nasceu como uma resposta direta a esse problema: criar uma
base técnica onde hipóteses possam ser testadas com dados reais, regras
objetivas, execução rastreável e resultados auditáveis. Em vez de partir
da promessa de lucro, o projeto parte de uma premissa mais simples e mais
honesta: antes de automatizar uma operação, é preciso medir se ela faz
sentido; antes de confiar em uma estratégia, é preciso observar seu
comportamento em diferentes ativos, em diferentes condições de mercado e
sob critérios explícitos de risco.

Na prática, isso significa transformar o processo de "achar setups no gráfico"
em um fluxo disciplinado de pesquisa. O sistema recebe ticks em tempo real,
constrói candles OHLC, classifica o regime de mercado, avalia estratégias
técnicas, valida risco e payout, executa contratos na Deriv e registra cada
decisão em relatórios que permitem auditoria posterior. O mesmo núcleo lógico
também pode ser usado em backtests com histórico salvo em disco, o que ajuda
a reduzir a distância entre aquilo que parece funcionar no papel e aquilo que
sobrevive quando exposto ao runtime real.

Mais do que um robô de execução, o TradingBoot funciona como um laboratório
de hipóteses quantitativas. Ele foi estruturado para responder perguntas
concretas, como:

- esta estratégia realmente tem edge estatístico?
- em quais ativos ela funciona melhor?
- em quais regimes de mercado ela se degrada?
- o payout da corretora é suficiente para sustentar a operação?
- a performance observada em backtest se mantém em tempo real?
- os resultados são repetíveis ou dependem de acaso e recortes?

Em vez de assumir que uma estratégia é boa porque "parece boa", o projeto
busca criar um ambiente onde cada decisão possa ser rastreada, cada hipótese
possa ser refutada e cada resultado possa ser confrontado com os dados.
Esse é o propósito central do TradingBoot.

---

## 📋 Índice

<details>
<summary><strong>Clique para expandir o índice completo</strong></summary>

- [1. O que é este projeto](#1-o-que-é-este-projeto)
- [2. Para quem este projeto foi feito](#2-para-quem-este-projeto-foi-feito)
- [3. Visão geral da arquitetura](#3-visão-geral-da-arquitetura)
- [4. Conceitos fundamentais](#4-conceitos-fundamentais)
- [5. Tecnologias usadas](#5-tecnologias-usadas)
- [6. Pré-requisitos](#6-pré-requisitos)
- [7. Como obter acesso à Deriv](#7-como-obter-acesso-à-deriv)
- [8. Como configurar o projeto](#8-como-configurar-o-projeto)
- [9. Como executar o projeto](#9-como-executar-o-projeto)
- [10. Estrutura de arquivos](#10-estrutura-de-arquivos)
- [11. Como o sistema funciona](#11-como-o-sistema-funciona)
- [12. Modos de decisão](#12-modos-de-decisão)
- [13. Estratégias implementadas](#13-estratégias-implementadas)
- [14. Regimes de mercado](#14-regimes-de-mercado)
- [15. Gestão de risco](#15-gestão-de-risco)
- [16. Relatórios gerados](#16-relatórios-gerados)
- [17. Ferramentas auxiliares](#17-ferramentas-auxiliares)
- [18. Backtest](#18-backtest)
- [19. Guia de contribuição](#19-guia-de-contribuição)
- [20. FAQ](#20-faq)
- [21. Aviso importante](#21-aviso-importante)


</details>

---

## 1. O que é este projeto

TradingBoot é um sistema quantitativo-operacional que:

| Funcionalidade | Descrição |
|---|---|
| Conexão em tempo real | conecta à Deriv via WebSocket autenticado por OTP |
| Agregação de dados | transforma ticks em candles OHLC de 1 minuto |
| Classificação de regime | detecta TRENDING, RANGING ou CHOPPY automaticamente |
| Avaliação de estratégias | roda estratégias técnicas a cada candle fechado |
| Execução automatizada | executa contratos Rise/Fall (CALL/PUT) com stake fixo |
| Gestão de risco | filtra por ATR, volatilidade e ROI mínimo |
| Relatórios | gera CSV, JSON e daily\_summary com breakdown por regime |
| Backtest | simula operações com histórico salvo em disco |

---

## 2. Para quem este projeto foi feito

<details>
<summary><strong>Desenvolvedores sem experiência em trading</strong></summary>

Você vai encontrar explicações conceituais ao longo do README para
entender o que são ticks, candles, indicadores, risco, payout e regime.
Cada conceito é apresentado com exemplos numéricos simples.

</details>

<details>
<summary><strong>Desenvolvedores com experiência em backend</strong></summary>

Você vai conseguir enxergar a arquitetura, os fluxos de execução,
os pontos de extensão e os componentes principais do sistema.
O projeto segue padrões como Strategy, Factory, DIP e SRP.

</details>

<details>
<summary><strong>Interessados em pesquisa quantitativa</strong></summary>

O projeto separa claramente coleta de dados, simulação, decisão,
risco, execução e relatório. O backtest é alinhado ao runtime
para reduzir a distância entre simulação e operação real.

</details>

<details>
<summary><strong>Quem quer estudar bots de trading sem "magia"</strong></summary>

Tudo é organizado em classes específicas, sem depender de lógica
escondida em um único arquivo monolítico. Cada responsabilidade
é isolada e documentada.

</details>

---

## 3. Visão geral da arquitetura

```text
┌─────────────────────────────────────────────────────────────────┐
│                        DERIV (API / MERCADO)                    │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│ 1. AUTENTICAÇÃO OTP                                             │
│    DerivOtpService                                              │
│    ► POST /otp com app-id, access-token e account-id            │
│    ► recebe URL autenticada do WebSocket                        │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. CONEXÃO WEBSOCKET                                            │
│    DerivWsClient                                                │
│    ► conecta na URL retornada                                   │
│    ► mantém ping keep-alive                                     │
│    ► reconecta com OTP fresco em caso de queda                  │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. RECEPÇÃO E TRANSFORMAÇÃO                                     │
│    DerivMarketDataService → TickCandleAggregator                │
│    ► recebe ticks                                               │
│    ► agrupa em candles OHLC de 1 minuto                         │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. ENGINE DE DECISÃO                                            │
│    StrategyEngine                                               │
│    ► mantém histórico local (BarHistory)                        │
│    ► aciona MarketRegimeMonitor                                 │
│    ► aplica VolatilityFilter                                    │
│    ► avalia estratégias via DecisionEvaluator                   │
│    ► emite sinal final via SignalEmitter                        │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. EXECUÇÃO DO TRADE                                            │
│    DerivTradeService                                            │
│    ► valida pré-condições (TradeValidator)                      │
│    ► avalia risco por ATR (AtrRiskManager)                      │
│    ► verifica ROI mínimo (TradeExecutor)                        │
│    ► executa proposal → buy                                     │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│ 6. MONITORAMENTO E RELATÓRIO                                    │
│    TradeMonitor → TradeReportService                            │
│    ► acompanha contrato via stream + watchdog                   │
│    ► grava CSV, JSON e daily_summary                            │
│    ► registra regimePerformance                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Conceitos fundamentais

> Se você já domina esses conceitos, pule para a
> [seção 7 — Como obter acesso à Deriv](#7-como-obter-acesso-à-deriv).

<details>
<summary><strong>4.1 O que é um ativo</strong></summary>

Um ativo é o "objeto" que está sendo negociado.

Exemplos na Deriv:

| Símbolo | Ativo |
|---|---|
| `frxEURUSD` | Euro vs Dólar |
| `frxGBPUSD` | Libra vs Dólar |
| `frxXAUUSD` | Ouro vs Dólar |

No projeto, cada ativo é tratado como um **pipeline isolado**
com suas próprias estratégias, stake, regime e histórico.

</details>

<details>
<summary><strong>4.2 O que é um tick</strong></summary>

Tick é a menor atualização de preço recebida do mercado.

```text
10:30:01 → 1.08420
10:30:02 → 1.08422
10:30:02 → 1.08418
10:30:03 → 1.08425
```

Cada linha é um tick. Ticks chegam de forma irregular — às vezes
vários por segundo, às vezes um a cada poucos segundos.

O tick sozinho é muito "rápido" e barulhento para a maioria das
estratégias. Por isso o sistema agrupa vários ticks em um candle.

</details>

<details>
<summary><strong>4.3 O que é um candle (OHLC)</strong></summary>

Um candle resume o que aconteceu no preço durante um período.
No projeto, o período é de **1 minuto**.

Cada candle possui:

| Campo | Significado |
|---|---|
| `open` | primeiro preço do minuto |
| `high` | maior preço do minuto |
| `low` | menor preço do minuto |
| `close` | último preço do minuto |

Exemplo:

```text
Minuto 10:30

Ticks recebidos:
  10:30:01 → 1.08420
  10:30:07 → 1.08425
  10:30:18 → 1.08417
  10:30:55 → 1.08423

Candle resultante:
  Open  = 1.08420
  High  = 1.08425
  Low   = 1.08417
  Close = 1.08423
```

Visualmente:

```text
     ┃ ← High
     ┃
   ┏━┃━┓
   ┃ ┃ ┃ ← corpo (Open a Close)
   ┗━┃━┛
     ┃
     ┃ ← Low
```

> Recurso visual externo:
> [O que é um candlestick — Investopedia](https://www.investopedia.com/terms/c/candlestick.asp)

</details>

<details>
<summary><strong>4.4 O que é um contrato Rise/Fall</strong></summary>

Na Deriv, o projeto opera contratos binários do tipo Rise/Fall:

| Tipo | Significado | No projeto |
|---|---|---|
| `CALL` (Rise) | aposta que o preço vai subir | `BUY` |
| `PUT` (Fall) | aposta que o preço vai cair | `SELL` |

Exemplo:

```text
Contrato CALL em EURUSD:
  Preço de entrada: 1.08420
  Duração: 15 minutos

  Após 15 minutos:
    preço = 1.08450 → subiu → GANHOU
    preço = 1.08400 → caiu  → PERDEU
```

</details>

<details>
<summary><strong>4.5 O que é stake, payout e ROI</strong></summary>

**Stake** é o valor apostado:

```text
stake = $10.00
```

**Payout** é o valor total retornado se ganhar:

```text
payout = $17.00
```

**ROI** é o retorno percentual sobre o stake:

```text
profit = payout - stake = $17.00 - $10.00 = $7.00
ROI    = profit / stake × 100 = 70%
```

O sistema permite configurar um **ROI mínimo** por ativo:

```text
minRoiPercent = 70.0

Payout $17.00 → ROI 70% → ✅ executa
Payout $16.50 → ROI 65% → ❌ descarta
```

</details>

<details>
<summary><strong>4.6 O que é break-even</strong></summary>

Break-even é a taxa de acerto mínima para não perder dinheiro:

```text
Break-even = 1 / (1 + ROI)

Com ROI de 70%:
  Break-even = 1 / 1.70 = 58.82%

Ou seja: precisa acertar mais de 58.82% das operações.
```

</details>

<details>
<summary><strong>4.7 O que é edge estatístico</strong></summary>

Edge é a vantagem do sistema sobre o aleatório:

```text
Break-even = 58.82%
Win rate   = 63%
Edge       = 63% - 58.82% = 4.18 pontos percentuais → positivo ✅

Break-even = 58.82%
Win rate   = 55%
Edge       = 55% - 58.82% = -3.82 → negativo ❌
```

> O objetivo principal do TradingBoot é descobrir se existe edge real
> antes de arriscar capital.

</details>

<details>
<summary><strong>4.8 O que é EMA (Média Móvel Exponencial)</strong></summary>

EMA é uma média que dá **mais peso aos preços recentes**.

```text
Preço de hoje     → peso ALTO
Preço de ontem    → peso MÉDIO
Preço de 20 dias  → peso BAIXO
```

O sistema usa duas EMAs:

| EMA | Período | Comportamento |
|---|---|---|
| EMA 8 | 8 minutos | rápida, segue o preço de perto |
| EMA 21 | 21 minutos | lenta, serve como referência |

```text
EMAs separadas → mercado com direção → TRENDING
EMAs coladas   → mercado sem direção → RANGING
```

</details>

<details>
<summary><strong>4.9 O que é ATR (Average True Range)</strong></summary>

ATR mede o **tamanho médio dos movimentos** do preço:

```text
Candle 1: high - low = 0.0010
Candle 2: high - low = 0.0014
Candle 3: high - low = 0.0012

ATR = (0.0010 + 0.0014 + 0.0012) / 3 = 0.0012
```

| ATR | Significado |
|---|---|
| alto | mercado agitado, candles grandes |
| baixo | mercado calmo, candles pequenos |

</details>

<details>
<summary><strong>4.10 O que é Efficiency Ratio (ER)</strong></summary>

ER mede se o preço foi "em linha reta" ou ficou "indo e voltando":

```text
ER = |movimento líquido| / movimento total

Caminho reto:     ER = 100/100 = 1.0 → tendência
Caminho tortuoso: ER = 30/200  = 0.15 → lateral
```

| ER | Significado |
|---|---|
| próximo de 1.0 | tendência forte |
| próximo de 0.0 | mercado lateral |

</details>

<details>
<summary><strong>4.11 O que é RSI</strong></summary>

RSI mede a "força" do movimento recente (escala 0 a 100):

```text
RSI = 100 - (100 / (1 + RS))
RS  = média dos ganhos / média das perdas
```

| RSI | Significado |
|---|---|
| > 70 | sobrecomprado → pode cair |
| < 30 | sobrevendido → pode subir |
| 30-70 | zona neutra |

</details>

<details>
<summary><strong>4.12 O que são Bandas de Bollinger</strong></summary>

Canal ao redor do preço baseado na volatilidade:

```text
Banda Superior = Média + (multiplicador × Desvio Padrão)
Média Central  = Média simples dos últimos N candles
Banda Inferior = Média - (multiplicador × Desvio Padrão)
```

```text
─── Banda Superior ─────────────
         ↑ preço aqui → pode cair
    ≈≈≈ Preço ≈≈≈
         ↓ preço aqui → pode subir
─── Banda Inferior ─────────────
```

</details>

<details>
<summary><strong>4.13 O que é Z-Score</strong></summary>

Mede quantos desvios padrão o preço está distante da média:

```text
z-score = (preço atual - média) / desvio padrão
```

| Z-Score | Significado |
|---|---|
| > +2 | preço muito acima da média → pode cair |
| < -2 | preço muito abaixo da média → pode subir |
| ≈ 0 | preço na média → neutro |

</details>

<details>
<summary><strong>4.14 O que é regime de mercado</strong></summary>

O regime resume o comportamento atual do mercado:

| Regime | Significado |
|---|---|
| `TRENDING` | mercado com direção clara |
| `RANGING` | mercado lateral |
| `CHOPPY` | mercado confuso ou em transição |

> Uma estratégia pode funcionar bem em RANGING e falhar em TRENDING,
> ou vice-versa. O TradingBoot tenta medir isso.

</details>

---

## 5. Tecnologias usadas

| Tecnologia | Versão | Finalidade |
|---|---|---|
| Java | 25 | linguagem principal com virtual threads e records |
| Spring Boot | 4.0.3 | framework de aplicação e injeção de dependências |
| Jackson | latest | serialização e deserialização JSON |
| Java-WebSocket | 1.5.7 | conexão em tempo real com a Deriv |
| Maven | latest | build, dependências e empacotamento |
| PDFBox | 2.0.30 | geração de relatórios em PDF |
| SnakeYAML | latest | leitura de configuração em ferramentas standalone |
| HttpClient | nativo | chamadas REST para autenticação OTP |

---

## 6. Pré-requisitos

- Java 25 ou superior
- Maven 3.8 ou superior
- Conta na [Deriv](https://www.deriv.com)
- Aplicação registrada na Deriv (para obter o `app-id`)
- Token de acesso com escopos: `Trade` e `Account management`

---

## 7. Como obter acesso à Deriv

### 7.1 Registrar o app

Antes de conectar o TradingBoot à Deriv, é necessário registrar uma aplicação
para obter o `app-id`.

#### Passo a passo

1. Acesse o portal de desenvolvedor da Deriv:

   ```text
   https://developers.deriv.com
   ```

2. Caso ainda não tenha uma conta na Deriv, faça seu cadastro antes de prosseguir.

3. Se já possuir uma conta, faça login normalmente.

4. Após o login, acesse a aba:

   ```text
   Dashboard
   ```

5. Clique em:

   ```text
   Create new app
   ```

6. Escolha o tipo de aplicação:

    - **Native apps**
    - **Web-based App**

7. Como a arquitetura atual do projeto está apenas coletando o token de acesso no **application.yml** e inserindo nas requisições, selecione a opção:

   ```text
   Native apps
   ```

> Se quiser entender mais sobre os modelos de autenticação,
> consulte a [documentação oficial](https://developers.deriv.com/docs/intro/authentication/).

8. Defina um nome para a aplicação.

9. Defina o **Markup** do seu app.

> Consulte o [guia sobre Markup](https://developers.deriv.com/docs/intro/markup/)
> para entender esse conceito.

10. Confirme a criação.

11. Após a criação, copie o valor do `app-id` gerado.

#### O que fazer com o `app-id`

Adicione no arquivo `src/main/resources/application.yml`:

```yaml
deriv:
  app-id: "SEU_APP_ID_AQUI"
```

> **Importante:** mantenha o valor entre aspas e use exatamente
> o valor retornado pela Deriv.

#### Observação técnica

O `app-id` é enviado no header `Deriv-App-ID` durante a autenticação REST via OTP.

---

### 7.2 Obter o Access Token

O projeto precisa de um `access-token` (Personal Access Token — PAT)
para autenticar as chamadas REST que trocam o token por um OTP de sessão.

#### Passo a passo

1. Acesse o [portal de desenvolvedor da Deriv](https://developers.deriv.com).

2. Caso ainda não tenha conta, faça seu cadastro. Se já tiver, faça login.

3. Acesse a aba `Dashboard` → seção `Api Tokens`.

4. Clique em `Create new token`.

5. Defina um nome e uma data de expiração.

6. Selecione os escopos:

    - `Trade`
    - `Account management`

7. Confirme a criação e copie o valor gerado.

#### O que fazer com o `access-token`

Adicione no arquivo `src/main/resources/application.yml`:

```yaml
deriv:
  access-token: "SEU_ACCESS_TOKEN_AQUI"
```

> **Importante:** trate o token como credencial sensível.
> Nunca publique em repositórios públicos.


#### Observação técnica

O `access-token` é um PAT enviado no header
`Authorization: Bearer {accessToken}` para obter o OTP de sessão.
Após a troca, o OTP é embutido na URL do WebSocket como query string.
O token original não é enviado via WebSocket.
---

### 7.3 Obter o Account ID

O projeto precisa do `account-id` para definir em qual conta
a autenticação será feita.

#### Passo a passo

1. Acesse o [portal de desenvolvedor da Deriv](https://developers.deriv.com).

2. Caso ainda não tenha conta, faça seu cadastro. Se já tiver, faça login.

3. Acesse a aba `Playground`.

4. Localize o seletor de conta:

   | Opção | Uso |
      |---|---|
   | `Demo account` | testes e validações |
   | `Real account` | operação com capital real |

5. Copie o identificador alfanumérico da conta desejada.

#### O que fazer com o `account-id`

Adicione no arquivo `src/main/resources/application.yml`:

```yaml
deriv:
  account-id: "VRTC1234567"
```

> **Importante:** use conta demo nos testes iniciais.
> Só utilize conta real após validação em backtest e simulação.

#### Observação técnica

O `account-id` compõe a rota REST: `/trading/v1/options/accounts/{accountId}/otp`

---

## 8. Como configurar o projeto

### 8.1 application.yml

Crie o arquivo:

```text
src/main/resources/application.yml
```

> **Este arquivo não está no repositório** por conter credenciais sensíveis.

```yaml
spring:
  application:
    name: TradingBoot

server:
  port: 9005

deriv:
  app-id: "SEU_APP_ID_AQUI"
  access-token: "SEU_ACCESS_TOKEN_AQUI"
  account-id: "SEU_ACCOUNT_ID_AQUI"
  history-count: 1500

logging:
  level:
    com.github.dayviddouglas.TradingBoot: INFO
    org.java_websocket: WARN
```

<details>
<summary><strong>Campos explicados</strong></summary>

| Campo | Tipo | Descrição |
|---|---|---|
| `app-id` | String | ID da aplicação registrada na Deriv |
| `access-token` | String | Token OAuth para autenticação REST |
| `account-id` | String | Login ID da conta (demo ou real) |
| `history-count` | int | Quantidade de candles carregados no startup |

</details>

---

### 8.2 strategies.json

Crie o arquivo:

```text
src/main/resources/strategies.json
```

> **Este arquivo não está no repositório.**
> A configuração detalhada de cada estratégia com seus parâmetros
> está documentada na [seção 13 — Estratégias implementadas](#13-estratégias-implementadas).

Exemplo de profile:

```json
{
  "profiles": [
    {
      "symbol": "frxEURUSD",
      "granularitySeconds": 60,
      "engine": {
        "maxBars": 1500,
        "decisionMode": "VOTING",
        "rangeLookback": 14,
        "rangeMultiplier": 1.10
      },
      "trade": {
        "enabled": false,
        "amount": 10,
        "currency": "USD",
        "duration": 15,
        "durationUnit": "m",
        "minRoiPercent": 70.0
      },
      "strategies": {
        "emaRsi": { "enabled": true },
        "pinBar": { "enabled": true },
        "bollingerMeanReversion": { "enabled": false },
        "zScoreMeanReversion": { "enabled": false },
        "supportResistance": { "enabled": false },
        "breakout": { "enabled": false },
        "keltnerChannel": { "enabled": false },
        "donchianSystem1": { "enabled": false },
        "donchianSystem2": { "enabled": false }
      }
    }
  ]
}
```

> **Importante:** mantenha `trade.enabled: false` durante os testes iniciais.

<details>
<summary><strong>Atributos do perfil</strong></summary>

| Campo | Descrição | Exemplo |
|---|---|---|
| `symbol` | código do ativo na Deriv | `"frxEURUSD"` |
| `granularitySeconds` | tamanho do candle em segundos | `60` |

</details>

<details>
<summary><strong>Atributos do bloco engine</strong></summary>

| Campo | Descrição | Valores |
|---|---|---|
| `maxBars` | histórico máximo de candles | `1500` |
| `decisionMode` | modo de decisão | `"VOTING"`, `"CONFLUENCE"`, `"SINGLE_STRATEGY"` |
| `rangeLookback` | candles para filtro de volatilidade | `14` |
| `rangeMultiplier` | multiplicador do filtro | `1.10` |

Veja a [seção 15.4](#15.4-filtro-de-volatilidade) para explicação detalhada
de `rangeLookback` e `rangeMultiplier`.

</details>

<details>
<summary><strong>Atributos do bloco trade</strong></summary>

| Campo | Descrição | Exemplo |
|---|---|---|
| `enabled` | habilita execução real | `false` |
| `amount` | stake por operação | `10` |
| `currency` | moeda do stake | `"USD"` |
| `duration` | duração do contrato | `15` |
| `durationUnit` | unidade de tempo | `"m"` |
| `minRoiPercent` | ROI mínimo para executar | `70.0` |

</details>

<details>
<summary><strong>Bloco strategies</strong></summary>

Cada estratégia aparece como chave dentro do bloco `strategies`.

O atributo mínimo obrigatório é `enabled`:

```json
"strategies": {
  "emaRsi": { "enabled": true },
  "pinBar": { "enabled": false }
}
```

A explicação detalhada dos parâmetros de cada estratégia está na
[seção 13 — Estratégias implementadas](#13-estratégias-implementadas).

</details>

---

## 9. Como executar o projeto

> **Pré-requisito:** as etapas das seções
> [7 — Como obter acesso à Deriv](#7-como-obter-acesso-à-deriv) e
> [8 — Como configurar o projeto](#8-como-configurar-o-projeto)
> devem estar concluídas antes de prosseguir.

---

### 9.1 Clonar o repositório

```bash
git clone https://github.com/dayviddouglas/TradingBoot.git
cd TradingBoot
```

---

### 9.2 Compilar o projeto

```bash
mvn clean install -DskipTests
```

Aguarde a mensagem `BUILD SUCCESS` antes de prosseguir.

---

### 9.3 Baixar o histórico de candles

Antes de iniciar o TradingBoot, é necessário ter histórico salvo em disco.
O histórico é usado no startup para inicializar o pipeline de cada ativo
e confirmar o regime de mercado via warm-up.

Abra a classe no IDE e ajuste os ativos desejados:

```text
src/main/java/.../tools/history/DerivHistoryDownloadTool.java
```

```java
private static final List<String> SYMBOLS = List.of("frxEURUSD", "frxXAUUSD");
private static final int GRANULARITY_SECONDS = 60;
private static final int DAYS_BACK = 90;
```

Execute a classe diretamente pelo IDE (botão de play na classe `main`).

O histórico será salvo em:

```text
data/history/frxEURUSD_60.json
data/history/frxXAUUSD_60.json
```

> Esta etapa não requer autenticação. O download usa o endpoint público da Deriv.

---

### 9.4 Verificar a configuração antes de iniciar

Antes de iniciar o TradingBoot, confirme:

```text
✅ application.yml criado com app-id, access-token e account-id
✅ strategies.json criado com pelo menos 1 profile habilitado
✅ Histórico baixado para os ativos configurados no strategies.json
✅ trade.enabled: false (recomendado para os primeiros testes)
```

---

### 9.5 Iniciar o TradingBoot

#### Via Maven

```bash
mvn spring-boot:run
```

#### Via IDE

Execute a classe principal pelo IDE:

```text
src/main/java/com/github/dayviddouglas/TradingBot/TradingBootApplication.java
```

#### Via JAR

```bash
mvn clean package -DskipTests
java -jar target/TradingBoot-*.jar
```

---

### 9.6 O que acontece no startup

```text
1. Spring Boot inicializa os beans
2. strategies.json é carregado e validado
3. Um pipeline é criado para cada ativo configurado
4. DerivOtpService troca o PAT por um OTP via REST
5. DerivWsClient conecta ao WebSocket com a URL autenticada
6. BotInitializer valida a disponibilidade de trade por ativo
7. Histórico de candles é carregado do disco para cada ativo
8. Regime de mercado é confirmado via warm-up do histórico
9. Subscrição de ticks em tempo real é iniciada por ativo
10. TradingBoot entra em operação
```

Os logs esperados no startup bem-sucedido são:

```text
INFO  DerivOtpService      - OTP WS URL OBTAINED | url=wss://...otp=***
INFO  DerivWsClient        - Deriv WS opened | httpStatus=101
INFO  StrategyEngine       - HISTORY SEEDED | symbol=frxEURUSD | bars=1500
INFO  StrategyEngine       - REGIME WARM-UP START | symbol=frxEURUSD | bars=1500
INFO  StrategyEngine       - REGIME WARM-UP DONE | symbol=frxEURUSD | bars=1500
INFO  PipelineRegistry     - PIPELINE REGISTERED | symbol=frxEURUSD | ...
INFO  TickHeartbeat        - TICK HEARTBEAT | monitoring started | timeout=3min
```

---

### 9.7 Monitorar a operação

Os logs operacionais são exibidos no console em tempo real.
Os principais eventos que aparecem durante a operação são:

```text
# Candle fechado e avaliado
INFO  StrategyEngine     - FINAL SIGNAL BUY | symbol=frxEURUSD | ...

# Sinal passando pelas camadas de risco
INFO  DerivTradeService  - ATR RISK OK | symbol=frxEURUSD | ...
INFO  TradeExecutor      - TRADE PROPOSAL OK | symbol=frxEURUSD | ...
INFO  TradeExecutor      - TRADE BUY OK | symbol=frxEURUSD | contract_id=...

# Fechamento do contrato
INFO  TradeMonitor       - TRADE CLOSED | source=STREAM | result=WIN | profit=7.00

# Mudança de regime
INFO  RegimeStateTracker - REGIME CONFIRMED | symbol=frxEURUSD | CHOPPY → RANGING
```

---

### 9.8 Parar o TradingBoot

Pressione `Ctrl+C` no terminal ou use o botão de parar do IDE.

O TradingBoot não possui persistência de estado entre execuções. Ao reiniciar,
o processo de startup completo é reexecutado, incluindo warm-up de regime.

---

### 9.9 Verificar os relatórios gerados

Após operações, os relatórios são salvos em subdiretórios por data:

```text
data/reports/
└── 2026-06-02/
    ├── trades_2026-06-02.csv
    ├── trades_2026-06-02.json
    ├── daily_summary_2026-06-02.json
    └── regime_report_frxEURUSD_2026-06-02.json
```

Veja a [seção 16 — Relatórios gerados](#16-relatórios-gerados) para
entender o conteúdo de cada arquivo.

---

### 9.10 Solução de problemas comuns no startup

<details>
<summary><strong>Erro: "OTP request failed | status=401"</strong></summary>

O `access-token` está incorreto ou expirou.

Verifique:
- o token foi copiado corretamente no `application.yml`
- o token não expirou no portal da Deriv
- os escopos `Trade` e `Account management` estão selecionados

</details>

<details>
<summary><strong>Erro: "OTP request failed | status=429"</strong></summary>

Rate limit do Cloudflare. O sistema tenta novamente automaticamente
com backoff crescente: `5s → 15s → 30s → 60s → 120s`.

Aguarde e o TradingBoot se reconectará sozinho.

</details>

<details>
<summary><strong>Erro: "strategies.json inválido: 'profiles' vazio ou ausente"</strong></summary>

O arquivo `strategies.json` não existe ou está mal formatado.

Verifique:
- o arquivo existe em `src/main/resources/strategies.json`
- o JSON é válido (use um validador online se necessário)
- o campo `profiles` é um array com pelo menos 1 elemento

</details>

<details>
<summary><strong>Erro: "HISTORY NOT FOUND | symbol=frxEURUSD"</strong></summary>

O histórico do ativo não foi baixado.

Execute `DerivHistoryDownloadTool` com o símbolo correto antes de iniciar o TradingBoot.

</details>

<details>
<summary><strong>TradingBoot inicia mas não gera sinais</strong></summary>

Possíveis causas:

- `trade.enabled: false` — o TradingBoot avalia mas não opera (comportamento esperado em modo pesquisa)
- filtro de volatilidade bloqueando (`rangeMultiplier` muito alto)
- estratégias não estão gerando sinal no histórico atual (confira via backtest)
- menos de 200 candles disponíveis para o regime ser classificado

</details>

## 10. Estrutura de arquivos

<details>
<summary><strong>Clique para expandir a estrutura completa</strong></summary>

```text
TradingBot/
│
├── src/main/java/com/github/dayviddouglas/TradingBoot/
│   ├── backtest/
│   │   ├── runner/        orquestração e configuração do backtest
│   │   ├── result/        métricas e relatórios de resultado
│   │   └── output/        impressão e exportação
│   ├── bot/               orquestração do runtime
│   ├── config/
│   │   ├── core/          beans e propriedades do Spring
│   │   └── strategy/      loading e validação do strategies.json
│   ├── deriv/             integração com a Deriv
│   ├── deriv/trade/
│   │   ├── context/       contexto e tratamento de erros
│   │   ├── execution/     proposal, ROI e compra
│   │   ├── monitor/       acompanhamento de contrato
│   │   └── validation/    validação de pré-condições
│   ├── deriv/ws/          infraestrutura WebSocket
│   ├── engine/
│   │   ├── core/          pipeline principal (StrategyEngine, BarHistory)
│   │   ├── decision/      modos de decisão (VOTING, SINGLE_STRATEGY)
│   │   ├── decision/confluence/  modo CONFLUENCE e scores ponderados
│   │   ├── filter/        filtros operacionais (VolatilityFilter)
│   │   └── regime/        classificação e monitoramento de regime
│   ├── exceptions/        exceções tipadas
│   ├── market/            agregação de ticks em candles
│   ├── model/             modelos principais (Bar, Signal)
│   ├── report/            relatórios operacionais
│   ├── risk/              gestão de risco por ATR
│   ├── strategy/          estratégias técnicas
│   └── tools/             ferramentas standalone
│
├── src/main/resources/
│   ├── application.yml    NÃO versionado
│   └── strategies.json    NÃO versionado
│
├── data/
│   ├── history/           histórico de candles por ativo
│   └── reports/           relatórios do runtime
│
└── pom.xml
```

</details>

---

## 11. Como o sistema funciona

### 11.1 Startup

```text
Spring Boot inicializa
    ↓
Profiles carregados do strategies.json
    ↓
Pipeline criado por ativo
    ↓
Conexão WebSocket iniciada via OTP
```

### 11.2 Conexão e autenticação

```text
DerivOtpService.fetchWsUri()
    ↓
POST /otp → URL temporária com OTP
    ↓
DerivWsClient.connect()
    ↓
BotInitializer.initialize()
    ├── valida contratos disponíveis
    ├── solicita histórico de candles
    └── subscreve ticks em tempo real
```

### 11.3 Ciclo por candle

```text
1. TickCandleAggregator fecha o candle
2. StrategyEngine.onBar() recebe o candle
3. BarHistory armazena
4. MarketRegimeMonitor é notificado
5. VolatilityFilter verifica se o mercado se moveu
6. Estratégias são avaliadas
7. Signal é emitido conforme o modo de decisão
8. DerivTradeService.onFinalSignal() recebe o sinal
```

> O modo de decisão (SINGLE\_STRATEGY, VOTING ou CONFLUENCE)
> determina como os sinais são combinados.
> Veja a [seção 12 — Modos de decisão](#12-modos-de-decisão).

### 11.4 Ciclo de trade

```text
1. TradeValidator verifica se pode operar
2. AtrRiskManager verifica volatilidade
3. TradeContextFactory monta o contexto
4. TradeExecutor solicita proposal
5. ROI do proposal é verificado
6. Se ROI >= minRoiPercent → compra
7. TradeMonitor acompanha o contrato
8. Quando fecha → TradeReportService grava
```

### 11.5 Reconexão automática

```text
Conexão cai
    ↓
Backoff exponencial (1s, 2s, 4s... até 20s)
    ↓
DerivOtpService busca novo OTP
    ↓
Reconecta com URL nova
    ↓
BotInitializer reexecuta o bootstrap
```

---

## 12. Modos de decisão

O modo de decisão define **como os sinais das estratégias são combinados**
para gerar o sinal final.

```json
"engine": {
  "decisionMode": "VOTING"
}
```

---

### 12.1 Por que o modo de decisão importa

```text
BollingerMeanReversion → BUY
ZScoreMeanReversion    → BUY
EmaRsi                 → SELL
```

| Modo | Resultado |
|---|---|
| SINGLE\_STRATEGY | usa apenas 1 estratégia |
| VOTING | discordância → NONE |
| CONFLUENCE | calcula scores → pode emitir BUY |

---

<details>
<summary><strong>12.2 SINGLE_STRATEGY</strong></summary>

#### Conceito

Usa **apenas uma estratégia**. O sinal dela vira o sinal final.

#### Regras

- exatamente 1 estratégia habilitada
- mais de 1 → erro na inicialização

#### Exemplo

```text
PinBar → BUY → Sinal final: BUY
PinBar → NONE → Sistema não opera
```

#### Quando usar

- teste isolado de uma estratégia
- pesquisa e diagnóstico

</details>

<details>
<summary><strong>12.3 VOTING</strong></summary>

#### Conceito

Exige **unanimidade** entre todas as estratégias habilitadas.

#### Regras

- pelo menos 2 estratégias habilitadas
- todas precisam concordar na mesma direção
- se qualquer uma retornar NONE → sinal final é NONE
- se houver BUY e SELL → sinal final é NONE

#### Exemplos

```text
Unanimidade:
  Bollinger → BUY
  ZScore    → BUY
  Sinal final: BUY ✅

Divergência:
  Bollinger → BUY
  ZScore    → SELL
  Sinal final: NONE ❌

Uma neutra:
  Bollinger → BUY
  ZScore    → NONE
  Sinal final: NONE ❌
```

#### Vantagem

Menos trades, mais convicção.

#### Desvantagem

Pode perder oportunidades.

</details>

<details>
<summary><strong>12.4 CONFLUENCE</strong></summary>

#### Conceito

Usa **scores ponderados** por regime de mercado.

#### Regras

- pelo menos 2 estratégias habilitadas
- cada estratégia tem peso diferente por regime
- 3 regras numéricas validam o sinal:

| Regra | Descrição | Exemplo |
|---|---|---|
| `minDecisionScore` | score mínimo na direção vencedora | `2.4` |
| `maxOppositeScore` | score máximo na direção oposta | `0.9` |
| `minStrategiesInDirection` | mínimo de estratégias concordando | `2` |

#### Exemplo completo

Regime atual: `RANGING`

```text
Pesos em RANGING:
  Bollinger = 1.5
  ZScore    = 1.2
  EmaRsi    = 0.6

Sinais:
  Bollinger → BUY  (peso 1.5)
  ZScore    → BUY  (peso 1.2)
  EmaRsi    → SELL (peso 0.6)

Scores:
  BUY  = 1.5 + 1.2 = 2.7
  SELL = 0.6

Verificação:
  2.7 >= 2.4      → ✅
  0.6 <= 0.9      → ✅
  2 estratégias   → ✅

Sinal final: BUY ✅
```

#### Vantagem

Mais flexível. Considera regime de mercado.

#### Desvantagem

Requer calibragem cuidadosa dos pesos.

</details>

---

### 12.5 Comparativo

| Critério | SINGLE\_STRATEGY | VOTING | CONFLUENCE |
|---|---|---|---|
| Estratégias mínimas | 1 | 2 | 2 |
| Critério | 1 decide | unanimidade | score ponderado |
| Considera regime | não | não | sim |
| Quantidade de sinais | alta | baixa | média |
| Complexidade | baixa | média | alta |
| Uso ideal | pesquisa | conservador | ajustado por contexto |

---

### 12.6 Fluxo visual

```text
SINGLE_STRATEGY:
  Candle → Estratégia A → Sinal final

VOTING:
  Candle → A=BUY, B=BUY, C=BUY → Unanimidade? → BUY

CONFLUENCE:
  Candle → A=BUY(1.5), B=BUY(1.2), C=SELL(0.6)
       → Score BUY=2.7, SELL=0.6
       → Regras OK? → BUY
```
## 13. Estratégias implementadas

> **Aviso importante:**
> Todos os parâmetros numéricos mostrados nesta seção são
> **exemplos puramente didáticos**. Eles **não representam** valores
> calibrados ou em uso no projeto. Quem quiser descobrir se uma
> estratégia tem edge deve **testar e calibrar por conta própria**
> via backtest.

---

<details>
<summary><strong>13.1 BollingerMeanReversionStrategy</strong></summary>

#### O que é reversão à média?

Imagine uma borracha elástica presa na mesa. Você puxa ela para
cima e solta. O que acontece? Ela volta para a posição original.

No mercado, o conceito é parecido:

> quando o preço se afasta muito da "posição normal" (média),
> existe uma probabilidade estatística de ele voltar.

A "posição normal" é a **média dos últimos N preços**.

---

#### O que é desvio padrão?

Desvio padrão é uma medida de **dispersão**. Ele diz:

> em média, quanto os preços se afastam da média?

Exemplo simples com 5 preços:

```text
Preços: 10, 12, 11, 13, 9
Média = (10 + 12 + 11 + 13 + 9) / 5 = 11

Diferenças em relação à média:
  10 - 11 = -1
  12 - 11 = +1
  11 - 11 =  0
  13 - 11 = +2
   9 - 11 = -2

Desvio padrão ≈ 1.41
```

Quanto maior o desvio padrão:
- os preços estão mais espalhados → mercado mais volátil

Quanto menor:
- os preços estão mais agrupados → mercado mais calmo

---

#### O que são as Bandas de Bollinger?

As Bandas de Bollinger usam a média e o desvio padrão para criar
um **canal** ao redor do preço:

```text
Banda Superior = Média + (multiplicador × Desvio Padrão)
Média Central  = Média simples dos últimos N candles
Banda Inferior = Média - (multiplicador × Desvio Padrão)
```

Exemplo numérico:

```text
Média dos últimos 20 candles = 1.0840
Desvio padrão = 0.0005
Multiplicador = 2.0

Banda Superior = 1.0840 + (2.0 × 0.0005) = 1.0850
Banda Inferior = 1.0840 - (2.0 × 0.0005) = 1.0830
```

Isso cria um canal entre 1.0830 e 1.0850.

Visualmente:

```text
1.0850 ─── Banda Superior ──────────────
                ↑ preço acima → pode cair
1.0840     ≈≈≈ Preço ≈≈≈ (média)
                ↓ preço abaixo → pode subir
1.0830 ─── Banda Inferior ──────────────
```

---

#### Lógica da estratégia

A estratégia observa se o preço saiu do canal:

```text
Preço acima da banda superior → raro → aposta que vai voltar → SELL
Preço abaixo da banda inferior → raro → aposta que vai voltar → BUY
```

Pode incluir filtro de **RSI** para dar mais segurança ao sinal:

```text
RSI sobrecomprado (ex: > 75) → confirma que o mercado subiu demais → SELL
RSI sobrevendido (ex: < 25)  → confirma que o mercado caiu demais → BUY
```

---

#### Regime ideal: `RANGING`

Em mercados laterais o preço fica "preso" numa faixa e tende
a voltar para a média quando toca os extremos.

#### Limitação conhecida

Em mercados TRENDING o preço pode **continuar subindo** mesmo
depois de ultrapassar a banda superior. Nesse caso, apostar na
queda gera perdas repetidas.

---

#### Configuração no strategies.json

```json
"bollingerMeanReversion": {
  "enabled": true,
  "period": 15,
  "stdDevMultiplier": 1.8,
  "entryThreshold": 0.92,
  "useRsiConfirmation": false,
  "rsiPeriod": 10,
  "rsiOverbought": 75.0,
  "rsiOversold": 25.0
}
```

#### Parâmetros explicados

##### period

Quantidade de candles usados para calcular a média e o desvio padrão.

```json
"period": 15
```

Exemplo: se `period = 15`, o sistema pega os preços de fechamento
dos últimos 15 candles, soma tudo, divide por 15 e tem a média.

```text
Valor maior → bandas mais suaves, menos sinais
Valor menor → bandas mais reativas, mais sinais, mais ruído
```

---

##### stdDevMultiplier

Multiplicador que define a largura das bandas.

```json
"stdDevMultiplier": 1.8
```

```text
Banda Superior = Média + (1.8 × Desvio Padrão)
Banda Inferior = Média - (1.8 × Desvio Padrão)
```

```text
Valor maior → bandas mais afastadas → preço precisa se mover mais → menos sinais
Valor menor → bandas mais próximas → preço atinge mais fácil → mais sinais
```

---

##### entryThreshold

Proporção da distância entre a média e a banda que o preço precisa
atingir para gerar sinal.

```json
"entryThreshold": 0.92
```

Pense assim:

```text
A distância entre a média (1.0840) e a banda superior (1.0850) é 0.0010.

entryThreshold = 0.92 significa:
  o preço precisa percorrer pelo menos 92% dessa distância.
  92% de 0.0010 = 0.0009
  preço mínimo para sinal = 1.0840 + 0.0009 = 1.0849

entryThreshold = 1.0 → precisa tocar exatamente a banda
entryThreshold = 0.85 → precisa chegar a 85% (mais permissivo)
```

---

##### useRsiConfirmation

Define se o RSI deve confirmar o sinal.

```json
"useRsiConfirmation": false
```

```text
true  → só emite sinal se RSI também confirmar
false → emite sinal apenas pelas bandas
```

---

##### rsiPeriod

Quantidade de candles usados para calcular o RSI.
Só é usado quando `useRsiConfirmation` é `true`.

```json
"rsiPeriod": 10
```

---

##### rsiOverbought

Valor do RSI acima do qual o mercado é considerado "sobrecomprado".

```json
"rsiOverbought": 75.0
```

Significa: se o RSI ultrapassar 75, o mercado subiu rápido demais
e existe chance de queda.

---

##### rsiOversold

Valor do RSI abaixo do qual o mercado é considerado "sobrevendido".

```json
"rsiOversold": 25.0
```

Significa: se o RSI ficar abaixo de 25, o mercado caiu rápido demais
e existe chance de alta.

</details>

---

<details>
<summary><strong>13.2 ZScoreMeanReversionStrategy</strong></summary>

#### O que é z-score?

Z-score responde uma pergunta simples:

> o preço atual está longe ou perto do normal?

A fórmula é:

```text
z-score = (preço atual - média) / desvio padrão
```

Pense no z-score como uma "régua de normalidade":

```text
z-score =  0   → preço está na média → normal
z-score = +1   → preço está 1 desvio acima → um pouco alto
z-score = +2   → preço está 2 desvios acima → alto
z-score = +3   → preço está 3 desvios acima → muito raro

z-score = -1   → preço está 1 desvio abaixo → um pouco baixo
z-score = -2   → preço está 2 desvios abaixo → baixo
z-score = -3   → preço está 3 desvios abaixo → muito raro
```

#### Por que isso importa?

Na estatística, existe uma regra chamada **regra empírica**:

```text
68% dos valores ficam entre -1 e +1
95% dos valores ficam entre -2 e +2
99.7% dos valores ficam entre -3 e +3
```

Ou seja: quando o z-score passa de ±2, o preço está numa posição
que acontece apenas **5% do tempo**. Isso é considerado raro.

---

#### Exemplo numérico completo

```text
Últimos 20 candles:
  média = 1.0840
  desvio padrão = 0.0005

Preço atual = 1.0851

z-score = (1.0851 - 1.0840) / 0.0005 = 2.2
```

O z-score de 2.2 significa: o preço está 2.2 desvios padrão
**acima** da média. Isso acontece em menos de 3% das vezes.

A estratégia interpreta isso como:

> o preço provavelmente vai voltar para a média → SELL

---

#### Diferença para o Bollinger

Ambas as estratégias medem afastamento da média, mas de formas
ligeiramente diferentes:

| Aspecto | Bollinger | ZScore |
|---|---|---|
| Cálculo do desvio | amostral (N-1) | populacional (N) |
| Sensibilidade | mais conservador | mais sensível |
| Melhor para | amostras grandes | amostras menores |

Na prática, a diferença é sutil. Ambas funcionam melhor em RANGING.

---

#### Regime ideal: `RANGING`

#### Limitação conhecida

Em tendências fortes, o z-score pode ficar "preso" em valores
extremos por muito tempo. O preço não volta para a média porque
o mercado está genuinamente mudando de patamar.

---

#### Configuração no strategies.json

```json
"zScoreMeanReversion": {
  "enabled": true,
  "period": 25,
  "entryZScore": 1.8
}
```

#### Parâmetros explicados

##### period

Quantidade de candles usados para calcular a média e o desvio.

```json
"period": 25
```

```text
Maior → cálculo mais estável, menos sensível a ruído
Menor → cálculo mais reativo, mais sensível a movimentos recentes
```

---

##### entryZScore

Valor mínimo absoluto do z-score para gerar sinal.

```json
"entryZScore": 1.8
```

Exemplo:

```text
z-score =  2.0 → |2.0| > 1.8 → preço muito acima → SELL ✅
z-score = -2.5 → |2.5| > 1.8 → preço muito abaixo → BUY ✅
z-score =  1.2 → |1.2| < 1.8 → preço próximo da média → NONE ❌
```

```text
Maior → precisa de afastamento maior → menos sinais, mais extremos
Menor → aceita afastamento menor → mais sinais, mais risco de falso positivo
```

</details>

---

<details>
<summary><strong>13.3 EmaRsiStrategy</strong></summary>

#### O que esta estratégia faz?

Combina dois indicadores diferentes para gerar um sinal:

- **EMA** → detecta **direção** (para onde o mercado está indo)
- **RSI** → confirma **momentum** (se o movimento tem força)

A ideia é: não basta saber que o mercado está subindo.
É preciso confirmar que ele está subindo **com força**.

---

#### Como a EMA detecta direção

O sistema usa duas EMAs:

```text
EMA rápida (período menor) → segue o preço de perto
EMA lenta  (período maior) → segue o preço de longe
```

A relação entre elas revela a direção:

```text
EMA rápida ACIMA da EMA lenta → mercado subindo
EMA rápida ABAIXO da EMA lenta → mercado caindo
EMA rápida COLADA na EMA lenta → mercado sem direção
```

Exemplo numérico:

```text
EMA rápida (5 períodos) = 1.0855
EMA lenta (15 períodos) = 1.0840

Distância = 1.0855 - 1.0840 = 0.0015

EMA rápida está ACIMA e com distância significativa
→ mercado está subindo com intensidade
```

---

#### O que é emaDistanceFactor?

O sistema precisa saber se as EMAs estão "separadas o suficiente"
para considerar que existe direção real.

Para isso, ele compara a distância entre as EMAs com o ATR:

```text
distância = |EMA rápida - EMA lenta|
limite = ATR × emaDistanceFactor

Se distância > limite → direção confirmada
Se distância < limite → EMAs muito próximas → sem direção
```

Exemplo:

```text
ATR = 0.0010
emaDistanceFactor = 0.3
limite = 0.0010 × 0.3 = 0.0003

distância entre EMAs = 0.0005
0.0005 > 0.0003 → direção confirmada ✅
```

---

#### Como o RSI confirma momentum

Depois de confirmar a direção pelas EMAs, o RSI valida
se o movimento tem força:

```text
Mercado subindo (EMA rápida acima):
  RSI > rsiOverbought → momentum de alta confirmado → BUY

Mercado caindo (EMA rápida abaixo):
  RSI < rsiOversold → momentum de queda confirmado → SELL
```

---

#### Regime ideal: `TRENDING`

#### Limitação conhecida

Em mercados RANGING as EMAs ficam coladas e o RSI oscila
entre 40-60 sem atingir os extremos. Isso gera sinais
confusos e falsos positivos.

---

#### Configuração no strategies.json

```json
"emaRsi": {
  "enabled": true,
  "fastEmaPeriod": 5,
  "slowEmaPeriod": 15,
  "rsiPeriod": 10,
  "rsiOverbought": 65.0,
  "rsiOversold": 35.0,
  "emaDistanceFactor": 0.3
}
```

#### Parâmetros explicados

##### fastEmaPeriod

Período da EMA rápida.

```json
"fastEmaPeriod": 5
```

```text
Menor → mais sensível ao preço recente → reage mais rápido
Maior → mais suave → reage mais devagar
```

---

##### slowEmaPeriod

Período da EMA lenta.

```json
"slowEmaPeriod": 15
```

A diferença entre `fastEmaPeriod` e `slowEmaPeriod` é crucial:

```text
Períodos muito próximos (ex: 10 e 12) → pouca separação → poucos sinais
Períodos mais distantes (ex: 5 e 15)  → mais separação → mais sinais
```

---

##### rsiPeriod

Candles usados para calcular o RSI.

```json
"rsiPeriod": 10
```

```text
Menor → RSI mais reativo → atinge extremos mais rápido
Maior → RSI mais suave → precisa de movimento maior para atingir extremos
```

---

##### rsiOverbought / rsiOversold

Limites do RSI para considerar momentum extremo.

```json
"rsiOverbought": 65.0,
"rsiOversold": 35.0
```

```text
rsiOverbought = 65 → qualquer RSI acima de 65 confirma alta
rsiOversold = 35   → qualquer RSI abaixo de 35 confirma queda
```

```text
Valores mais extremos (75/25) → mais rigoroso → menos sinais
Valores mais centrais (60/40) → mais permissivo → mais sinais
```

---

##### emaDistanceFactor

Fator mínimo de distância entre as EMAs.

```json
"emaDistanceFactor": 0.3
```

```text
Maior → exige EMAs mais separadas → mais confirmação → menos sinais
Menor → aceita EMAs mais próximas → mais sinais → mais ruído
```

</details>

<details>
<summary><strong>13.4 BreakoutStrategy</strong></summary>

#### O que é breakout?

Breakout é o momento em que o preço **rompe** uma região onde
ele ficou parado por um tempo.

Imagine uma bola dentro de uma caixa. Enquanto ela está dentro
da caixa, os movimentos são pequenos. Mas quando ela sai da caixa,
ela pode percorrer uma grande distância.

No mercado é a mesma ideia:

```text
O preço fica dentro de uma faixa por vários candles:

1.0830 ─────────────────────── (teto)
         → preço fica aqui ←
1.0810 ─────────────────────── (chão)

De repente, um candle rompe o teto:

1.0830 ─────────────────────── (teto)
                            ↗ 1.0855 ← breakout!
1.0810 ─────────────────────── (chão)
```

---

#### O que é o corpo de um candle?

Para entender o breakout, é preciso saber o que é o **corpo**:

```text
     ┃ ← sombra superior (wick)
   ┏━┛
   ┃   ← CORPO (distância entre open e close)
   ┗━┓
     ┃ ← sombra inferior (wick)
```

```text
corpo = |open - close|

Se open = 1.0820 e close = 1.0845:
  corpo = |1.0820 - 1.0845| = 0.0025
```

Um candle de breakout tem **corpo grande** comparado aos anteriores.
Isso indica que o preço se moveu com força e convicção.

---

#### Lógica da estratégia

A estratégia compara o corpo do candle atual com o corpo médio
dos candles anteriores:

```text
corpo do candle atual > corpo médio × bodyMultiplier
→ breakout detectado
```

Exemplo:

```text
corpo médio dos últimos candles = 0.0008
bodyMultiplier = 2.0

corpo mínimo para breakout = 0.0008 × 2.0 = 0.0016

corpo do candle atual = 0.0020
0.0020 > 0.0016 → breakout ✅

corpo do candle atual = 0.0010
0.0010 < 0.0016 → não é breakout ❌
```

Se o breakout for para cima → BUY.
Se for para baixo → SELL.

---

#### Regime ideal: início de `TRENDING`

Os melhores breakouts acontecem quando o mercado estava comprimido
(em range) e de repente começa a se mover com direção.

#### Limitação conhecida

Muitos breakouts são **falsos**. O preço rompe a faixa, mas logo
volta para dentro. Isso é chamado de "falso rompimento" e é
uma das maiores fontes de perda em estratégias de breakout.

---

#### Configuração no strategies.json

```json
"breakout": {
  "enabled": true,
  "bodyMultiplier": 2.0
}
```

#### Parâmetros explicados

##### bodyMultiplier

Fator mínimo pelo qual o corpo do candle atual precisa ser
maior que o corpo médio dos candles anteriores.

```json
"bodyMultiplier": 2.0
```

```text
Maior → exige candles muito maiores → menos sinais, menos falsos positivos
Menor → aceita candles menores → mais sinais, mais falsos positivos
```

</details>

---

<details>
<summary><strong>13.5 PinBarStrategy</strong></summary>

#### O que é um pin bar?

Pin bar é um padrão de candle que mostra **rejeição** de preço.

Imagine que o mercado tentou subir mas foi "empurrado para baixo"
com força. O resultado é um candle com:

- **sombra longa** na direção que foi rejeitada
- **corpo pequeno** na direção oposta

Visualmente:

```text
Pin bar de rejeição de ALTA (sinal de SELL):

        │ ← sombra longa para CIMA
        │    (mercado tentou subir mas voltou)
    ┌───┤
    │   │ ← corpo pequeno
    └───┘

Pin bar de rejeição de BAIXA (sinal de BUY):

    ┌───┐
    │   │ ← corpo pequeno
    └───┤
        │ ← sombra longa para BAIXO
        │    (mercado tentou cair mas voltou)
```

---

#### O que é sombra (wick)?

A sombra é a parte do candle que fica **fora do corpo**:

```text
     ┃ ← sombra superior = high - max(open, close)
   ┏━┛
   ┃   ← corpo = |open - close|
   ┗━┓
     ┃ ← sombra inferior = min(open, close) - low
```

Exemplo numérico:

```text
open  = 1.0830
close = 1.0828
high  = 1.0850
low   = 1.0827

corpo = |1.0830 - 1.0828| = 0.0002 ← pequeno
sombra superior = 1.0850 - 1.0830 = 0.0020 ← grande

Razão sombra/corpo = 0.0020 / 0.0002 = 10x
→ sombra muito maior que corpo → pin bar de rejeição de alta
```

---

#### Como o ATR calibra os tamanhos

O sistema precisa saber o que é "grande" ou "pequeno" no contexto
do ativo. Para isso ele usa o **ATR**:

```text
tamanho mínimo da sombra = ATR × tailMultiplier
tamanho máximo do corpo   = ATR × bodyMaxMultiplier
```

Se o ATR do ativo for 0.0010:

```text
tailMultiplier = 3.0
  → sombra mínima = 0.0010 × 3.0 = 0.0030

bodyMaxMultiplier = 0.25
  → corpo máximo = 0.0010 × 0.25 = 0.00025
```

Isso garante que um pin bar válido tenha sombra **pelo menos 12 vezes
maior** que o corpo (0.0030 / 0.00025 = 12).

---

#### O que é lookback?

O `lookback` define quantos candles anteriores o sistema verifica
para avaliar o **contexto** do pin bar.

Um pin bar que aparece numa região de suporte ou resistência
tem mais valor do que um que aparece no meio do nada:

```text
lookback = 15
→ o sistema olha os últimos 15 candles
→ verifica se existe algum nível de preço relevante
→ se sim, o pin bar tem mais contexto
```

---

#### Regime ideal: reversão em nível de suporte/resistência

#### Limitação: sem contexto de nível, o sinal é fraco.

---

#### Configuração no strategies.json

```json
"pinBar": {
  "enabled": true,
  "atrPeriod": 10,
  "tailMultiplier": 3.0,
  "bodyMaxMultiplier": 0.25,
  "lookback": 15
}
```

| Parâmetro | O que controla |
|---|---|
| `atrPeriod` | candles para calcular o ATR de referência |
| `tailMultiplier` | tamanho mínimo da sombra (× ATR) |
| `bodyMaxMultiplier` | tamanho máximo do corpo (× ATR) |
| `lookback` | candles de contexto verificados |

</details>

---

<details>
<summary><strong>13.6 SupportResistanceStrategy</strong></summary>

#### O que é suporte?

Suporte é uma região de preço onde o mercado **parou de cair**
e **voltou a subir** no passado.

Pense assim: é como um "chão" que o preço não consegue romper.

```text
Preço caindo:
  1.0850
  1.0840
  1.0830
  1.0820 ← chegou aqui e voltou a subir (suporte)
  1.0830
  1.0840
```

---

#### O que é resistência?

Resistência é o oposto: uma região onde o mercado **parou de subir**
e **voltou a cair**.

É como um "teto" que o preço não consegue romper.

```text
Preço subindo:
  1.0820
  1.0830
  1.0840
  1.0850 ← chegou aqui e voltou a cair (resistência)
  1.0840
  1.0830
```

---

#### O que é clustering de toques?

O sistema não procura suporte e resistência baseados em um único
ponto. Ele procura **regiões** onde o preço **tocou várias vezes**:

```text
Preço:  ... 1.0820 ... 1.0822 ... 1.0819 ... 1.0821 ...

Esses 4 pontos estão muito próximos entre si.
O sistema agrupa eles em um "cluster":
  → região de suporte em torno de 1.0820
  → 4 toques → nível forte
```

Quanto mais toques, mais forte é o nível.

---

#### O que é tolerância baseada em ATR?

O preço não precisa tocar **exatamente** o mesmo valor para ser
considerado um suporte. Existe uma tolerância:

```text
tolerância = ATR × fator interno

Se ATR = 0.0010 e fator = 0.3:
  tolerância = 0.0003

Nível de suporte: 1.0820
Faixa de tolerância: 1.0817 a 1.0823

Qualquer preço nessa faixa é considerado "perto do suporte"
```

---

#### Lógica da estratégia

```text
Preço perto de suporte (dentro da tolerância) → BUY
Preço perto de resistência (dentro da tolerância) → SELL
```

#### Regime ideal: `RANGING`

#### Limitação: em tendências fortes, suporte e resistência são rompidos.

---

#### Configuração no strategies.json

```json
"supportResistance": {
  "enabled": true
}
```

> Os parâmetros internos são calibrados automaticamente com base
> no ATR do ativo. Não há configuração adicional no JSON.

</details>

---

<details>
<summary><strong>13.7 KeltnerChannelStrategy</strong></summary>

#### O que é o Canal de Keltner?

É um canal de preço similar ao Bollinger, mas com uma diferença
importante na forma como calcula a largura das bandas:

| Aspecto | Bollinger | Keltner |
|---|---|---|
| Largura | usa desvio padrão | usa ATR |
| Comportamento | se expande muito em volatilidade | mais estável |
| Melhor para | medir extremos estatísticos | medir breakouts de canal |

```text
Linha central  = EMA do preço
Banda superior = EMA + (multiplicador × ATR)
Banda inferior = EMA - (multiplicador × ATR)
```

Exemplo:

```text
EMA = 1.0840
ATR = 0.0010
multiplicador = 2.0

Banda superior = 1.0840 + (2.0 × 0.0010) = 1.0860
Banda inferior = 1.0840 - (2.0 × 0.0010) = 1.0820
```

---

#### Por que ATR em vez de desvio padrão?

O **desvio padrão** (usado pelo Bollinger) é muito sensível
a candles extremos. Um único candle grande pode "explodir"
a largura das bandas.

O **ATR** é mais estável porque calcula a média dos movimentos
em vez de medir a dispersão estatística. Isso torna o canal
de Keltner mais previsível e menos "nervoso".

---

#### Lógica da estratégia

A estratégia detecta quando o preço **rompe** o canal com um candle
de **corpo grande**:

```text
Candle rompe banda superior com corpo forte → BUY
Candle rompe banda inferior com corpo forte → SELL
```

O tamanho do corpo é verificado da mesma forma que no BreakoutStrategy:

```text
corpo atual > corpo médio × bodyMultiplier → breakout válido
```

---

#### Regime ideal: início de `TRENDING`

#### Limitação: em range, o preço bate nas bandas repetidamente sem romper.

---

#### Configuração no strategies.json

```json
"keltnerChannel": {
  "enabled": true,
  "bodyMultiplier": 2.0
}
```

| Parâmetro | O que controla |
|---|---|
| `bodyMultiplier` | corpo mínimo para validar o rompimento (× corpo médio) |

</details>

---

<details>
<summary><strong>13.8 DonchianBreakoutStrategy</strong></summary>

#### O que é o Canal de Donchian?

O Canal de Donchian é o mais simples de todos os canais.
Ele apenas olha os **extremos** dos últimos N candles:

```text
Banda superior = maior HIGH dos últimos N candles
Banda inferior = menor LOW dos últimos N candles
```

Exemplo com 10 candles:

```text
HIGHs dos últimos 10 candles:
  1.0835, 1.0840, 1.0838, 1.0845, 1.0830,
  1.0842, 1.0847, 1.0843, 1.0841, 1.0839

Maior HIGH = 1.0847 → banda superior

LOWs dos últimos 10 candles:
  1.0815, 1.0820, 1.0818, 1.0825, 1.0810,
  1.0822, 1.0827, 1.0823, 1.0821, 1.0819

Menor LOW = 1.0810 → banda inferior
```

Canal: entre 1.0810 e 1.0847.

---

#### Contexto histórico

Essa estratégia ficou famosa nos anos 1980 quando Richard Dennis
a usou para treinar os **Turtle Traders**. A regra era:

```text
Compra quando o preço atinge a máxima de N dias.
Vende quando o preço atinge a mínima de N dias.
```

A lógica é: se o preço está fazendo **novas máximas**, ele
provavelmente vai continuar subindo (e vice-versa).

---

#### O que é filtro de expansão de ATR?

Nem todo rompimento do canal é válido. Alguns acontecem em momentos
de baixa volatilidade e não têm "combustível" para continuar.

O filtro de expansão verifica se o ATR está **aumentando**:

```text
ATR atual > ATR histórico → volatilidade expandindo → breakout mais confiável
ATR atual < ATR histórico → volatilidade comprimida → breakout pode ser falso
```

---

#### System 1 vs System 2

O projeto suporta dois sistemas Donchian:

| Sistema | Período | Característica |
|---|---|---|
| System 1 | mais curto | entradas mais rápidas, mais sensível |
| System 2 | mais longo | entradas mais lentas, mais confirmadas |

---

#### Regime ideal: `TRENDING`

#### Limitação: em mercados laterais, o preço toca as bandas repetidamente
sem continuar na direção, gerando muitos falsos breakouts.

---

#### Configuração no strategies.json

```json
"donchianSystem1": { "enabled": true },
"donchianSystem2": { "enabled": true }
```

> Os períodos são configurados internamente na classe.
> Quando habilitados, ambos participam da decisão conforme o modo
> de decisão configurado.

</details>

---

### 13.9 Tabela comparativa

| Estratégia | Tipo | Regime ideal | Falha em |
|---|---|---|---|
| Bollinger | Reversão | RANGING | TRENDING forte |
| ZScore | Reversão | RANGING | TRENDING forte |
| EmaRsi | Tendência | TRENDING | RANGING |
| Breakout | Rompimento | Início TRENDING | RANGING |
| PinBar | Price Action | Nível S/R | Sem contexto |
| S/R | Nível | RANGING | TRENDING |
| Keltner | Breakout | Início TRENDING | RANGING |
| Donchian | Breakout | TRENDING | RANGING |

> Apenas backtest com dados reais pode confirmar se existe edge.
> Os parâmetros acima são exemplos didáticos.

---

## 14. Regimes de mercado

O sistema classifica o mercado em 3 regimes:

| Regime | Significado |
|---|---|
| `TRENDING` | mercado com direção clara |
| `RANGING` | mercado lateral / reversivo |
| `CHOPPY` | mercado confuso ou em transição |

---

<details>
<summary><strong>14.1 Explicação detalhada de cada regime</strong></summary>

#### TRENDING

O mercado está subindo ou caindo de forma consistente.

Imagine uma escada rolante: o preço vai subindo degrau por degrau,
cada candle fechando acima do anterior.

```text
10:00 → 1.0800
10:01 → 1.0810
10:02 → 1.0822
10:03 → 1.0831
10:04 → 1.0845
```

#### RANGING

O mercado está "preso" dentro de uma faixa, como uma bola
quicando entre o chão e o teto.

```text
10:00 → 1.0820
10:01 → 1.0815
10:02 → 1.0823
10:03 → 1.0817
10:04 → 1.0821
```

O preço oscila ao redor de 1.0820 sem sair do lugar.

#### CHOPPY

O mercado não está nem em tendência nem em range claro.
Os movimentos são erráticos e imprevisíveis, como uma bola
batendo nas paredes de forma aleatória.

```text
10:00 → 1.0820
10:01 → 1.0835
10:02 → 1.0802
10:03 → 1.0828
10:04 → 1.0810
```

</details>

---

<details>
<summary><strong>14.2 Como o sistema detecta o regime</strong></summary>

O classificador combina 3 indicadores. Todos precisam concordar
para classificar um regime.

---

#### Indicador 1 — Efficiency Ratio (ER)

O ER responde: **o preço foi reto ou ficou indo e voltando?**

```text
ER = |distância em linha reta| / distância total percorrida
```

Exemplo prático:

```text
CENÁRIO A — preço foi reto:
  Começou em 1.0800, terminou em 1.0850
  Distância em linha reta = 0.0050
  Total percorrido = 0.0060

  ER = 0.0050 / 0.0060 = 0.83 → alto → TRENDING

CENÁRIO B — preço ficou no lugar:
  Começou em 1.0800, terminou em 1.0802
  Distância em linha reta = 0.0002
  Total percorrido = 0.0080

  ER = 0.0002 / 0.0080 = 0.025 → baixo → RANGING
```

| ER | Significado |
|---|---|
| próximo de 1.0 | mercado foi em linha reta → tendência |
| próximo de 0.0 | mercado ficou no lugar → lateral |
| entre 0.13 e 0.20 | zona indefinida → choppy |

---

#### Indicador 2 — Distância entre EMA 8 e EMA 21

O sistema calcula duas médias móveis:

```text
EMA 8  → média rápida (últimos 8 minutos)
EMA 21 → média lenta (últimos 21 minutos)
```

E mede a distância entre elas:

```text
emaDistance = |EMA8 - EMA21|
```

Depois compara essa distância com o ATR para saber se é significativa:

```text
emaDistance > ATR × 0.20 → EMAs separadas → tem direção
emaDistance < ATR × 0.40 → EMAs coladas → sem direção
```

Exemplo:

```text
EMA8 = 1.0855
EMA21 = 1.0840
emaDistance = 0.0015

ATR = 0.0012
ATR × 0.20 = 0.00024

0.0015 > 0.00024 → EMAs bem separadas → tendência confirmada ✅
```

---

#### Indicador 3 — ATR Ratio

Compara a volatilidade recente com a volatilidade histórica:

```text
ATR Ratio = ATR rápido (14 candles) / ATR lento (50 candles)
```

| ATR Ratio | Significado |
|---|---|
| ≈ 1.0 | volatilidade normal |
| > 1.2 | volatilidade aumentando |
| < 0.9 | volatilidade baixa demais |

Exemplo:

```text
ATR 14 candles = 0.00134
ATR 50 candles = 0.00120

ATR Ratio = 0.00134 / 0.00120 = 1.12
→ volatilidade levemente acima do normal
```

</details>

---

<details>
<summary><strong>14.3 Lógica de classificação</strong></summary>

As 3 condições precisam ser verdadeiras simultaneamente:

#### TRENDING

```text
efficiency >= 0.20        → preço andou reto nos últimos 30 minutos
emaDistance > ATR × 0.20  → EMAs separadas o suficiente
atrRatio >= 0.90          → volatilidade normal ou acima
```

#### RANGING

```text
efficiency <= 0.13        → preço ficou quase no lugar
emaDistance < ATR × 0.40  → EMAs muito coladas
atrRatio <= 1.20          → volatilidade estável
```

#### CHOPPY

Tudo que não se encaixa em TRENDING nem em RANGING.

```text
efficiency entre 0.13 e 0.20 → zona indefinida
ou alguma das outras condições não atendida
```

#### Distribuição esperada (candles de 1 minuto)

| Regime | Proporção | Por que |
|---|---|---|
| RANGING | ~40% | mercado lateral é o estado mais comum |
| CHOPPY | ~45% | muitas vezes o mercado está "entre" regimes |
| TRENDING | ~15% | tendências claras são menos frequentes |

</details>

---

<details>
<summary><strong>14.4 Confirmação de regime</strong></summary>

O sistema não muda o regime a cada minuto. Ele exige **confirmação**
para evitar ficar trocando de regime por causa de ruído.

O processo funciona em 3 etapas:

#### Etapa 1 — Decimação

O sistema só avalia o regime **a cada 15 candles** (15 minutos).
Isso filtra o ruído dos movimentos minuto a minuto.

```text
Candle 1 → não avalia
Candle 2 → não avalia
...
Candle 15 → avalia o regime ← primeira avaliação
Candle 16 → não avalia
...
Candle 30 → avalia o regime ← segunda avaliação
```

#### Etapa 2 — Janela de observação

Quando avalia, o sistema olha os **últimos 200 candles** para calcular
os indicadores. Isso equivale a ~3h20min de mercado.

Por que 200?

```text
Usar poucos candles (ex: 20) → muito sensível a movimentos curtos
Usar muitos candles (ex: 1000) → muito lento para detectar mudanças

200 candles é um equilíbrio calibrado para 1 minuto
```

#### Etapa 3 — Filtro de persistência

Mesmo que a avaliação detecte um novo regime, o sistema exige
**3 avaliações consecutivas iguais** para confirmar:

```text
Avaliação 1 (candle 15):  RANGING → candidato
Avaliação 2 (candle 30):  RANGING → segunda confirmação
Avaliação 3 (candle 45):  RANGING → terceira confirmação → CONFIRMADO ✅
```

Se no meio do caminho o resultado mudar, o contador reseta:

```text
Avaliação 1: RANGING → candidato
Avaliação 2: RANGING → segunda confirmação
Avaliação 3: CHOPPY  → candidato diferente → reseta ↩
Avaliação 4: CHOPPY  → segunda confirmação
Avaliação 5: CHOPPY  → terceira → CHOPPY CONFIRMADO ✅
```

Tempo total de confirmação:

```text
15 candles × 3 avaliações = 45 candles
45 candles × 1 minuto = 45 minutos
```

> O mercado precisa ficar no novo regime por **45 minutos**
> para que a mudança seja confirmada.

</details>

---

<details>
<summary><strong>14.5 Qual estratégia funciona melhor em qual regime</strong></summary>

| Estratégia | RANGING | TRENDING | CHOPPY | Justificativa |
|---|---|---|---|---|
| Bollinger | ✅ forte | ❌ fraca | ⚠️ instável | reversão funciona quando preço respeita os limites |
| ZScore | ✅ forte | ❌ fraca | ⚠️ instável | z-score extremo só é confiável em range |
| EmaRsi | ❌ fraca | ✅ forte | ⚠️ instável | EMAs precisam de direção para separar |
| Breakout | ❌ falsos | ✅ início | ⚠️ instável | rompimentos reais acontecem na saída do range |
| PinBar | ✅ em S/R | ⚠️ depende | ❌ fraca | precisa de nível claro para funcionar |
| S/R | ✅ forte | ❌ rompidos | ⚠️ instável | níveis só são respeitados em range |
| Keltner | ❌ falsos | ✅ início | ⚠️ instável | canal ATR detecta início de tendência |
| Donchian | ❌ falsos | ✅ forte | ⚠️ instável | novas máximas/mínimas indicam tendência |

> Esta tabela é uma orientação conceitual baseada no tipo de cada estratégia.
> O edge real só pode ser confirmado via backtest com dados específicos.

</details>

---

## 15. Gestão de risco

O TradingBoot possui **4 camadas de proteção** que atuam em sequência
antes de executar qualquer operação:

```text
Camada 1: TradeValidator    → o ativo já tem operação aberta?
Camada 2: AtrRiskManager    → a volatilidade está dentro do limite?
Camada 3: VolatilityFilter  → o mercado está se movendo o suficiente?
Camada 4: TradeExecutor     → o ROI do proposal é >= minRoiPercent?
```

Se **qualquer** camada reprovar, a operação **não acontece**.

---

<details>
<summary><strong>15.1 Stake fixo</strong></summary>

#### O que é stake fixo?

O sistema opera com um valor fixo por operação. Esse valor é
configurado no `strategies.json` e **nunca é reduzido**.

```json
"trade": {
  "amount": 10
}
```

#### Como funciona na prática

```text
Cenário 1 — ATR dentro do limite:
  → executa com $10.00

Cenário 2 — ATR acima do limite:
  → NÃO executa (operação bloqueada)
```

Não existe "meio termo". O sistema nunca opera com $7.50 ou $3.25.
Ou é o valor cheio configurado, ou a operação não acontece.

#### Por que stake fixo?

Motivos:

```text
1. Simplifica a análise de performance
   → todos os trades têm o mesmo tamanho

2. Facilita comparação entre ativos
   → $10 em EURUSD é comparável com $10 em GBPUSD

3. Evita viés de tamanho
   → stakes variáveis distorcem winRate e expectancy

4. Consistente com a natureza de contratos binários
   → o resultado é fixo: ganha X ou perde o stake
```

</details>

---

<details>
<summary><strong>15.2 Gestão por ATR</strong></summary>

#### O que é gestão por ATR?

O sistema usa o ATR para verificar se a **volatilidade atual**
está dentro de limites aceitáveis. Se a volatilidade estiver
muito alta, a operação é bloqueada.

#### Por que bloquear em volatilidade alta?

Em momentos de volatilidade extrema:

```text
→ o preço se move de forma imprevisível
→ as estratégias perdem confiabilidade
→ os sinais têm mais chance de ser falsos
→ o risco de perda aumenta
```

#### Como funciona

O sistema calcula o **ATR Ratio**:

```text
ATR Ratio = ATR rápido (14 candles) / ATR lento (50 candles)
```

E compara com um limite chamado `blockThreshold`:

```text
ATR Ratio < blockThreshold → volatilidade aceitável → executa ✅
ATR Ratio >= blockThreshold → volatilidade alta → bloqueia ❌
```

#### Tipos de operação e seus limites

O sistema classifica cada operação em um tipo baseado na estratégia:

| Tipo | Estratégias | blockThreshold |
|---|---|---|
| REVERSAL\_RANGE | Bollinger, ZScore, PinBar, S/R | 1.50 |
| TREND\_BREAKOUT | EmaRsi, Breakout, Keltner, Donchian | 2.20 |

Por que limites diferentes?

```text
REVERSAL_RANGE (blockThreshold = 1.50):
  → estratégias de reversão precisam de mercado estável
  → volatilidade 50% acima do normal já é arriscada para reversão

TREND_BREAKOUT (blockThreshold = 2.20):
  → estratégias de tendência toleram mais volatilidade
  → o breakout precisa de algum "combustível" de volatilidade
  → só bloqueia quando está 120% acima do normal
```

#### Exemplo prático

```text
Estratégia: BollingerMeanReversion (tipo REVERSAL_RANGE)
blockThreshold: 1.50

ATR 14 candles = 0.00150
ATR 50 candles = 0.00120
ATR Ratio = 0.00150 / 0.00120 = 1.25

1.25 < 1.50 → volatilidade aceitável → executa ✅
```

```text
Mesma estratégia em outro momento:

ATR 14 candles = 0.00200
ATR 50 candles = 0.00120
ATR Ratio = 0.00200 / 0.00120 = 1.67

1.67 > 1.50 → volatilidade muito alta → bloqueia ❌
```

</details>

---

<details>
<summary><strong>15.3 ROI mínimo por ativo</strong></summary>

#### O que é ROI mínimo?

Antes de comprar um contrato, o sistema pede uma **proposta**
(proposal) à Deriv. A proposta retorna o payout que a corretora
está oferecendo naquele momento.

O sistema então calcula o ROI esperado:

```text
ROI = (payout - stake) / stake × 100
```

E compara com o `minRoiPercent` configurado. Se o ROI for menor,
a operação é descartada.

#### Exemplo detalhado

```text
stake = $10
payout oferecido = $17.00

profit esperado = $17.00 - $10.00 = $7.00
ROI = $7.00 / $10.00 × 100 = 70%

minRoiPercent = 70.0

70% >= 70% → aceita ✅
```

```text
stake = $10
payout oferecido = $16.50

profit esperado = $16.50 - $10.00 = $6.50
ROI = $6.50 / $10.00 × 100 = 65%

minRoiPercent = 70.0

65% < 70% → descarta ❌
```

#### Por que 70% é o valor padrão?

Para responder isso, precisamos entender o break-even.

Com ROI de 70%, o break-even é:

```text
break-even = 1 / (1 + 0.70) = 1 / 1.70 = 58.82%
```

Isso significa: você precisa acertar **mais de 58.82%** das
operações para ter lucro no longo prazo.

Se aceitarmos ROI menor, o break-even sobe:

| ROI aceito | Break-even | Dificuldade |
|---|---|---|
| 80% | 55.55% | mais fácil |
| 70% | 58.82% | moderado |
| 60% | 62.50% | mais difícil |
| 50% | 66.67% | muito difícil |

```text
70% é um valor conservador que:
  → exige ~59% de acerto (alcançável com edge real)
  → descarta operações com payout ruim
  → preserva a qualidade da seleção de trades
```

#### Por que isso é configurável por ativo?

Porque diferentes ativos podem ter payouts médios diferentes:

```text
frxEURUSD → payout médio ~82% → minRoiPercent = 70 funciona
frxXAUUSD → payout médio ~75% → minRoiPercent = 70 funciona
frxGBPNZD → payout médio ~68% → minRoiPercent = 65 pode ser necessário
```

</details>

---

<details>
<summary id="15.4-filtro-de-volatilidade"><strong>15.4 Filtro de volatilidade</strong></summary>

#### O que é o filtro de volatilidade?

Antes das estratégias serem avaliadas, o sistema verifica se
o mercado está **se movendo o suficiente** para que as estratégias
tenham condição de gerar sinais confiáveis.

Se o mercado estiver completamente parado (range muito estreito),
as estratégias são puladas.

#### Como funciona

O sistema mede o **range** dos últimos N candles:

```text
range = maior máxima - menor mínima dos últimos rangeLookback candles
```

E compara com o range médio histórico:

```text
Se range atual < range médio × rangeMultiplier
  → mercado parado demais → pula as estratégias

Se range atual >= range médio × rangeMultiplier
  → mercado ativo → avalia as estratégias
```

#### Configuração

```json
"engine": {
  "rangeLookback": 14,
  "rangeMultiplier": 1.10
}
```

##### rangeLookback

Define quantos candles são usados para medir o range recente:

```json
"rangeLookback": 14
```

Exemplo:

```text
Últimos 14 candles:
  maior máxima = 1.0835
  menor mínima = 1.0810

Range atual = 1.0835 - 1.0810 = 0.0025
```

```text
Maior → mais candles, mais estável, menos reativo
Menor → menos candles, mais reativo, mais ruidoso
```

##### rangeMultiplier

Multiplicador sobre o range médio para definir o limite mínimo:

```json
"rangeMultiplier": 1.10
```

Exemplo:

```text
Range médio dos últimos 14 candles = 0.0020
rangeMultiplier = 1.10

Limite mínimo = 0.0020 × 1.10 = 0.0022

Range atual = 0.0015
0.0015 < 0.0022 → mercado parado → estratégias puladas ❌

Range atual = 0.0025
0.0025 >= 0.0022 → mercado ativo → estratégias avaliadas ✅
```

```text
Maior → mais exigente → menos operações → só mercados movimentados
Menor → mais permissivo → mais operações → mercados calmos também passam
```

</details>

---

<details>
<summary><strong>15.5 Exemplo completo de um ciclo de proteção</strong></summary>

```text
Sinal recebido: BUY
Estratégia: BollingerMeanReversion
Ativo: frxEURUSD
Stake configurado: $10

═══════════════════════════════════════════

Camada 1: TradeValidator
  Já existe operação aberta nesse ativo?
  → Não
  → ✅ Aprovado — passa para camada 2

═══════════════════════════════════════════

Camada 2: AtrRiskManager
  Tipo da estratégia: REVERSAL_RANGE
  blockThreshold: 1.50

  ATR 14 candles = 0.00130
  ATR 50 candles = 0.00120
  ATR Ratio = 0.00130 / 0.00120 = 1.08

  1.08 < 1.50 → volatilidade aceitável
  → ✅ Aprovado — passa para camada 3

═══════════════════════════════════════════

Camada 3: VolatilityFilter
  rangeLookback = 14
  rangeMultiplier = 1.10
  range médio = 0.0020
  limite = 0.0020 × 1.10 = 0.0022
  range atual = 0.0025

  0.0025 >= 0.0022 → mercado ativo
  → ✅ Aprovado — passa para camada 4

═══════════════════════════════════════════

Camada 4: TradeExecutor
  Solicita proposal à Deriv
  Deriv retorna payout = $17.00

  ROI = ($17.00 - $10.00) / $10.00 × 100 = 70%
  minRoiPercent = 70.0

  70% >= 70% → ROI aceitável
  → ✅ Aprovado

═══════════════════════════════════════════

Resultado: contrato CALL aberto com stake $10.00
```

#### Exemplo onde uma camada bloqueia

```text
Mesma situação, mas ATR mudou:

Camada 2: AtrRiskManager
  ATR 14 candles = 0.00200
  ATR 50 candles = 0.00120
  ATR Ratio = 1.67

  1.67 > 1.50 → volatilidade muito alta
  → ❌ BLOQUEADO

Resultado: operação não executada.
As camadas 3 e 4 nem são verificadas.
```

</details>

---

## 16. Relatórios gerados

```text
data/reports/
├── trades_{symbol}_{date}.csv
├── trades_{symbol}_{date}.json
├── daily_summary_{date}.json
└── regime_report_{symbol}_{date}.json

```

<details>
<summary><strong>16.1 trades CSV e JSON</strong></summary>

Campos principais:

| Campo | Descrição |
|---|---|
| `entryTimestampBrasilia` | horário de entrada |
| `exitTimestampBrasilia` | horário de saída |
| `symbol` | ativo operado |
| `strategy` | estratégia que gerou o sinal |
| `regime` | regime no momento da entrada |
| `signalType` | BUY ou SELL |
| `stake` | valor apostado |
| `profit` | lucro ou perda |
| `payout` | valor recebido |
| `roiPct` | retorno percentual |
| `result` | WIN ou LOSS |

</details>

<details>
<summary><strong>16.2 daily_summary</strong></summary>

```json
{
  "dateBrasilia": "2026-04-29",
  "totalTrades": 24,
  "wins": 14,
  "losses": 10,
  "winRate": 58.33,
  "totalProfit": 10.80,
  "regimePerformance": {
    "RANGING": {
      "trades": 14,
      "wins": 9,
      "losses": 5,
      "winRate": 64.28,
      "totalProfit": 8.30,
      "expectancy": 0.59
    },
    "TRENDING": {
      "trades": 6,
      "wins": 3,
      "losses": 3,
      "winRate": 50.0,
      "totalProfit": 0.00,
      "expectancy": 0.00
    }
  }
}
```

#### Como interpretar

- `winRate > 58.82%` com ROI 70% → acima do break-even → positivo
- `regimePerformance` → mostra em qual regime a estratégia funcionou

</details>

<details>
<summary><strong>16.3 regime_report</strong></summary>

O relatório de regime é gerado em um único arquivo JSON por ativo por dia,
unificando os dados técnicos de transições e o resumo percentual do comportamento
do mercado.

```json
{
   "symbol": "frxEURUSD",
   "date": "2026-04-29",
   "summary": {
      "totalMinutes": 480,
      "distribution": {
         "RANGING":  { "minutes": 210, "percent": 43.8 },
         "CHOPPY":   { "minutes": 195, "percent": 40.6 },
         "TRENDING": { "minutes": 75,  "percent": 15.6 }
      },
      "currentRegime": "RANGING",
      "currentRegimeSince": "18:30"
   },
   "events": [
      {
         "marketTimestamp": "2026-04-29T12:30:00Z",
         "marketTimestampBrasilia": "2026-04-29T09:30-03:00",
         "processingTimestamp": "2026-04-29T12:30:01.123Z",
         "symbol": "frxEURUSD",
         "previousRegime": "CHOPPY",
         "currentRegime": "RANGING",
         "durationMinutes": 210,
         "atrFast": 0.000134,
         "atrBase": 0.000120,
         "atrRatio": 1.12,
         "emaDistance": 0.000032,
         "efficiency": 0.11,
         "metricsValid": true
      }
   ]
}
```

| Campo | Descrição                                          |
|---|----------------------------------------------------|
| `summary.totalMinutes` | Tempo total observado no dia.                      |
| `summary.distribution` | Percentual do tempo em cada regime.                |
| `summary.currentRegime` | regime ativo no momento.                           |
| `summary.currentRegimeSince` | horário de início do regime atual (Brasília).<br/> |
| `events[].marketTimestamp` | momento real de mercado da confirmação (UTC).      |
| `events[].processingTimestamp` | momento em que o sistema processou.                |
| `events[].durationMinutes` | quanto tempo o regime durou.                       |
| `events[].efficiency` | Efficiency Ratio calculado.                        |
| `events[].emaDistance` | distância entre EMA 8 e EMA 21.                    |
| `events[].atrRatio` | razão de volatilidade.                             |

> **Observação:** O processingTimestamp é mantido para diagnóstico. Durante o warm-up histórico
>ele será muito próximo do horário de startup, enquanto o marketTimestamp
>reflete o tempo real de mercado.


</details>

---

## 17. Ferramentas auxiliares

<details>
<summary><strong>17.1 Download de histórico de candles</strong></summary>

**Classe:** `DerivHistoryDownloadTool`

```text
src/main/java/.../tools/history/DerivHistoryDownloadTool.java
```

Edite as constantes e execute via IDE:

```java
private static final List<String> SYMBOLS = List.of("frxEURUSD");
private static final int GRANULARITY_SECONDS = 60;
private static final int DAYS_BACK = 90;
```

**Gera:**

```text
data/history/frxEURUSD_60.json
```

> Usa endpoint público. Não requer autenticação.

</details>

<details>
<summary><strong>17.2 Listagem de ativos</strong></summary>

**Classe:** `ListOfFinancialAssets`

**Gera:**

```text
data/active_symbols.full.json
```

Contém: símbolos, contratos, famílias de trade, durações.

> Usa endpoint público. Não requer autenticação.

</details>

<details>
<summary><strong>17.3 Conversão para PDF</strong></summary>

**Classe:** `HistoryJsonToPdfTool`

Converte arquivos JSON de histórico em PDF para visualização.

</details>

## 18. Backtest

<details>
<summary><strong>18.1 O que é backtest</strong></summary>

Simulação de uma estratégia usando dados históricos:

> "Se eu tivesse operado essa estratégia nos últimos 90 dias,
> qual teria sido o resultado?"

#### Limitações

- **overfitting** — calibrar demais para o passado
- **survivorship bias** — testar apenas ativos que sobreviveram
- **look-ahead bias** — usar dados que não estariam disponíveis

> Backtest é apenas o primeiro passo.
> Validação em conta demo é obrigatória antes de operar com capital real.

</details>

<details>
<summary><strong>18.2 Passo a passo completo</strong></summary>

#### Passo 1 — Baixar o histórico

Execute `DerivHistoryDownloadTool` com o ativo desejado.

#### Passo 2 — Configurar strategies.json

```json
"trade": { "enabled": false },
"strategies": { "emaRsi": { "enabled": true } }
```

#### Passo 3 — Executar

```text
src/main/java/.../backtest/BacktestRunner.java
```

#### Passo 4 — Interpretar

```text
╔══════════════════════════════════════════╗
║     BACKTEST REPORT - frxEURUSD          ║
╠══════════════════════════════════════════╣
║  Total trades:     142                   ║
║  Win Rate:         58.5%                 ║
║  Expectancy:       0.0423 R              ║
║  Profit Factor:    1.10                  ║
║  Max Drawdown:     4.00 R                ║
║  RESULT:           WEAK                  ║
║  HAS EDGE:         NO ❌                 ║
╚══════════════════════════════════════════╝
```

</details>

<details>
<summary><strong>18.3 Métricas explicadas</strong></summary>

| Métrica | O que mede | Interpretação |
|---|---|---|
| `winRate` | % de acertos | > break-even = positivo |
| `avgWin` | ganho médio por win | quanto ganha por acerto |
| `avgLoss` | perda média por loss | quanto perde por erro |
| `payoffRatio` | avgWin / avgLoss | > 1.0 = ganhos maiores que perdas |
| `expectancy` | retorno médio por operação | > 0 = edge presente |
| `profitFactor` | lucro bruto / perda bruta | > 1.0 = sistema lucrativo |
| `maxDrawdown` | maior queda acumulada | mostra risco máximo |
| `maxConsecutiveLosses` | maior sequência de perdas | prepara psicologicamente |

</details>

<details>
<summary><strong>18.4 Classificação automática</strong></summary>

| Classificação | Critério |
|---|---|
| `APPROVED` | trades ≥ 30, winRate > 52%, expectancy > 0.05, profitFactor > 1.1 |
| `WEAK` | expectancy > 0 mas não atende todos os critérios |
| `REJECTED` | expectancy ≤ 0 |
| `NO_DATA` | menos de 30 trades |

</details>

<details>
<summary><strong>18.5 Fluxo de pesquisa recomendado</strong></summary>

```text
1. Formule hipótese
2. Baixe histórico (90+ dias)
3. Configure estratégias
4. Rode o backtest
5. Interprete:
   APPROVED  → avance para conta demo
   WEAK      → ajuste ou descarte
   REJECTED  → descarte
   NO_DATA   → gere mais trades
6. Valide em demo por 30+ dias
7. Compare backtest vs runtime
8. Se edge confirmado → considere conta real
```

</details>

---

## 19. Guia de contribuição

<details>
<summary><strong>19.1 Padrões de código</strong></summary>

| Tipo | Padrão |
|---|---|
| Serviços | `DerivTradeService` |
| Componentes | `TradeExecutor` |
| Interfaces | `TradingStrategy` |
| Records | `Bar`, `Signal` |
| Enums | `DecisionMode` |
| Injeção | **sempre via construtor** |

</details>

<details>
<summary><strong>19.2 Como adicionar uma estratégia</strong></summary>

```text
1. Criar classe em strategy/ implementando TradingStrategy
2. Adicionar buildXxx() no StrategyBuilder
3. Adicionar bloco no strategies.json
4. Adicionar chave no StrategyWeightProfile
5. Adicionar no Set do ConfluenceTypeResolver
```

</details>

<details>
<summary><strong>19.3 Formato de commit</strong></summary>

```text
<tipo>(escopo): descrição curta

📦 Pacote: <nome>

* Classe:
  - O que mudou
```

Tipos: `feat`, `fix`, `refactor`, `docs`, `chore`, `test`

</details>

<details>
<summary><strong>19.4 Pull Request</strong></summary>

Descreva:

- o que foi implementado
- por que
- quais classes foram alteradas
- como testar

</details>

---

## 20. FAQ

<details>
<summary><strong>Por que o TradingBoot não está operando?</strong></summary>

Verifique na ordem:

1. O `application.yml` existe?
2. Os campos estão preenchidos corretamente?
3. O token tem os escopos certos?
4. O `strategies.json` existe?
5. O `trade.enabled` está `true`?
6. O ativo está disponível na Deriv?
7. O `minRoiPercent` está alto demais?

</details>

<details>
<summary><strong>O que significa "OTP request failed | status=429"?</strong></summary>

Rate limit do Cloudflare. O sistema tenta novamente automaticamente:

```text
5s → 15s → 30s → 60s → 120s
```

</details>

<details>
<summary><strong>Por que o regime sempre aparece como RANGING?</strong></summary>

- Histórico insuficiente (precisa de pelo menos 200 candles para classificar)
- O regime é confirmado após o startup via warm-up do histórico carregado.
  Se o histórico for menor que 200 candles, o regime permanece CHOPPY por padrão.
- Em runtime, uma mudança de regime exige 3 avaliações consecutivas,
  o que representa 45 minutos de permanência no novo estado.
- Se o arquivo `regime_report_{symbol}_{date}.json` estiver sendo gerado
  mas o `currentRegime` sempre aparecer como RANGING, verifique os
  thresholds de `efficiency` e `emaDistance` nos eventos — pode indicar
  que o ativo opera predominantemente em lateral naquele período.

</details>

<details>
<summary><strong>Backtest retornou NO_DATA?</strong></summary>

- As estratégias estão gerando sinais?
- O histórico é suficiente?
- O filtro de volatilidade está muito restritivo?

</details>

<details>
<summary><strong>Diferença entre VOTING e CONFLUENCE?</strong></summary>

| Aspecto | VOTING | CONFLUENCE |
|---|---|---|
| Critério | unanimidade | score ponderado |
| Regime | não considera | considera |
| Flexibilidade | baixa | alta |

</details>

<details>
<summary><strong>O que é CHOPPY?</strong></summary>

Zona de transição entre RANGING e TRENDING. O mercado não está
claramente em nenhum dos dois. A maioria das estratégias não
funciona bem nesse regime.

</details>

<details>
<summary><strong>Por que a operação foi descartada com sinal válido?</strong></summary>

Possíveis motivos:

- ROI abaixo do `minRoiPercent`
- ATR acima do `blockThreshold`
- Já existe operação aberta no ativo
- Mercado parado demais (VolatilityFilter)

</details>

<details>
<summary><strong>Posso usar em conta real imediatamente?</strong></summary>

**Não.**

```text
Fluxo correto: backtest → demo → real
```

</details>

<details>
<summary><strong>E se o backtest for diferente do runtime?</strong></summary>

Indica que o edge pode não ser real. Causas comuns:

- spread e slippage
- payout real vs simulado
- overfitting de parâmetros

Descarte a hipótese e teste outra combinação.

</details>

<details>
<summary><strong>Como descobrir se a estratégia funciona em um ativo?</strong></summary>

```text
1. Baixe o histórico do ativo
2. Configure strategies.json
3. Execute BacktestRunner
4. Veja se o resultado é APPROVED
5. Se sim, valide em conta demo por 30 dias
```

</details>

---

## 21. Aviso importante

> **Este projeto é desenvolvido para fins de estudo e pesquisa.**

```text
- NÃO é conselho financeiro
- NÃO garante resultados
- Trading envolve risco real de perda de capital
- Sempre teste em conta demo antes de conta real
- Nunca use dinheiro que não pode perder
- Resultados de backtest não garantem resultados futuros
- Edge identificado hoje pode desaparecer amanhã
```


