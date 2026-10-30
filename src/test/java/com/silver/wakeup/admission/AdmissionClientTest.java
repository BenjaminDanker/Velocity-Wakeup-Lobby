package com.silver.wakeup.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdmissionClientTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static AdmissionClient client() {
        return new AdmissionClient("127.0.0.1");
    }

    @Test
    void aFreshClientIsClosedAndAcceptsEverything() {
        AdmissionClient client = client();
        assertFalse(client.isOpen());
        assertTrue(client.isAccepting("anything"));
    }

    @Test
    void aFreshClientStaysClosedWhenTheServiceFails() {
        AdmissionClient client = client();
        client.failed(1_000);
        assertFalse(client.isOpen());
        client.failed(System.currentTimeMillis());
        assertFalse(client.isOpen());
    }

    @Test
    void openFollowsTheAdmissionField() {
        AdmissionClient client = client();
        client.apply(json("{\"admission\":\"OPEN\"}"), 1_000);
        assertTrue(client.isOpen());
        client.apply(json("{\"admission\":\"CLOSED\"}"), 2_000);
        assertFalse(client.isOpen());
        client.apply(json("{\"admission\":\"OPEN\"}"), 3_000);
        assertTrue(client.isOpen());
        client.apply(json("{}"), 4_000);
        assertFalse(client.isOpen());
        client.apply(json("{\"admission\":\"open\"}"), 5_000);
        assertFalse(client.isOpen(), "only the exact word OPEN opens the gate");
    }

    @Test
    void acceptingComesFromTheServersMapAndDefaultsToTrue() {
        AdmissionClient client = client();
        client.apply(json("""
            {"admission":"OPEN","servers":{
              "laggy":{"accepting":false},
              "fine":{"accepting":true},
              "missing":{"tps":20},
              "nullish":{"accepting":null},
              "notAnObject":5
            }}"""), 1_000);
        assertFalse(client.isAccepting("laggy"));
        assertTrue(client.isAccepting("fine"));
        assertTrue(client.isAccepting("missing"));
        assertTrue(client.isAccepting("nullish"));
        assertTrue(client.isAccepting("notAnObject"));
        assertTrue(client.isAccepting("unknown"));
    }

    @Test
    void aNewReplyReplacesTheOldAcceptingMap() {
        AdmissionClient client = client();
        client.apply(json("{\"admission\":\"OPEN\",\"servers\":{\"a\":{\"accepting\":false}}}"), 1_000);
        assertFalse(client.isAccepting("a"));
        client.apply(json("{\"admission\":\"OPEN\"}"), 2_000);
        assertTrue(client.isAccepting("a"));
        client.apply(json("{\"admission\":\"OPEN\",\"servers\":\"nope\"}"), 3_000);
        assertTrue(client.isAccepting("a"));
    }

    @Test
    void anOutageKeepsTheLastStateForTheGraceAndThenCloses() {
        AdmissionClient client = client();
        long t0 = 1_000_000;
        client.apply(json("{\"admission\":\"OPEN\",\"servers\":{\"a\":{\"accepting\":false}}}"), t0);

        client.failed(t0 + 1);
        assertTrue(client.isOpen());
        assertFalse(client.isAccepting("a"));

        client.failed(t0 + AdmissionClient.GRACE_MS);
        assertTrue(client.isOpen(), "exactly at the grace limit the state is still kept");
        assertFalse(client.isAccepting("a"));

        client.failed(t0 + AdmissionClient.GRACE_MS + 1);
        assertFalse(client.isOpen());
        assertTrue(client.isAccepting("a"), "accepting is cleared, so unknown servers count as accepting");
    }

    @Test
    void theGraceRunsFromTheLastGoodReplyNotFromTheFirstFailure() {
        AdmissionClient client = client();
        long t0 = 5_000_000;
        client.apply(json("{\"admission\":\"OPEN\"}"), t0);
        client.failed(t0 + 30_000);
        client.failed(t0 + 59_000);
        assertTrue(client.isOpen());
        client.failed(t0 + 61_000);
        assertFalse(client.isOpen());
    }

    @Test
    void aGoodReplyAfterAnOutageReopensAndRestartsTheGrace() {
        AdmissionClient client = client();
        long t0 = 9_000_000;
        client.apply(json("{\"admission\":\"OPEN\"}"), t0);
        client.failed(t0 + 120_000);
        assertFalse(client.isOpen());
        client.apply(json("{\"admission\":\"OPEN\"}"), t0 + 130_000);
        assertTrue(client.isOpen());
        client.failed(t0 + 150_000);
        assertTrue(client.isOpen());
    }

    @Test
    void aClosedReplyIsRememberedAsAGoodReplyForTheGrace() {
        AdmissionClient client = client();
        client.apply(json("{\"admission\":\"CLOSED\"}"), 1_000_000);
        client.failed(1_010_000);
        assertFalse(client.isOpen());
    }

    // The port is a constant (25580, used by the real service here), so the test points the client at a local
    // server by replacing its private base URI by reflection; product code is untouched.
    private static AdmissionClient clientFor(HttpServer server) throws Exception {
        AdmissionClient client = client();
        Field base = AdmissionClient.class.getDeclaredField("base");
        base.setAccessible(true);
        base.set(client, URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        return client;
    }

    private static HttpServer serve(int status, String body, AtomicReference<String> seenBody, AtomicReference<String> seenRequest)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (seenRequest != null) {
                seenRequest.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            }
            String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (seenBody != null) {
                seenBody.set(received);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void reserveStartSendsTheRequestAndReadsAGrant() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = serve(200, "{\"granted\":true}", body, request);
        try {
            AdmissionClient.Permit permit = clientFor(server).reserveStart("magic");
            assertNotNull(permit);
            assertTrue(permit.granted());
            assertEquals("", permit.reason());
            assertEquals("POST /reserve", request.get());
            JsonObject sent = json(body.get());
            assertEquals("mc_start", sent.get("kind").getAsString());
            assertEquals("magic", sent.get("name").getAsString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reserveStartReadsARefusalWithItsReason() throws Exception {
        HttpServer server = serve(200, "{\"granted\":false,\"reason\":\"not enough RAM\"}", null, null);
        try {
            AdmissionClient.Permit permit = clientFor(server).reserveStart("tech");
            assertNotNull(permit);
            assertFalse(permit.granted());
            assertEquals("not enough RAM", permit.reason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reserveStartTreatsMissingFieldsAsRefusedWithNoReason() throws Exception {
        HttpServer server = serve(200, "{\"reason\":null}", null, null);
        try {
            AdmissionClient.Permit permit = clientFor(server).reserveStart("tech");
            assertNotNull(permit);
            assertFalse(permit.granted());
            assertEquals("", permit.reason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reserveStartReturnsNullWhenTheServiceDidNotReallyAnswer() throws Exception {
        HttpServer error = serve(500, "{\"granted\":false}", null, null);
        try {
            assertNull(clientFor(error).reserveStart("magic"), "non-200 means no answer");
        } finally {
            error.stop(0);
        }
        HttpServer garbage = serve(200, "not json", null, null);
        try {
            assertNull(clientFor(garbage).reserveStart("magic"), "unparseable body means no answer");
        } finally {
            garbage.stop(0);
        }
        HttpServer array = serve(200, "[1,2]", null, null);
        try {
            assertNull(clientFor(array).reserveStart("magic"), "a non-object body means no answer");
        } finally {
            array.stop(0);
        }
        HttpServer gone = serve(200, "{}", null, null);
        AdmissionClient client = clientFor(gone);
        gone.stop(0);
        assertNull(client.reserveStart("magic"), "connection refused means no answer");
    }

    @Test
    void refreshReadsTheStatusReplyIntoTheCachedState() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = serve(200, "{\"admission\":\"OPEN\",\"servers\":{\"a\":{\"accepting\":false}}}", null, request);
        try {
            AdmissionClient client = clientFor(server);
            client.refresh();
            assertEquals("GET /status", request.get());
            assertTrue(client.isOpen());
            assertFalse(client.isAccepting("a"));
        } finally {
            server.stop(0);
        }
    }
}
