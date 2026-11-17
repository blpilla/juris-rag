package br.com.jurisrag.ingestion;

import br.com.jurisrag.config.JurisRagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TextChunker {
    private static final Pattern SECTION = Pattern.compile(
            "(?im)^\\s*((?:artigo|cláusula|clausula|seção|secao|capítulo|capitulo|anexo)\\s+[\\p{L}0-9.-]+[^\\r\\n]*)");
    private final int targetCharacters;
    private final int overlapCharacters;

    public TextChunker(JurisRagProperties properties) {
        this.targetCharacters = properties.chunking().targetCharacters();
        this.overlapCharacters = properties.chunking().overlapCharacters();
    }

    public List<TextChunk> split(List<PageText> pages) {
        List<TextChunk> output = new ArrayList<>();
        for (PageText page : pages) {
            String text = page.text().strip();
            if (text.isBlank()) {
                continue;
            }
            List<SectionSpan> sections = sections(text);
            int position = 0;
            for (SectionSpan section : sections) {
                String content = section.text().strip();
                if (content.isBlank()) {
                    continue;
                }
                for (String part : splitLong(content)) {
                    if (!part.isBlank()) {
                        output.add(new TextChunk(page.pageNumber(), position++, section.title(), part));
                    }
                }
            }
        }
        return output;
    }

    private List<SectionSpan> sections(String text) {
        Matcher matcher = SECTION.matcher(text);
        List<SectionSpan> spans = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            titles.add(matcher.group(1).strip());
        }
        if (starts.isEmpty()) {
            return List.of(new SectionSpan(null, text));
        }
        if (starts.getFirst() > 0) {
            spans.add(new SectionSpan(null, text.substring(0, starts.getFirst())));
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            spans.add(new SectionSpan(titles.get(i), text.substring(starts.get(i), end)));
        }
        return spans;
    }

    private List<String> splitLong(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + targetCharacters, text.length());
            if (end < text.length()) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > start + targetCharacters / 2) {
                    end = boundary;
                }
            }
            parts.add(text.substring(start, end).strip());
            if (end == text.length()) {
                break;
            }
            int next = Math.max(start + 1, end - overlapCharacters);
            start = next;
        }
        return parts;
    }

    private record SectionSpan(String title, String text) { }
}
