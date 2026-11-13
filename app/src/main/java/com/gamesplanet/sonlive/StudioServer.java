package com.gamesplanet.sonlive;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class StudioServer {
    final String hostKey = key();
    private final String remoteKey = key();
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final byte[] page;
    private final ArrayDeque<JSONObject> commands = new ArrayDeque<>();
    private volatile byte[] state = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile byte[] program;
    private volatile byte[] preview;

    StudioServer(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("studio.html")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            page = bytes.toByteArray();
        }
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(8765));
        new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    try { workers.execute(() -> serve(client)); }
                    catch (RejectedExecutionException e) { client.close(); }
                } catch (IOException e) { break; }
            }
        }, "studio-http").start();
    }

    private static String key() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return android.util.Base64.encodeToString(bytes, 11);
    }

    String localOrigin() { return "http://127.0.0.1:" + listener.getLocalPort(); }

    String remoteUrl() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String fallback = "";
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) continue;
                    String url = "http://" + address.getHostAddress() + ":" + listener.getLocalPort() + "/remote#" + remoteKey;
                    if (network.getName().startsWith("wlan") || network.getName().startsWith("ap")) return url;
                    fallback = url;
                }
            }
            return fallback;
        } catch (SocketException e) { return ""; }
    }

    void close() {
        try { listener.close(); } catch (IOException ignored) { }
        workers.shutdown();
    }

    private void serve(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(3500);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            String[] request = line(input).split(" ", 3);
            if (request.length != 3) return;
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 48; i++) {
                String header = line(input);
                if (header.isEmpty()) break;
                int split = header.indexOf(':');
                if (split > 0) headers.put(header.substring(0, split).toLowerCase(Locale.ROOT), header.substring(split + 1).trim());
            }
            String method = request[0], path = request[1];
            boolean local = socket.getInetAddress().isLoopbackAddress();
            boolean host = local && hostKey.equals(headers.get("x-studio-key"));
            boolean remote = remoteKey.equals(headers.get("x-studio-key"));
            if (method.equals("GET") && (path.equals("/remote") || local && path.equals("/studio"))) {
                respond(output, 200, "text/html; charset=utf-8", page);
                return;
            }
            if (!host && !remote) { respond(output, 403, "text/plain", null); return; }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (length < 0 || length > 500_000) { respond(output, 413, "text/plain", null); return; }
            byte[] body = new byte[length];
            new DataInputStream(input).readFully(body);
            if (method.equals("POST") && path.equals("/command") && remote) {
                JSONObject command = new JSONObject(new String(body, StandardCharsets.UTF_8));
                synchronized (commands) {
                    if (commands.size() >= 64) { respond(output, 429, "text/plain", null); return; }
                    commands.add(command);
                }
                respond(output, 204, "text/plain", null);
            } else if (method.equals("GET") && path.equals("/commands") && host) {
                JSONArray batch = new JSONArray();
                synchronized (commands) { while (!commands.isEmpty()) batch.put(commands.remove()); }
                respond(output, 200, "application/json", batch.toString().getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("POST") && host && path.equals("/state")) {
                state = body;
                respond(output, 204, "text/plain", null);
            } else if (method.equals("POST") && host && (path.equals("/program") || path.equals("/preview"))) {
                if (path.equals("/program")) program = body; else preview = body;
                respond(output, 204, "text/plain", null);
            } else if (method.equals("GET") && path.equals("/state")) {
                respond(output, 200, "application/json", state);
            } else if (method.equals("GET") && (path.equals("/program") || path.equals("/preview") || host && path.equals("/screen"))) {
                byte[] image = path.equals("/program") ? program : path.equals("/preview") ? preview : Capture.frame;
                respond(output, image == null ? 204 : 200, "image/jpeg", image);
            } else respond(output, 404, "text/plain", null);
        } catch (IOException | JSONException | NumberFormatException ignored) { }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < 8192) {
            int ch = input.read();
            if (ch == -1 || ch == '\n') return out.toString("US-ASCII").trim();
            out.write(ch);
        }
        throw new IOException("Line too long");
    }

    private static void respond(OutputStream output, int code, String type, byte[] body) throws IOException {
        String header = "HTTP/1.1 " + code + " Studio\r\nContent-Type: " + type +
            "\r\nContent-Length: " + (body == null ? 0 : body.length) +
            "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff" +
            "\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY" +
            "\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src blob: data:; media-src blob:; object-src 'none'; base-uri 'none'\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        if (body != null) output.write(body);
        output.flush();
    }
}
