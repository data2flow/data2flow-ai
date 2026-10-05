package net.java21.data2flow.ai.help;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 제품 문서 색인(AIA-09.01). 배포본에 들어 있는 문서({@code classpath:help/guide/*.md} 사용자 가이드·템플릿 설명서,
 * {@code help/error-codes.tsv} 오류 코드 설명)를 절 단위로 잘라 help_chunks에 넣는다. 내용이 같으면 쓰지 않는다(여러 파드가 동시에 시작해도 안전).
 */
public class HelpIndexer {

    private static final Logger log = LoggerFactory.getLogger(HelpIndexer.class);
    private final HelpChunkRepository repository;

    public HelpIndexer(HelpChunkRepository repository) {
        this.repository = repository;
    }

    /** 문서 조각 */
    public record Chunk(String source, String docId, int chunkNo, String title, String text, String url) {
    }

    public int index() {
        List<Chunk> chunks = load();
        for (Chunk c : chunks) {
            repository.upsert(c.source(), c.docId(), c.chunkNo(), c.title() + "\n" + c.text(), HashingEmbedder.embed(c.title() + " " + c.text()),
                    c.url());
        }
        log.info("도움말 색인 {}개", chunks.size());
        return chunks.size();
    }

    static List<Chunk> load() {
        List<Chunk> out = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource r : resolver.getResources("classpath:help/guide/*.md")) {
                out.addAll(guide(r.getFilename().replace(".md", ""), r.getContentAsString(StandardCharsets.UTF_8)));
            }
            Resource codes = resolver.getResource("classpath:help/error-codes.tsv");
            if (codes.exists()) {
                for (String line : codes.getContentAsString(StandardCharsets.UTF_8).split("\n")) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] f = line.split("\t");
                    if (f.length < 5) {
                        continue;
                    }
                    out.add(new Chunk("ERROR_CODE", "error:" + f[0], 0, "오류 코드 " + f[0] + " (HTTP " + f[1] + ", " + f[2] + ")",
                            "상황: " + f[3] + "\n화면 문구: " + f[4], "/help/errors#" + f[0]));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("도움말 문서를 읽지 못했습니다", e);
        }
        return out;
    }

    static List<Chunk> guide(String name, String content) {
        String title = name;
        String source = "USER_GUIDE";
        String url = "/help/guide/" + name;
        String body = content;
        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            for (String line : content.substring(3, end).split("\n")) {
                String[] kv = line.split(":", 2);
                if (kv.length == 2) {
                    switch (kv[0].trim()) {
                        case "title" -> title = kv[1].trim();
                        case "source" -> source = kv[1].trim();
                        case "url" -> url = kv[1].trim();
                        default -> {
                        }
                    }
                }
            }
            body = content.substring(end + 4);
        }
        List<Chunk> out = new ArrayList<>();
        int no = 0;
        for (String section : body.split("(?m)^## ")) {
            if (section.isBlank()) {
                continue;
            }
            String heading = section.lines().findFirst().orElse("").trim();
            String text = section.substring(section.indexOf('\n') + 1).trim();
            out.add(new Chunk(source, "guide:" + name, no, title + " — " + heading, text, url + "#s" + no));
            no++;
        }
        return out;
    }
}
