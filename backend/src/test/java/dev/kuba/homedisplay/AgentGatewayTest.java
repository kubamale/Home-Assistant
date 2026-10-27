package dev.kuba.homedisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import dev.kuba.homedisplay.security.CredentialKind;
import dev.kuba.homedisplay.security.CredentialService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives the real WebSocket endpoint with a scripted fake agent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "app.devices.mode=agent", "app.devices.command-timeout=1s" })
@ActiveProfiles("test")
class AgentGatewayTest {

    @LocalServerPort
    int port;
    @Autowired
    CredentialService credentials;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final List<FakeAgent> opened = new ArrayList<>();
    private String agentToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("delete from credential").update();
        jdbc.sql("delete from quick_access").update();
        jdbc.sql("delete from scene_action").update();
        jdbc.sql("delete from scene").update();
        jdbc.sql("delete from device").update();
        agentToken = credentials.issue(CredentialKind.AGENT, "Test Pi").token();
        // sign in as the user so the HTTP client holds a session cookie
        send("POST", "/api/auth/login", "{\"password\":\"testpass\"}");
    }

    /** The gateway is shared by all tests in this context, so make sure every agent is gone afterwards. */
    @AfterEach
    void disconnectAgents() throws Exception {
        for (FakeAgent agent : opened) {
            if (!agent.socket.isOutputClosed()) {
                agent.socket.sendClose(WebSocket.NORMAL_CLOSURE, "test over").join();
            }
        }
        opened.clear();
        await(() -> {
            try {
                return !devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** A scripted agent: records what the server sends and lets the test reply. */
    private final class FakeAgent implements WebSocket.Listener {
        final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        WebSocket socket;
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                received.add(json.readTree(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        void say(String message) {
            socket.sendText(message, true).join();
        }

        JsonNode next() throws InterruptedException {
            JsonNode node = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(node, "no message from the server within 3 s");
            return node;
        }
    }

    private FakeAgent connect(String token) {
        FakeAgent agent = new FakeAgent();
        var builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        agent.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/agent/ws"), agent).join();
        opened.add(agent);
        return agent;
    }

    private void hello(FakeAgent agent) {
        agent.say("""
                {"v":1,"type":"hello","agentId":"pi-1","version":"0.1",
                 "devices":[{"deviceId":"bulb-1","name":"Light bar","type":"yeelight"},
                            {"deviceId":"bulb-2","name":"Desk lamp","type":"yeelight"}],
                 "states":[{"deviceId":"bulb-1","power":true,"reachable":true},
                           {"deviceId":"bulb-2","power":false,"reachable":false}]}""");
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode devices() throws Exception {
        HttpResponse<String> response = send("GET", "/api/devices", null);
        assertEquals(200, response.statusCode());
        return json.readTree(response.body());
    }

    private void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met in time");
    }

    @Test
    void rejectsConnectionsWithoutAValidAgentToken() {
        assertThrows(CompletionException.class, () -> connect(null));
        assertThrows(CompletionException.class, () -> connect("not-a-real-token"));
        // a display token is not an agent token
        String displayToken = credentials.issue(CredentialKind.DISPLAY, "Door").token();
        assertThrows(CompletionException.class, () -> connect(displayToken));
    }

    @Test
    void devicesAppearAfterHelloAndCommandsRoundTrip() throws Exception {
        assertFalse(devices().path("agentOnline").asBoolean());

        FakeAgent agent = connect(agentToken);
        hello(agent);
        await(() -> {
            try {
                return devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });

        JsonNode list = devices();
        assertEquals(2, list.path("devices").size());
        assertEquals("Desk lamp", list.path("devices").get(0).path("name").asString());
        assertFalse(list.path("devices").get(0).path("reachable").asBoolean());
        assertTrue(list.path("devices").get(1).path("power").asBoolean());

        // turn the light bar off: the server sends a command, the agent answers, the HTTP call returns
        CompletableFuture<HttpResponse<String>> call = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/devices/bulb-1/power", "{\"on\":false}");
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        JsonNode command = agent.next();
        assertEquals("command", command.path("type").asString());
        assertEquals("bulb-1", command.path("deviceId").asString());
        assertEquals("setPower", command.path("action").asString());
        assertFalse(command.path("args").path("on").asBoolean());
        agent.say("{\"v\":1,\"type\":\"result\",\"id\":\"" + command.path("id").asString() + "\",\"ok\":true}");

        HttpResponse<String> response = call.get(3, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertFalse(json.readTree(response.body()).path("power").asBoolean());
        assertFalse(devices().path("devices").get(1).path("power").asBoolean());
    }

    @Test
    void stateReportsFromTheAgentUpdateTheDevices() throws Exception {
        FakeAgent agent = connect(agentToken);
        hello(agent);
        await(() -> {
            try {
                return devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        agent.say("{\"v\":1,\"type\":\"state\",\"deviceId\":\"bulb-2\",\"power\":true,\"reachable\":true}");
        await(() -> {
            try {
                return devices().path("devices").get(0).path("power").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(devices().path("devices").get(0).path("reachable").asBoolean());
    }

    @Test
    void failedCommandBecomesBadGateway() throws Exception {
        FakeAgent agent = connect(agentToken);
        hello(agent);
        await(() -> {
            try {
                return devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        CompletableFuture<HttpResponse<String>> call = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/devices/bulb-1/power", "{\"on\":true}");
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        JsonNode command = agent.next();
        agent.say("{\"v\":1,\"type\":\"result\",\"id\":\"" + command.path("id").asString()
                + "\",\"ok\":false,\"error\":\"bulb unreachable\"}");
        assertEquals(502, call.get(3, TimeUnit.SECONDS).statusCode());
    }

    @Test
    void anAgentThatNeverAnswersTimesOut() throws Exception {
        FakeAgent agent = connect(agentToken);
        hello(agent);
        await(() -> {
            try {
                return devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        long started = System.nanoTime();
        HttpResponse<String> response = send("POST", "/api/devices/bulb-1/power", "{\"on\":true}");
        assertEquals(502, response.statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000);
    }

    @Test
    void reportedDevicesAreStoredAndAMissingOneStaysListed() throws Exception {
        FakeAgent agent = connect(agentToken);
        hello(agent);
        await(() -> jdbc.sql("select count(*) from device").query(Integer.class).single() == 2);

        // The agent later lists only one bulb, which has been renamed on the bulb itself.
        agent.say("""
                {"v":1,"type":"devices",
                 "devices":[{"deviceId":"bulb-1","name":"Ceiling light","type":"yeelight"}],
                 "states":[{"deviceId":"bulb-1","power":true,"reachable":true}]}""");
        await(() -> "Ceiling light".equals(jdbc.sql("select reported_name from device where id = 'bulb-1'")
                .query(String.class).optional().orElse(null)));

        JsonNode list = devices().path("devices");
        assertEquals(2, list.size());
        JsonNode gone = list.get(0).path("id").asString().equals("bulb-2") ? list.get(0) : list.get(1);
        assertEquals("bulb-2", gone.path("id").asString());
        assertFalse(gone.path("present").asBoolean());
        assertFalse(gone.path("reachable").asBoolean());
        assertEquals("Desk lamp", gone.path("name").asString());
    }

    private void awaitOnline() throws Exception {
        await(() -> {
            try {
                return devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void anAgentWithoutChannelsGetsASinglePowerOnlyChannel() throws Exception {
        FakeAgent agent = connect(agentToken);
        hello(agent);
        awaitOnline();

        JsonNode bulb = devices().path("devices").get(1); // Light bar, which is on
        assertEquals(1, bulb.path("channels").size());
        assertEquals("main", bulb.path("channels").get(0).path("id").asString());
        assertFalse(bulb.path("channels").get(0).path("capabilities").path("brightness").asBoolean());
        assertTrue(bulb.path("channels").get(0).path("on").asBoolean());
    }

    @Test
    void channelCommandsGoToTheAgentAndTheStateItReadsBackIsShown() throws Exception {
        FakeAgent agent = connect(agentToken);
        agent.say("""
                {"v":1,"type":"hello","agentId":"pi-1","version":"0.2",
                 "devices":[{"deviceId":"bar-1","name":"Monitor bar","type":"yeelight","channels":[
                    {"id":"main","label":"Main","capabilities":{"brightness":true,"colorTemp":{"min":2700,"max":6500},"color":false}},
                    {"id":"background","label":"Background","capabilities":{"brightness":true,"colorTemp":{"min":1700,"max":6500},"color":true}}]}],
                 "states":[{"deviceId":"bar-1","power":true,"reachable":true,"channels":[
                    {"id":"main","on":true,"brightness":80,"colorTemp":4000,"colorMode":"temperature"},
                    {"id":"background","on":false,"brightness":30,"hue":200,"saturation":90,"colorMode":"color"}]}]}""");
        awaitOnline();

        JsonNode bar = devices().path("devices").get(0);
        assertEquals(2, bar.path("channels").size());
        assertTrue(bar.path("channels").get(1).path("capabilities").path("color").asBoolean());
        assertEquals(2700, bar.path("channels").get(0).path("capabilities").path("colorTemp").path("min").asInt());
        assertEquals(80, bar.path("channels").get(0).path("brightness").asInt());

        // a change the channel cannot do is refused by the backend; the agent never hears of it
        assertEquals(400, send("POST", "/api/devices/bar-1/channels/main", "{\"hue\":10,\"saturation\":10}").statusCode());
        assertTrue(agent.received.isEmpty());

        CompletableFuture<HttpResponse<String>> call = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/devices/bar-1/channels/background",
                        "{\"on\":true,\"brightness\":40,\"hue\":120,\"saturation\":70}");
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        JsonNode command = agent.next();
        assertEquals("set", command.path("action").asString());
        assertEquals("background", command.path("channel").asString());
        assertEquals("bar-1", command.path("deviceId").asString());
        assertTrue(command.path("args").path("on").asBoolean());
        assertEquals(40, command.path("args").path("brightness").asInt());
        assertEquals(120, command.path("args").path("hue").asInt());
        assertEquals(70, command.path("args").path("saturation").asInt());

        // the bulb rounds brightness to 41 when read back: the app must show what the bulb reports
        agent.say("""
                {"v":1,"type":"result","id":"%s","ok":true,"state":{"deviceId":"bar-1","power":true,"reachable":true,"channels":[
                    {"id":"main","on":true,"brightness":80,"colorTemp":4000,"colorMode":"temperature"},
                    {"id":"background","on":true,"brightness":41,"hue":120,"saturation":70,"colorMode":"color"}]}}"""
                .formatted(command.path("id").asString()));

        HttpResponse<String> response = call.get(3, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        JsonNode background = json.readTree(response.body()).path("channels").get(1);
        assertEquals(41, background.path("brightness").asInt());
        assertEquals("color", background.path("colorMode").asString());
        assertEquals(41, devices().path("devices").get(0).path("channels").get(1).path("brightness").asInt());
    }

    @Test
    void aSceneIsAppliedAsChannelCommandsAndFailsWhenTheAgentIsOffline() throws Exception {
        FakeAgent agent = connect(agentToken);
        agent.say("""
                {"v":1,"type":"hello","agentId":"pi-1","version":"0.2",
                 "devices":[{"deviceId":"bar-1","name":"Monitor bar","type":"yeelight","channels":[
                    {"id":"main","label":"Main","capabilities":{"brightness":true,"colorTemp":{"min":2700,"max":6500},"color":false}},
                    {"id":"background","label":"Background","capabilities":{"brightness":true,"colorTemp":{"min":1700,"max":6500},"color":true}}]}],
                 "states":[{"deviceId":"bar-1","power":true,"reachable":true,"channels":[
                    {"id":"main","on":true,"brightness":80,"colorTemp":4000,"colorMode":"temperature"},
                    {"id":"background","on":false,"brightness":30,"hue":200,"saturation":90,"colorMode":"color"}]}]}""");
        awaitOnline();

        HttpResponse<String> created = send("POST", "/api/scenes", """
                {"name":"Movie","actions":[
                  {"deviceId":"bar-1","channel":"main","settings":{"on":false}},
                  {"deviceId":"bar-1","channel":"background","settings":{"brightness":20,"hue":220,"saturation":100}}]}""");
        assertEquals(201, created.statusCode());
        long id = json.readTree(created.body()).path("id").asLong();

        CompletableFuture<HttpResponse<String>> call = CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", "/api/scenes/" + id + "/apply", null);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        // one device: its two actions are sent in order, each answered before the next
        JsonNode first = agent.next();
        assertEquals("main", first.path("channel").asString());
        assertFalse(first.path("args").path("on").asBoolean());
        agent.say("{\"v\":1,\"type\":\"result\",\"id\":\"" + first.path("id").asString() + "\",\"ok\":true}");
        JsonNode second = agent.next();
        assertEquals("background", second.path("channel").asString());
        assertTrue(second.path("args").path("on").asBoolean(), "brightness and colour imply on");
        assertEquals(20, second.path("args").path("brightness").asInt());
        assertEquals(220, second.path("args").path("hue").asInt());
        agent.say("{\"v\":1,\"type\":\"result\",\"id\":\"" + second.path("id").asString() + "\",\"ok\":true}");

        HttpResponse<String> response = call.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals(2, json.readTree(response.body()).path("applied").asInt());
        assertEquals(0, json.readTree(response.body()).path("failed").size());

        agent.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        await(() -> {
            try {
                return !devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        assertEquals(503, send("POST", "/api/scenes/" + id + "/apply", null).statusCode());
    }

    @Test
    void goingOfflineKeepsLastStateButRefusesCommands() throws Exception {
        FakeAgent agent = connect(agentToken);
        hello(agent);
        await(() -> {
            try {
                return devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        agent.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        await(() -> {
            try {
                return !devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        assertEquals(2, devices().path("devices").size()); // last known devices are kept
        assertEquals(503, send("POST", "/api/devices/bulb-1/power", "{\"on\":true}").statusCode());
    }

    @Test
    void unknownDeviceIsNotFound() throws Exception {
        FakeAgent agent = connect(agentToken);
        hello(agent);
        await(() -> {
            try {
                return devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        assertEquals(404, send("POST", "/api/devices/nope/power", "{\"on\":true}").statusCode());
    }

    @Test
    void aNewConnectionReplacesTheOldOne() throws Exception {
        FakeAgent first = connect(agentToken);
        hello(first);
        await(() -> {
            try {
                return devices().path("agentOnline").asBoolean();
            } catch (Exception e) {
                return false;
            }
        });
        FakeAgent second = connect(agentToken);
        hello(second);
        await(() -> first.socket.isInputClosed());
        // the replaced connection closing must not take the agent offline
        Thread.sleep(300);
        assertTrue(devices().path("agentOnline").asBoolean());
    }
}
