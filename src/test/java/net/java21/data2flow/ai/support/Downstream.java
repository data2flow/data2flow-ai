package net.java21.data2flow.ai.support;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * core-api·analytics·pipeline 흉내(MockWebServer 하나, 경로별 응답). 실제 s3·s4에는 붙지 않는다(CLAUDE.md §5).
 * 경로 접두사 → 응답 함수로 등록하고, 받은 요청을 기록한다.
 */
public final class Downstream {

    private static final MockWebServer SERVER = new MockWebServer();
    private static final Map<String, Function<RecordedRequest, MockResponse>> ROUTES = new ConcurrentHashMap<>();
    private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();

    static {
        SERVER.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                REQUESTS.add(request);
                String path = request.getPath() == null ? "" : request.getPath();
                String best = null;
                int bestLength = -1;
                for (String key : ROUTES.keySet()) {
                    int space = key.indexOf(' ');
                    String method = key.substring(0, space);
                    String prefix = key.substring(space + 1);
                    if (method.equals(request.getMethod()) && path.startsWith(prefix) && prefix.length() > bestLength) {
                        best = key;
                        bestLength = prefix.length();
                    }
                }
                if (best == null) {
                    return new MockResponse().setResponseCode(404).setBody("{\"header\":{\"isSuccessful\":false,\"resultCode\":\"RESOURCE_NOT_FOUND\"}}");
                }
                return ROUTES.get(best).apply(request);
            }
        });
        try {
            SERVER.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Downstream() {
    }

    public static String url() {
        return SERVER.url("/").toString().replaceAll("/$", "");
    }

    /** {@code "GET /core/devices"} 같은 키 */
    public static void on(String methodAndPathPrefix, Function<RecordedRequest, MockResponse> response) {
        ROUTES.put(methodAndPathPrefix, response);
    }

    public static void json(String methodAndPathPrefix, int status, String body) {
        on(methodAndPathPrefix, r -> new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body));
    }

    /** 공통 봉투로 감싼 성공 응답 */
    public static void ok(String methodAndPathPrefix, String responseJson) {
        json(methodAndPathPrefix, 200, "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"OK\",\"resultMessage\":\"OK\"},\"response\":" + responseJson + "}");
    }

    public static void reset() {
        ROUTES.clear();
        REQUESTS.clear();
    }

    public static List<RecordedRequest> requests() {
        return List.copyOf(REQUESTS);
    }

    public static List<RecordedRequest> requests(String pathPrefix) {
        return REQUESTS.stream().filter(r -> r.getPath() != null && r.getPath().startsWith(pathPrefix)).toList();
    }
}
