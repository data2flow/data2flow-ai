package net.java21.data2flow.ai.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AIA-08.05 도구 정의 버전 계약(TC-AIA-086). 도구 정의(이름·설명·범위·입력 스키마) 해시를 스냅숏과 비교한다.
 * 정의가 바뀌었는데 버전이 그대로이거나, 변경 내역 문서(docs/mcp-tools-changelog.md)에 "도구 | 버전" 줄이 없으면 실패한다.
 * 의도한 변경이면 버전을 올리고 문서에 줄을 더한 뒤 실패 메시지의 새 스냅숏으로 src/test/resources/mcp-tools.snapshot.json을 바꾼다.
 */
class McpToolVersionContractTest {

    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    @Test
    @DisplayName("[AIA-08.05][AT-AIA-08.1][TC-AIA-086] 도구 정의가 바뀌면 버전과 변경 내역이 함께 바뀌어야 한다")
    void versionedDefinitions() throws Exception {
        Map<String, Map<String, String>> current = new TreeMap<>();
        for (McpToolDefinition d : new McpTools(null, 1000).definitions()) {
            String canonical = JSON.writeValueAsString(Map.of("name", d.name(), "description", d.description(), "scope", d.scope().code(),
                    "inputSchema", d.inputSchema()));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            current.put(d.name(), Map.of("version", d.version(), "hash", hash));
        }
        String newSnapshot = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(current);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/mcp-tools.snapshot.new.json"), newSnapshot);   // 의도한 변경이면 이 파일로 스냅숏을 바꾼다
        JsonNode snapshot = JSON.readTree(Files.readString(Path.of("src/test/resources/mcp-tools.snapshot.json")));
        String changelog = Files.readString(Path.of("docs/mcp-tools-changelog.md"));
        for (Map.Entry<String, Map<String, String>> e : current.entrySet()) {
            JsonNode old = snapshot.path(e.getKey());
            boolean changed = !old.path("hash").asString("").equals(e.getValue().get("hash"));
            if (changed && old.path("version").asString("").equals(e.getValue().get("version"))) {
                throw new AssertionError(e.getKey() + " 정의가 바뀌었는데 버전이 그대로입니다. 버전을 올리고 변경 내역을 적으세요. 새 스냅숏:\n" + newSnapshot);
            }
            assertThat(changelog).as("변경 내역 문서에 %s %s 항목", e.getKey(), e.getValue().get("version"))
                    .contains("| " + e.getKey() + " | " + e.getValue().get("version") + " |");
            assertThat(changed).as("스냅숏 갱신 필요(버전·변경 내역은 맞음). 새 스냅숏:\n" + newSnapshot).isFalse();
        }
        assertThat(snapshot.size()).as("지운 도구는 스냅숏에서도 지운다").isEqualTo(current.size());
    }
}
