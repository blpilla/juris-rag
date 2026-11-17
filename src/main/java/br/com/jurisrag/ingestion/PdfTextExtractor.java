package br.com.jurisrag.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfTextExtractor {
    public List<PageText> extract(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<PageText> pages = new ArrayList<>();
            boolean hasText = false;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).strip();
                if (!text.isBlank()) {
                    hasText = true;
                }
                pages.add(new PageText(page, text));
            }
            if (!hasText) {
                throw new PdfWithoutTextLayerException();
            }
            return pages;
        } catch (IOException error) {
            throw new InvalidPdfContentException(error);
        }
    }
}
