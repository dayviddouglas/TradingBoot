package com.github.dayviddouglas.TradingBot.tools.history;

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

public class HistoryJsonToPdfTool {

    // ====== CONFIG FIXA (edite aqui) ======
    private static final Path INPUT_JSON = Path.of("data", "history", "frxEURUSD_60.json");
    private static final Path OUTPUT_DIR = Path.of("data", "history", "pdf");
    private static final long MAX_PDF_BYTES = 14L * 1024L * 1024L; // 14 MB
    // ======================================

    private static final PDType1Font FONT = PDType1Font.COURIER;
    private static final float FONT_SIZE = 9f;

    private static final float MARGIN = 36f;
    private static final PDRectangle PAGE_SIZE = LETTER;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        String raw = Files.readString(INPUT_JSON, StandardCharsets.UTF_8);

        // >>> FIX: sanitiza o texto para remover CR e outros controles incompatíveis com PDFBox
        String json = sanitizeForPdf(raw);

        String baseName = stripExtension(INPUT_JSON.getFileName().toString());

        List<String> parts = splitToPdfSizedParts(json);

        for (int i = 0; i < parts.size(); i++) {
            String partText = parts.get(i);
            int partNo = i + 1;

            String outName = String.format("%s_part%03d.pdf", baseName, partNo);
            Path outFile = OUTPUT_DIR.resolve(outName);

            // Mudança aqui: passamos 'null' no segundo parâmetro para não imprimir o header
            byte[] pdfBytes = renderPdfBytes(partText, null);
            writeAtomic(outFile, pdfBytes);
        }

        System.out.println("Done. PDFs created at: " + OUTPUT_DIR.toAbsolutePath());
    }
    /**
     * Remove caracteres que quebram o showText():
     * - converte \r\n e \r para \n
     * - remove controles restantes (0x00-0x1F), exceto \n e \t
     */
    private static String sanitizeForPdf(String s) {
        if (s == null) return "";

        // normaliza quebras de linha
        s = s.replace("\r\n", "\n").replace("\r", "\n");

        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            // Mantém newline e tab (tab vamos converter depois em espaços por segurança)
            if (ch == '\n' || ch == '\t') {
                sb.append(ch);
                continue;
            }

            // Remove outros controles (0x00..0x1F)
            if (ch < 0x20) {
                continue;
            }

            sb.append(ch);
        }

        // troca tab por 4 espaços (evita surpresa na fonte)
        return sb.toString().replace("\t", "    ");
    }

    private static List<String> splitToPdfSizedParts(String fullText) throws IOException {
        List<String> parts = new ArrayList<>();

        int index = 0;
        int n = fullText.length();

        int chunkChars = 400_000;

        while (index < n) {
            int remaining = n - index;
            int tryLen = Math.min(chunkChars, remaining);

            while (true) {
                String candidate = fullText.substring(index, index + tryLen);

                byte[] pdf = renderPdfBytes(candidate, null);
                if (pdf.length <= MAX_PDF_BYTES) {
                    parts.add(candidate);
                    index += tryLen;

                    if (pdf.length < MAX_PDF_BYTES * 0.70 && chunkChars < 2_000_000) {
                        chunkChars = (int) Math.min(2_000_000, (int) (chunkChars * 1.2));
                    }
                    break;
                }

                tryLen = (int) (tryLen * 0.75);
                if (tryLen < 10_000) {
                    tryLen = 10_000;
                    String fallback = fullText.substring(index, Math.min(n, index + tryLen));
                    parts.add(fallback);
                    index += fallback.length();
                    chunkChars = 200_000;
                    break;
                }
            }
        }

        return parts;
    }

    private static byte[] renderPdfBytes(String text, String header) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            List<String> lines = wrapToLines(text, PAGE_SIZE.getWidth() - 2 * MARGIN);

            int lineIndex = 0;

            while (lineIndex < lines.size()) {
                PDPage page = new PDPage(PAGE_SIZE);
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(FONT, FONT_SIZE);

                    float leading = FONT_SIZE * 1.2f;
                    float startX = MARGIN;
                    float startY = PAGE_SIZE.getHeight() - MARGIN;

                    cs.newLineAtOffset(startX, startY);

                    if (header != null && !header.isBlank()) {
                        cs.showText(header);
                        cs.newLineAtOffset(0, -leading);
                        cs.showText("------------------------------------------------------------");
                        cs.newLineAtOffset(0, -leading);
                    }

                    float usableHeight = PAGE_SIZE.getHeight() - 2 * MARGIN;
                    int maxLines = (int) Math.floor(usableHeight / leading);

                    int headerLines = (header == null || header.isBlank()) ? 0 : 2;
                    int linesThisPage = Math.max(1, maxLines - headerLines);

                    int end = Math.min(lines.size(), lineIndex + linesThisPage);
                    for (int i = lineIndex; i < end; i++) {
                        // Linha já sanitizada (sem \r e sem controles)
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

    private static List<String> wrapToLines(String text, float maxWidthPoints) throws IOException {
        List<String> out = new ArrayList<>();

        float charWidth = FONT.getStringWidth("M") / 1000f * FONT_SIZE;
        int maxCharsPerLine = Math.max(20, (int) (maxWidthPoints / charWidth));

        // O texto já está com \n normalizado
        String[] rawLines = text.split("\\n");
        for (String raw : rawLines) {
            if (raw.length() <= maxCharsPerLine) {
                out.add(raw);
            } else {
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

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot >= 0) ? fileName.substring(0, dot) : fileName;
    }

    private static void writeAtomic(Path outFile, byte[] bytes) throws IOException {
        Files.createDirectories(outFile.getParent());

        Path tmp = outFile.resolveSibling(outFile.getFileName() + ".tmp");
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        try {
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}