package com.github.dayviddouglas.TradingBoot.tools.history;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import static org.apache.pdfbox.pdmodel.common.PDRectangle.LETTER;

/**
 * Ferramenta standalone para converter o arquivo JSON de histórico de candles em arquivos PDF.
 *
 * Lê o arquivo JSON configurado em {@link #INPUT_JSON}, sanitiza o texto para compatibilidade
 * com o PDFBox, divide em partes respeitando o limite de {@link #MAX_PDF_BYTES} por arquivo
 * e gera os PDFs no diretório {@link #OUTPUT_DIR}.
 *
 * A divisão em partes é feita por estimativa de tamanho via {@link #renderPdfBytes}:
 * cada candidato de chunk é renderizado para verificar o tamanho em bytes antes de confirmar.
 * O tamanho do chunk é ajustado dinamicamente: cresce quando há folga e encolhe quando excede o limite.
 *
 * A escrita de cada arquivo PDF utiliza o padrão de arquivo temporário com renomeação atômica,
 * evitando estados parcialmente escritos. A ferramenta é executada diretamente via
 * {@link #main(String[])}, independente do contexto Spring.
 */
public class HistoryJsonToPdfTool {

    /** Arquivo JSON de entrada a ser convertido. */
    private static final Path INPUT_JSON = Path.of("data", "history", "frxEURUSD_60.json");

    /** Diretório de saída onde os PDFs gerados serão salvos. */
    private static final Path OUTPUT_DIR = Path.of("data", "history", "pdf");

    /** Tamanho máximo em bytes de cada arquivo PDF gerado. */
    private static final long MAX_PDF_BYTES = 14L * 1024L * 1024L; // 14 MB

    /** Fonte monoespaçada utilizada para renderização do texto nos PDFs. */
    private static final PDType1Font FONT = PDType1Font.COURIER;

    private static final float FONT_SIZE  = 9f;
    private static final float MARGIN     = 36f;
    private static final PDRectangle PAGE_SIZE = LETTER;

    /**
     * Ponto de entrada da ferramenta. Lê o JSON, sanitiza, divide em partes e gera os PDFs.
     *
     * @throws Exception em caso de falha na leitura do JSON ou na geração dos PDFs
     */
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        String raw  = Files.readString(INPUT_JSON, StandardCharsets.UTF_8);
        String json = sanitizeForPdf(raw);

        String       baseName = stripExtension(INPUT_JSON.getFileName().toString());
        List<String> parts    = splitToPdfSizedParts(json);

        for (int i = 0; i < parts.size(); i++) {
            String partText = parts.get(i);
            int    partNo   = i + 1;

            String outName  = String.format("%s_part%03d.pdf", baseName, partNo);
            Path   outFile  = OUTPUT_DIR.resolve(outName);

            byte[] pdfBytes = renderPdfBytes(partText, null);
            writeAtomic(outFile, pdfBytes);
        }

        System.out.println("Done. PDFs created at: " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Sanitiza o texto para compatibilidade com o PDFBox, removendo caracteres
     * de controle que causam falha no método {@code showText}:
     * <ul>
     *   <li>Normaliza quebras de linha: {@code \r\n} e {@code \r} → {@code \n}</li>
     *   <li>Remove caracteres de controle ({@code 0x00-0x1F}) exceto {@code \n} e {@code \t}</li>
     *   <li>Converte {@code \t} em 4 espaços para evitar comportamento inesperado na fonte</li>
     * </ul>
     *
     * @param s texto bruto lido do arquivo JSON
     * @return texto sanitizado pronto para renderização
     */
    private static String sanitizeForPdf(String s) {
        if (s == null) return "";

        s = s.replace("\r\n", "\n").replace("\r", "\n");

        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (ch == '\n' || ch == '\t') {
                sb.append(ch);
                continue;
            }

            // Remove caracteres de controle restantes
            if (ch < 0x20) continue;

            sb.append(ch);
        }

        return sb.toString().replace("\t", "    ");
    }

    /**
     * Divide o texto completo em partes que, quando renderizadas como PDF,
     * não excedam {@link #MAX_PDF_BYTES}.
     *
     * O tamanho do chunk inicial é {@code 400_000} caracteres e é ajustado dinamicamente:
     * cresce 20% quando há mais de 30% de folga e encolhe 25% quando excede o limite.
     * Um chunk mínimo de {@code 10_000} caracteres é aplicado como fallback de segurança.
     *
     * @param fullText texto completo já sanitizado
     * @return lista de partes de texto prontas para renderização
     * @throws IOException se ocorrer falha durante a renderização de estimativa
     */
    private static List<String> splitToPdfSizedParts(String fullText) throws IOException {
        List<String> parts = new ArrayList<>();

        int index     = 0;
        int n         = fullText.length();
        int chunkChars = 400_000;

        while (index < n) {
            int remaining = n - index;
            int tryLen    = Math.min(chunkChars, remaining);

            while (true) {
                String candidate = fullText.substring(index, index + tryLen);
                byte[] pdf       = renderPdfBytes(candidate, null);

                if (pdf.length <= MAX_PDF_BYTES) {
                    parts.add(candidate);
                    index += tryLen;

                    // Aumenta o chunk quando há folga suficiente
                    if (pdf.length < MAX_PDF_BYTES * 0.70 && chunkChars < 2_000_000) {
                        chunkChars = (int) Math.min(2_000_000, (int) (chunkChars * 1.2));
                    }
                    break;
                }

                // Reduz o chunk e retenta
                tryLen = (int) (tryLen * 0.75);
                if (tryLen < 10_000) {
                    // Fallback: força o chunk mínimo para não travar
                    tryLen = 10_000;
                    String fallback = fullText.substring(index, Math.min(n, index + tryLen));
                    parts.add(fallback);
                    index     += fallback.length();
                    chunkChars = 200_000;
                    break;
                }
            }
        }

        return parts;
    }

    /**
     * Renderiza o texto informado em um PDF em memória e retorna os bytes resultantes.
     * O texto é quebrado em linhas com base na largura máxima da página menos as margens.
     * Quando {@code header} for não nulo e não vazio, é exibido no topo da primeira página.
     *
     * @param text   texto a ser renderizado
     * @param header cabeçalho opcional exibido no topo da primeira página; {@code null} para omitir
     * @return bytes do PDF gerado
     * @throws IOException se ocorrer falha durante a criação do documento
     */
    private static byte[] renderPdfBytes(String text, String header) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            List<String> lines     = wrapToLines(text, PAGE_SIZE.getWidth() - 2 * MARGIN);
            int          lineIndex = 0;

            while (lineIndex < lines.size()) {
                PDPage page = new PDPage(PAGE_SIZE);
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(FONT, FONT_SIZE);

                    float leading = FONT_SIZE * 1.2f;
                    float startX  = MARGIN;
                    float startY  = PAGE_SIZE.getHeight() - MARGIN;

                    cs.newLineAtOffset(startX, startY);

                    if (header != null && !header.isBlank()) {
                        cs.showText(header);
                        cs.newLineAtOffset(0, -leading);
                        cs.showText("------------------------------------------------------------");
                        cs.newLineAtOffset(0, -leading);
                    }

                    float usableHeight  = PAGE_SIZE.getHeight() - 2 * MARGIN;
                    int   maxLines      = (int) Math.floor(usableHeight / leading);
                    int   headerLines   = (header == null || header.isBlank()) ? 0 : 2;
                    int   linesThisPage = Math.max(1, maxLines - headerLines);

                    int end = Math.min(lines.size(), lineIndex + linesThisPage);
                    for (int i = lineIndex; i < end; i++) {
                        cs.showText(lines.get(i));
                        cs.newLineAtOffset(0, -leading);
                    }

                    cs.endText();
                    lineIndex = end;
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Quebra o texto em linhas respeitando a largura máxima da página em pontos.
     * Cada linha original é subdividida quando excede o número máximo de caracteres
     * calculado a partir da largura do caractere "M" na fonte configurada.
     *
     * @param text           texto sanitizado com quebras de linha normalizadas para {@code \n}
     * @param maxWidthPoints largura máxima disponível na página em pontos tipográficos
     * @return lista de linhas prontas para impressão no PDF
     * @throws IOException se ocorrer falha ao calcular a largura do caractere na fonte
     */
    private static List<String> wrapToLines(String text, float maxWidthPoints) throws IOException {
        List<String> out = new ArrayList<>();

        float charWidth      = FONT.getStringWidth("M") / 1000f * FONT_SIZE;
        int   maxCharsPerLine = Math.max(20, (int) (maxWidthPoints / charWidth));

        String[] rawLines = text.split("\\n");
        for (String raw : rawLines) {
            if (raw.length() <= maxCharsPerLine) {
                out.add(raw);
            } else {
                // Quebra a linha em segmentos do tamanho máximo permitido
                int i = 0;
                while (i < raw.length()) {
                    int end = Math.min(raw.length(), i + maxCharsPerLine);
                    out.add(raw.substring(i, end));
                    i = end;
                }
            }
        }

        return out;
    }

    /**
     * Remove a extensão do nome de arquivo.
     *
     * @param fileName nome do arquivo com extensão
     * @return nome sem a extensão, ou o nome original se não houver ponto
     */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot >= 0) ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Persiste os bytes do PDF no arquivo de destino usando escrita atômica.
     * Escreve em arquivo temporário e renomeia atomicamente para o destino,
     * evitando estados parcialmente escritos em caso de falha.
     *
     * @param outFile caminho do arquivo de destino
     * @param bytes   conteúdo do PDF a ser persistido
     * @throws IOException se ocorrer falha na escrita ou renomeação
     */
    private static void writeAtomic(Path outFile, byte[] bytes) throws IOException {
        Files.createDirectories(outFile.getParent());

        Path tmp = outFile.resolveSibling(outFile.getFileName() + ".tmp");
        Files.write(tmp, bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        try {
            Files.move(tmp, outFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}