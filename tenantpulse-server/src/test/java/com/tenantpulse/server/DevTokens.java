package com.tenantpulse.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches a token from a running server's demo token endpoint, for tests that talk to it over real HTTP. */
public final class DevTokens {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private DevTokens() {
    }

    public static String fetch(String baseUrl, String subject, String role, String tenant)
            throws IOException, InterruptedException {
        String body = "{\"subject\":\"" + subject + "\",\"role\":\"" + role + "\",\"tenant\":\"" + tenant + "\"}";
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + "/dev/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        Matcher matcher = TOKEN.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("no token in the response (HTTP " + response.statusCode() + ")");
        }
        return matcher.group(1);
    }
}
