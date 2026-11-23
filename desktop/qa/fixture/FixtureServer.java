import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FixtureServer {
    private static final String HOST = "127.0.0.1";
    private static final int PORT_A = 18443;
    private static final int PORT_B = 18444;
    private static final Pattern SESSION = Pattern.compile("\"session_id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
    private static final Pattern USERNAME = Pattern.compile("[?&]username=([^&]*)");
    private static final Pattern LONG_STREAM = Pattern.compile("fixture:long-stream:([A-Za-z0-9_-]{1,32})(?![A-Za-z0-9_-])");
    private static final String PNG_ID_A = "reply-image-a";
    private static final String PNG_ID_B = "reply-image-b";

    private final Properties config = new Properties();
    private final Path configFile;
    private final Path controlsFile;
    private final HttpsServer serverA;
    private final HttpsServer serverB;
    private final byte[] replyPngA;
    private final byte[] replyJpegB;

    private FixtureServer(Path configPath) throws Exception {
        this.configFile = configPath;
        try (var input = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            config.load(input);
        }
        controlsFile = Path.of(config.getProperty("controls.file"));
        replyPngA = imageBytes("png", new Color(38, 113, 136), "Mock A reply");
        replyJpegB = imageBytes("jpg", new Color(154, 84, 46), "Mock B reply");
        Path media = Path.of(config.getProperty("media.dir"));
        Files.createDirectories(media);
        Files.write(media.resolve("upload-a.png"), imageBytes("png", new Color(66, 135, 90), "Upload sample A"));
        Files.write(media.resolve("upload-b.jpg"), imageBytes("jpg", new Color(120, 98, 168), "Upload sample B"));

        char[] password = config.getProperty("tls.password").toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(config.getProperty("tls.serverStore")))) {
            keyStore.load(input, password);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);

        serverA = newServer(PORT_A, tls, "A");
        serverB = newServer(PORT_B, tls, "B");
    }

    private HttpsServer newServer(int port, SSLContext tls, String profile) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(HOST, port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "fixture-" + profile);
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> handle(profile, exchange));
        return server;
    }

    private void start() {
        serverA.start();
        try {
            serverB.start();
        } catch (RuntimeException error) {
            serverA.stop(0);
            throw error;
        }
        System.out.println("READY https://" + HOST + ":" + PORT_A + " https://" + HOST + ":" + PORT_B);
        System.out.flush();
    }

    private void handle(String profile, HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/health")) {
            try {
                send(exchange, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
            return;
        }

        String session = "-";
        String alias = identify(profile, exchange.getRequestHeaders().getFirst("Authorization"));
        int status;
        if (exchange.getRequestMethod().equals("POST") && (path.equals("/api/v1/file") || path.equals("/api/v1/chat"))) {
            System.out.println("received profile=" + profile + " operation=" + (path.endsWith("/file") ? "upload" : "chat"));
        }
        try {
            if (alias.equals("invalid") || (alias.equals("old_key") && rejectOldKey())) {
                status = 401;
                send(exchange, status, "application/json", "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/api/v1/chat/sessions") && exchange.getRequestMethod().equals("GET")) {
                if (alias.equals("unknown")) {
                    status = 401;
                    send(exchange, status, "application/json", "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8));
                } else {
                    status = serveSessions(profile, exchange);
                }
            } else if (path.equals("/api/v1/chat") && exchange.getRequestMethod().equals("POST")) {
                if (alias.equals("unknown")) {
                    status = 401;
                    send(exchange, status, "application/json", "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    session = find(SESSION, new String(body, StandardCharsets.UTF_8), 1, "-");
                    status = serveChat(profile, exchange, body, session);
                }
            } else if (path.equals("/api/v1/file")) {
                if (alias.equals("unknown")) {
                    status = 401;
                    send(exchange, status, "application/json", "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8));
                } else if (exchange.getRequestMethod().equals("POST")) {
                    status = serveUpload(profile, exchange);
                } else if (exchange.getRequestMethod().equals("GET")) {
                    status = serveDownload(profile, exchange);
                } else {
                    status = 405;
                    send(exchange, status, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                }
            } else {
                status = 404;
                send(exchange, status, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            status = 500;
            try {
                send(exchange, status, "application/json", "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
        logRequest(profile, exchange.getRequestMethod(), path, alias, session, exchange.getResponseCode());
    }

    private int serveSessions(String profile, HttpExchange exchange) throws IOException {
        String username = find(USERNAME, exchange.getRequestURI().toString(), 1, "");
        username = URLDecoder.decode(username, StandardCharsets.UTF_8);
        String expectedUsername = config.getProperty("username");
        String id = config.getProperty("session." + profile.toLowerCase());
        String row = username.equals(expectedUsername)
                ? "{\"session_id\":\"" + id + "\",\"creator\":\"" + expectedUsername + "\",\"platform_id\":\"webchat\"}"
                : "";
        String sessions = row.isEmpty() ? "[]" : "[" + row + "]";
        String page = "{\"status\":\"ok\",\"data\":{\"sessions\":" + sessions +
                ",\"page\":1,\"page_size\":100,\"total\":" + (row.isEmpty() ? "0" : "1") + "}}";
        send(exchange, 200, "application/json; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
        return 200;
    }

    private int serveChat(String profile, HttpExchange exchange, byte[] body, String session) throws IOException {
        String request = new String(body, StandardCharsets.UTF_8);
        if (!verifyMedia(profile, "chat", "", "", body)) {
            send(exchange, 400, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return 400;
        }
        String reply = profile.equals("A") ? "已收到本地验收消息。" : "B 服务已收到本地验收消息。";
        boolean delayed = request.contains("延迟");
        boolean delayBeforeHeaders = request.contains("fixture:delay-before-headers");
        boolean delayBeforeFirstEvent = request.contains("fixture:delay-before-first-event");
        boolean truncate = request.contains("fixture:truncate-sse") || control("chat.truncate.sse", false);
        boolean uploadedImage = request.contains("\"type\":\"image\"") || request.contains("\"type\": \"image\"");
        if (delayBeforeHeaders && !pause(controlMillis("chat.delay.before.headers.ms", 1_500))) return 200;
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        var output = exchange.getResponseBody();
        if (delayBeforeFirstEvent) {
            // Flush an SSE comment to open the stream before delaying its first data event.
            output.write(": stream open\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            if (!pause(controlMillis("chat.delay.before.first.event.ms", 1_500))) return 200;
        }
        if (truncate) {
            writeEvent(output, "{\"type\":\"plain\",\"data\":\"回复流中断前的部分内容。\"}");
            output.flush();
            return 200;
        }
        Matcher longStream = LONG_STREAM.matcher(request);
        if (longStream.find()) {
            serveLongStream(profile, output, longStream.group(1), request.contains("fixture:two-text-batches"));
            return 200;
        }
        if (request.contains("fixture:duration-pair") || request.contains("fixture:duration-edges")) {
            String[] samples = request.contains("fixture:duration-pair")
                    ? new String[] {"mono8", "stereo65"} : new String[] {"short", "metadata", "corrupt", "missing"};
            for (String sample : samples) {
                String id = "wav-" + profile + "-" + sample;
                writeEvent(output, "{\"type\":\"plain\",\"data\":\"" + sample + "\"}");
                writeEvent(output, "{\"type\":\"record\",\"data\":\"[RECORD]sample.wav\"}");
                writeEvent(output, "{\"type\":\"attachment_saved\",\"data\":{\"id\":\"" + id + "\",\"type\":\"record\"}}");
            }
            writeEvent(output, "{\"type\":\"end\"}");
            output.flush();
            return 200;
        }
        if (request.contains("fixture:voice-reply")) {
            String id = "reply-voice-" + profile.toLowerCase(java.util.Locale.ROOT);
            writeEvent(output, "{\"type\":\"plain\",\"data\":\"语音前文\"}");
            writeEvent(output, "{\"type\":\"record\",\"data\":\"[RECORD]fixture.wav\"}");
            writeEvent(output, "{\"type\":\"attachment_saved\",\"data\":{\"id\":\"" + id + "\",\"type\":\"record\"}}");
            writeEvent(output, "{\"type\":\"plain\",\"data\":\"语音后文\"}");
            writeEvent(output, "{\"type\":\"end\"}");
            output.flush();
            return 200;
        }
        if (delayed) {
            writeEvent(output, "{\"type\":\"plain\",\"data\":\"本地服务已收到，正在继续\",\"streaming\":true}");
            output.flush();
            try {
                Thread.sleep(6500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 200;
            }
            writeEvent(output, "{\"type\":\"plain\",\"data\":\"处理并完成。\"}");
        } else {
            writeEvent(output, "{\"type\":\"plain\",\"data\":\"" + reply + "\"}");
        }
        if (uploadedImage) {
            writeEvent(output, "{\"type\":\"plain\",\"data\":\" 图片已接收。\"}");
        }
        writeReplyImage(profile, output);
        writeEvent(output, "{\"type\":\"end\"}");
        output.flush();
        return 200;
    }

    private void serveLongStream(String profile, OutputStream output, String runId, boolean twoTextBatches) throws IOException {
        writeParagraphs(output, 1, 90);
        if (!awaitStage(output, runId, "text")) return;
        if (twoTextBatches) {
            writeParagraphs(output, 91, 99);
            if (!awaitStage(output, runId, "text2")) return;
            writeParagraphs(output, 100, 108);
        } else {
            writeParagraphs(output, 91, 108);
        }
        if (!awaitStage(output, runId, "image")) return;
        writeReplyImage(profile, output);
        output.flush();
        if (!awaitStage(output, runId, "end")) return;
        writeEvent(output, "{\"type\":\"end\"}");
        output.flush();
    }

    private static void writeParagraphs(OutputStream output, int first, int last) throws IOException {
        for (int number = first; number <= last; number++) {
            String label = String.format(java.util.Locale.ROOT, "Paragraph %03d", number);
            writeEvent(output, "{\"type\":\"plain\",\"streaming\":true,\"data\":\"" +
                    label + " | The sample transcript contains numbered observations for reading-position checks.\\n\\n\"}");
        }
        output.flush();
    }

    private boolean awaitStage(OutputStream output, String runId, String stage) throws IOException {
        Path gate = controlsFile.resolveSibling("long-stream-" + runId + "." + stage);
        int polls = 0;
        while (!Files.exists(gate)) {
            if (!pause(100)) return false;
            if (++polls % 50 == 0) {
                // Keep manual exercises alive and detect when the client has disconnected.
                output.write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        }
        return true;
    }

    private static void writeReplyImage(String profile, OutputStream output) throws IOException {
        String attachment = profile.equals("A") ? PNG_ID_A : PNG_ID_B;
        writeEvent(output, "{\"type\":\"image\",\"data\":\"[IMAGE]fixture.png\"}");
        writeEvent(output, "{\"type\":\"attachment_saved\",\"data\":{\"id\":\"" + attachment + "\",\"type\":\"image\"}}");
    }

    private int serveUpload(String profile, HttpExchange exchange) throws IOException {
        byte[] multipart = exchange.getRequestBody().readAllBytes();
        String mediaType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (mediaType == null || !mediaType.startsWith("multipart/form-data") || multipart.length == 0) {
            send(exchange, 400, "application/json", "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8));
            return 400;
        }
        if (control("upload.fail", false)) {
            send(exchange, 503, "application/json", "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8));
            return 503;
        }
        String id = "upload-" + profile.toLowerCase() + "-" + UUID.randomUUID();
        if (!verifyMedia(profile, "upload", id, mediaType, multipart)) {
            send(exchange, 400, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return 400;
        }
        String json = "{\"status\":\"ok\",\"data\":{\"attachment_id\":\"" + id + "\"}}";
        send(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        return 200;
    }

    private int serveDownload(String profile, HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String id = query == null ? "" : URLDecoder.decode(query.replaceFirst("^attachment_id=", ""), StandardCharsets.UTF_8);
        String prefix = "wav-" + profile + "-";
        if (id.startsWith(prefix)) {
            String sample = id.substring(prefix.length());
            byte[] wav = switch (sample) {
                case "mono8" -> voiceWav(8000, 1, false);
                case "stereo65" -> voiceWav(65000, 2, false);
                case "short" -> voiceWav(375, 1, false);
                case "metadata" -> voiceWav(2750, 1, true);
                case "corrupt" -> control("wav.repair.corrupt", false) ? voiceWav(3500, 1, false) : new byte[]{'R','I','F','F'};
                case "missing" -> control("wav.repair.missing", false) ? voiceWav(4500, 1, false) : null;
                default -> null;
            };
            send(exchange, wav == null ? 404 : 200, wav == null ? "application/json" : "audio/wav",
                    wav == null ? "{}".getBytes(StandardCharsets.UTF_8) : wav);
            return wav == null ? 404 : 200;
        }
        if (id.equals("reply-voice-" + profile.toLowerCase(java.util.Locale.ROOT))) {
            send(exchange, 200, "audio/wav", voiceWav(2000, 1, false));
            return 200;
        }
        byte[] image;
        String mediaType;
        if (profile.equals("A") && id.equals(PNG_ID_A)) {
            image = replyPngA;
            mediaType = "image/png";
        } else if (profile.equals("B") && id.equals(PNG_ID_B)) {
            image = replyJpegB;
            mediaType = "image/jpeg";
        } else {
            send(exchange, 404, "application/json", "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8));
            return 404;
        }
        send(exchange, 200, mediaType, image);
        return 200;
    }

    private String identify(String profile, String authorization) {
        String value = authorization == null ? "" : authorization.replaceFirst("(?i)^Bearer\\s+", "");
        if (value.equals(config.getProperty("token.invalid"))) return "invalid";
        if (profile.equals("A") && value.equals(config.getProperty("token.a.original"))) return "old_key";
        if (profile.equals("A") && value.equals(config.getProperty("token.a.second"))) return "second_key";
        if (profile.equals("B") && value.equals(config.getProperty("token.b.second"))) return "second_key";
        return "unknown";
    }

    private boolean rejectOldKey() {
        return control("reject.original", false);
    }

    private boolean control(String name, boolean fallback) {
        Properties controls = readControls();
        return Boolean.parseBoolean(controls.getProperty(name, Boolean.toString(fallback)));
    }

    private long controlMillis(String name, long fallback) {
        Properties controls = readControls();
        try {
            return Math.min(60_000, Math.max(0, Long.parseLong(controls.getProperty(name, Long.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Properties readControls() {
        Properties controls = new Properties();
        try (var input = Files.newInputStream(controlsFile)) {
            controls.load(input);
        } catch (IOException ignored) {
            // The fixture remains usable with its default, non-failing behavior if a control file is absent.
        }
        return controls;
    }

    private static boolean pause(long millis) {
        if (millis <= 0) return true;
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void logRequest(String profile, String method, String path, String alias, String session, int result) {
        String line = "profile=" + profile + " endpoint=" + method + " " + safePath(path) +
                " key=" + alias + " session=" + session + " result=" + result;
        for (String name : new String[] {"token.a.original", "token.a.second", "token.b.second", "token.invalid"}) {
            String secret = config.getProperty(name, "");
            if (!secret.isEmpty() && line.contains(secret)) {
                throw new IllegalStateException("Refusing to emit an unsafe fixture log line.");
            }
        }
        System.out.println(line);
        System.out.flush();
    }

    // Only enabled by Prepare-Fixture -VerifyMedia. The validator writes anonymous receipts,
    // never request bodies, into the private run directory. Its stdout contains only checked metadata.
    private boolean verifyMedia(String profile, String operation, String id, String mediaType, byte[] body) throws IOException {
        String verifier = config.getProperty("media.verifier");
        if (verifier == null) return true;
        Process process = new ProcessBuilder(config.getProperty("media.python"), "-I", verifier, operation, profile,
                controlsFile.getParent().toString(), id, mediaType).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(body);
            }
            String receipt = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0) return false;
            System.out.println("verified profile=" + profile + " " + receipt);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fixture verifier", interrupted);
        } finally {
            process.destroy();
        }
    }

    private static byte[] voiceWav(int millis, int channels, boolean metadata) throws IOException {
        int sampleRate = 16_000;
        int frames = (int) ((long) millis * sampleRate / 1000);
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        byte[] pcm = new byte[frames * format.getFrameSize()];
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) (1_000 * Math.sin(frame * 2 * Math.PI * 440 / sampleRate));
            for (int channel = 0; channel < channels; channel++) {
                int offset = (frame * channels + channel) * 2;
                pcm[offset] = (byte) sample;
                pcm[offset + 1] = (byte) (sample >> 8);
            }
        }
        try (var stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            var output = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output);
            byte[] wav = output.toByteArray();
            if (!metadata) return wav;
            // Insert an odd-sized RIFF JUNK chunk and its padding immediately after WAVE.
            return ByteBuffer.allocate(wav.length + 12).order(ByteOrder.LITTLE_ENDIAN)
                    .put(wav, 0, 4).putInt(wav.length + 4).put(wav, 8, 4)
                    .put(new byte[]{'J','U','N','K'}).putInt(3).put(new byte[]{1, 2, 3, 0})
                    .put(wav, 12, wav.length - 12).array();
        }
    }

    private static byte[] imageBytes(String format, Color color, String label) throws IOException {
        BufferedImage image = new BufferedImage(480, 260, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(242, 246, 248));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(color);
        g.fillRoundRect(24, 24, 432, 212, 28, 28);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
        g.drawString(label, 52, 143);
        g.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static void writeEvent(java.io.OutputStream output, String json) throws IOException {
        output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String find(Pattern pattern, String value, int group, String fallback) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(group) : fallback;
    }

    private static String safePath(String path) {
        if (path.equals("/api/v1/chat/sessions")) return "/api/v1/chat/sessions";
        if (path.equals("/api/v1/chat")) return "/api/v1/chat";
        if (path.equals("/api/v1/file")) return "/api/v1/file";
        return path.equals("/health") ? "/health" : "/other";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the private fixture properties path.");
        FixtureServer fixture = new FixtureServer(Path.of(args[0]));
        fixture.start();
        Files.writeString(Path.of(fixture.config.getProperty("ready.file")), "ready", StandardCharsets.UTF_8);
    }
}
