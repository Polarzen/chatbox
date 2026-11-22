import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class FixtureProbe {
    private final Properties config;

    private FixtureProbe(Properties config) {
        this.config = config;
        System.setProperty("javax.net.ssl.trustStore", config.getProperty("tls.trustStore"));
        System.setProperty("javax.net.ssl.trustStorePassword", config.getProperty("tls.trustPassword"));
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
    }

    private void run() throws Exception {
        checkSessions("A", config.getProperty("token.a.original"));
        checkSessions("B", config.getProperty("token.b.second"));
        int invalid = sessionsStatus("A", config.getProperty("token.invalid"));
        require(invalid == 401, "known invalid candidate must be rejected");

        String chat = postChat("A", config.getProperty("token.a.original"),
                config.getProperty("session.a"), "fixture ordinary chat");
        require(chat.contains("已收到本地验收消息") && chat.contains("reply-image-a"),
                "A chat stream should contain text and a saved image event");
        String delayed = postChat("A", config.getProperty("token.a.original"),
                config.getProperty("session.a"), "fixture 延迟 response");
        require(delayed.contains("正在继续") && delayed.contains("处理并完成"),
                "delayed response should stream a partial answer before completion");
        System.out.println("PROBE PASS endpoint=POST /api/v1/chat profile=A result=200 delayed=true");

        uploadAndDownload("A", config.getProperty("token.a.second"), "reply-image-a", "image/png");
        uploadAndDownload("B", config.getProperty("token.b.second"), "reply-image-b", "image/jpeg");

        Path controlsFile = Path.of(config.getProperty("controls.file"));
        Properties controls = new Properties();
        controls.setProperty("reject.original", "true");
        try (var output = Files.newOutputStream(controlsFile)) {
            controls.store(output, "private fixture switch");
        }
        try {
            require(sessionsStatus("A", config.getProperty("token.a.original")) == 401,
                    "previously-valid key should be rejectable after switching control");
            require(sessionsStatus("A", config.getProperty("token.a.second")) == 200,
                    "second valid key should remain accepted");
        } finally {
            controls.setProperty("reject.original", "false");
            try (var output = Files.newOutputStream(controlsFile)) {
                controls.store(output, "private fixture switch");
            }
        }
        System.out.println("PROBE PASS endpoint=GET /api/v1/chat/sessions profile=A result=401 then 200 switching=true");
    }

    private void checkSessions(String profile, String token) throws Exception {
        int status = sessionsStatus(profile, token);
        require(status == 200, profile + " sessions endpoint should accept its scoped key");
        System.out.println("PROBE PASS endpoint=GET /api/v1/chat/sessions profile=" + profile + " result=200");
    }

    private int sessionsStatus(String profile, String token) throws Exception {
        String url = base(profile) + "/api/v1/chat/sessions?page=1&page_size=100&platform_id=webchat&username=" +
                config.getProperty("username");
        HttpURLConnection connection = open(url, token, "GET");
        try {
            int status = connection.getResponseCode();
            if (status == 200) {
                String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                require(body.contains(config.getProperty("session." + profile.toLowerCase())),
                        profile + " sessions response should include its fixture legacy session");
            }
            return status;
        } finally {
            connection.disconnect();
        }
    }

    private String postChat(String profile, String token, String session, String message) throws Exception {
        HttpURLConnection connection = open(base(profile) + "/api/v1/chat", token, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "text/event-stream");
        String payload = "{\"message\":\"" + message + "\",\"username\":\"" + config.getProperty("username") +
                "\",\"session_id\":\"" + session + "\",\"enable_streaming\":true}";
        connection.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        try {
            require(connection.getResponseCode() == 200, "chat endpoint should return 200");
            require(connection.getContentType().startsWith("text/event-stream"), "chat should return SSE");
            return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private void uploadAndDownload(String profile, String token, String attachment, String expectedMime) throws Exception {
        byte[] image = Files.readAllBytes(Path.of(config.getProperty("media.dir"),
                profile.equals("A") ? "upload-a.png" : "upload-b.jpg"));
        String boundary = "----FixtureProbeBoundary";
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"probe." +
                (profile.equals("A") ? "png" : "jpg") + "\"\r\nContent-Type: " + expectedMime +
                "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(prefix);
        multipart.write(image);
        multipart.write(suffix);

        HttpURLConnection upload = open(base(profile) + "/api/v1/file", token, "POST");
        upload.setDoOutput(true);
        upload.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        upload.getOutputStream().write(multipart.toByteArray());
        try {
            require(upload.getResponseCode() == 200, profile + " image upload should return 200");
            String response = new String(upload.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(response.contains("\"attachment_id\":\"upload-"), "upload should return an attachment id");
        } finally {
            upload.disconnect();
        }

        HttpURLConnection download = open(base(profile) + "/api/v1/file?attachment_id=" + attachment, token, "GET");
        try {
            require(download.getResponseCode() == 200, profile + " image download should return 200");
            require(download.getContentType().startsWith(expectedMime), "download should keep image MIME type");
            byte[] downloaded = download.getInputStream().readAllBytes();
            require(downloaded.length > 100, "download should return a real image payload");
            require(profile.equals("A") ? (downloaded[0] & 0xff) == 0x89 && downloaded[1] == 'P'
                    : downloaded[0] == (byte) 0xff && downloaded[1] == (byte) 0xd8,
                    "download should return PNG for A and JPEG for B");
        } finally {
            download.disconnect();
        }
        System.out.println("PROBE PASS endpoint=POST+GET /api/v1/file profile=" + profile + " result=200");
    }

    private HttpURLConnection open(String value, String token, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(value).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private String base(String profile) {
        return config.getProperty("base." + profile.toLowerCase());
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the private fixture properties path.");
        Properties config = new Properties();
        try (var input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            config.load(input);
        }
        new FixtureProbe(config).run();
    }
}
