package com.tenantpulse.benchmarks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers shared by the runners: get a dev token, publish a change, compute percentiles. */
final class Bench {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private Bench() {
    }

    static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }

    /** Needs the server started with tenantpulse.security.dev-token-endpoint-enabled=true. */
    static String token(String baseUrl, String subject, String role, String tenant) throws IOException, InterruptedException {
        String body = "{\"subject\":\"" + subject + "\",\"role\":\"" + role + "\",\"tenant\":\"" + tenant + "\"}";
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + "/dev/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        Matcher matcher = TOKEN.matcher(response.body());
        if (response.statusCode() != 200 || !matcher.find()) {
            throw new IOException("could not get a token (is the dev token endpoint enabled?): HTTP " + response.statusCode());
        }
        return matcher.group(1);
    }

    /** Publishes one OFFICE-level override and returns the HTTP status. */
    static int publishOffice(String baseUrl, String token, String tenant, String office, String key, String value,
                             String idempotencyKey) throws IOException, InterruptedException {
        String body = "{\"reason\":\"benchmark\",\"idempotencyKey\":\"" + idempotencyKey + "\",\"changes\":[{\"key\":\""
                + key + "\",\"scope\":{\"level\":\"OFFICE\",\"clientId\":\"" + tenant + "\",\"officeId\":\"" + office
                + "\"},\"value\":\"" + value + "\"}]}";
        return HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/change-sets"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static String get(String url, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static double percentileMillis(long[] sortedNanos, double percentile) {
        if (sortedNanos.length == 0) {
            return Double.NaN;
        }
        int index = (int) Math.min(sortedNanos.length - 1, Math.ceil(percentile / 100.0 * sortedNanos.length) - 1);
        return sortedNanos[Math.max(0, index)] / 1_000_000.0;
    }

    static long[] sorted(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }
}
