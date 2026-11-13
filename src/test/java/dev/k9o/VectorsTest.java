package dev.k9o;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** vectors().nearest and upsert: exact method, path and body, validation that sends nothing, and decoded answers and errors. */
class VectorsTest {

    static final ObjectMapper M = new ObjectMapper();

    record Call(String method, String target, String body) {}

    HttpServer server;
    String baseUrl;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile Function<Call, String[]> handler = c -> new String[] {"200", "{\"data\":null,\"meta\":{}}"};

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            Call call = new Call(ex.getRequestMethod(), ex.getRequestURI().toString(),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(call);
            String[] r = handler.apply(call);
            byte[] b = r[1].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(Integer.parseInt(r[0]), b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build());
    }

    void expectLast(String method, String target, String json) throws IOException {
        Call c = calls.get(calls.size() - 1);
        assertEquals(method, c.method());
        assertEquals(target, c.target());
        assertEquals(M.readTree(json), M.readTree(c.body()), c.body());
    }

    static final String NEAREST = "{\"data\":{\"results\":["
        + "{\"id\":\"p1\",\"score\":1,\"_source\":{\"handle\":\"probe\",\"chunkIndex\":0},\"contentHash\":null,\"text\":\"x axis\",\"metadata\":{\"lang\":\"en\"},\"model\":null,\"chunk\":0},"
        + "{\"id\":\"p2\",\"score\":0.8,\"_source\":{\"handle\":\"probe\",\"chunkIndex\":1},\"contentHash\":\"h2\",\"text\":null,\"metadata\":{\"model\":\"m1\"},\"model\":\"m1\",\"chunk\":1}"
        + "],\"collection\":\"_vec_example\"},\"meta\":{}}";

    @Test
    void nearestSendsTheBodyAndDecodesResults() throws IOException {
        handler = c -> new String[] {"200", NEAREST};
        var opts = new Vectors.NearestOptions(2, 0.5, "dot", Map.of("metadata.lang", "en"));
        var r = client().vectors().nearest("_vec_example", List.of(1.0, 0.0, 0.0), opts);
        expectLast("POST", "/@portal/vectors/_vec_example/search",
            "{\"queryVector\":[1.0,0.0,0.0],\"topK\":2,\"minScore\":0.5,\"metric\":\"dot\",\"filters\":{\"metadata.lang\":\"en\"}}");
        assertEquals("_vec_example", r.data().collection());
        var hits = r.data().results();
        assertEquals(2, hits.size());
        assertEquals("p1", hits.get(0).id());
        assertEquals(1.0, hits.get(0).score());
        assertEquals("probe", hits.get(0).source().handle());
        assertNull(hits.get(0).contentHash());
        assertNull(hits.get(0).model());
        assertEquals(0, hits.get(0).chunk());
        assertEquals("x axis", hits.get(0).text());
        assertEquals(0.8, hits.get(1).score());
        assertNull(hits.get(1).text());
        assertEquals("h2", hits.get(1).contentHash());
        assertEquals("m1", hits.get(1).model());
    }

    @Test
    void nearestWithoutOptionsSendsOnlyTheVector() throws IOException {
        handler = c -> new String[] {"200", NEAREST};
        client().vectors().nearest("_vec_example", List.of(1, 0, 0));
        expectLast("POST", "/@portal/vectors/_vec_example/search", "{\"queryVector\":[1,0,0]}");
        client().vectors().nearest("_vec_example", List.of(1.0), Vectors.NearestOptions.topK(2));
        expectLast("POST", "/@portal/vectors/_vec_example/search", "{\"queryVector\":[1.0],\"topK\":2}");
    }

    @Test
    void upsertSendsRowsWithTheSourceKey() throws IOException {
        handler = c -> new String[] {"200", "{\"data\":{\"upserted\":3,\"collection\":\"_vec_example\"},\"meta\":{}}"};
        var src = new Vectors.Source("probe", 0);
        var rows = List.of(
            new Vectors.Row("java-1", List.of(0.0, 0.0, 1.0), src, "h", "z axis", Map.of("lang", "de"), List.of("eu_YmGE3Z6nkv5i")),
            new Vectors.Row(null, List.of(1.0, 0.0, 0.0), src));
        var r = client().vectors().upsert("_vec_example", rows);
        expectLast("POST", "/@portal/vectors/_vec_example/upsert",
            "{\"rows\":[{\"id\":\"java-1\",\"vector\":[0.0,0.0,1.0],\"_source\":{\"handle\":\"probe\",\"chunkIndex\":0},"
                + "\"contentHash\":\"h\",\"text\":\"z axis\",\"metadata\":{\"lang\":\"de\"},\"subjectIds\":[\"eu_YmGE3Z6nkv5i\"]},"
                + "{\"vector\":[1.0,0.0,0.0],\"_source\":{\"handle\":\"probe\",\"chunkIndex\":0}}]}");
        assertEquals(3, r.data().upserted());
        assertEquals("_vec_example", r.data().collection());
    }

    @Test
    void invalidInputSendsNothing() {
        var v = client().vectors();
        var src = new Vectors.Source("h", 0);
        List<Runnable> bad = new ArrayList<>();
        bad.add(() -> v.nearest("notes", List.of(1.0)));
        bad.add(() -> v.nearest(null, List.of(1.0)));
        bad.add(() -> v.nearest("_n", null));
        bad.add(() -> v.nearest("_n", List.of()));
        bad.add(() -> v.nearest("_n", List.of(1.0, Double.NaN)));
        bad.add(() -> v.nearest("_n", List.of(Double.POSITIVE_INFINITY)));
        bad.add(() -> v.nearest("_n", java.util.Collections.nCopies(16001, 1.0)));
        bad.add(() -> v.nearest("_n", List.of(1.0), Vectors.NearestOptions.topK(0)));
        bad.add(() -> v.nearest("_n", List.of(1.0), Vectors.NearestOptions.topK(1001)));
        bad.add(() -> v.nearest("_n", List.of(1.0), new Vectors.NearestOptions(null, null, "manhattan", null)));
        bad.add(() -> v.nearest("_n", List.of(1.0), new Vectors.NearestOptions(null, Double.NaN, null, null)));
        bad.add(() -> v.upsert("notes", List.of(new Vectors.Row("a", List.of(1.0), src))));
        bad.add(() -> v.upsert("_n", null));
        bad.add(() -> v.upsert("_n", List.of()));
        bad.add(() -> v.upsert("_n", java.util.Collections.nCopies(1001, new Vectors.Row("a", List.of(1.0), src))));
        bad.add(() -> v.upsert("_n", List.of(new Vectors.Row("a", List.of(), src))));
        bad.add(() -> v.upsert("_n", List.of(new Vectors.Row("a", List.of(Double.NaN), src))));
        for (Runnable r : bad) {
            var e = assertThrows(KError.class, r::run);
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        }
        assertEquals(0, calls.size());
        // exact limits are accepted
        handler = c -> new String[] {"200", NEAREST};
        v.nearest("_n", java.util.Collections.nCopies(16000, 0.5), Vectors.NearestOptions.topK(1000));
        assertEquals(1, calls.size());
    }

    @Test
    void missingCollectionDecodesIntoKError() {
        handler = c -> new String[] {"404", "{\"code\":\"K9O_COLLECTION_NOT_FOUND\",\"message\":\"derived vector collection '_vec_example' does not exist\",\"context\":{},\"recommendation\":null}"};
        var e = assertThrows(KError.class, () -> client().vectors().nearest("_vec_example", List.of(1.0, 0.0, 0.0)));
        assertEquals(404, e.getStatus());
        assertEquals("K9O_COLLECTION_NOT_FOUND", e.getCode());
        assertTrue(e.getMessage().contains("_vec_example"));
    }

    @Test
    void wrongDimensionDecodesIntoKError() {
        handler = c -> new String[] {"400", "{\"code\":\"K9O_INVALID_VECTOR_DIMENSION\",\"message\":\"query vector dimension does not match collection '_vec_example'\","
            + "\"context\":{\"collection\":\"_vec_example\",\"queryDim\":2},\"recommendation\":null}"};
        var e = assertThrows(KError.class, () -> client().vectors().nearest("_vec_example", List.of(1.0, 0.0)));
        assertEquals(400, e.getStatus());
        assertEquals("K9O_INVALID_VECTOR_DIMENSION", e.getCode());
        assertEquals("_vec_example", e.getContext().get("collection"));
        assertEquals(2, ((Number) e.getContext().get("queryDim")).intValue());
    }

    @Test
    void requiresProjectSlug() {
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT",
            assertThrows(KError.class, () -> c.vectors().nearest("_n", List.of(1.0))).getCode());
        assertEquals(0, calls.size());
    }
}
