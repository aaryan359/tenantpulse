package com.tenantpulse.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenantpulse.server.security.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for API tests: the whole application on the in-memory store, driven through MockMvc with real signed tokens.
 * Each test uses its own tenant names, so tests do not disturb each other.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memory")
@TestPropertySource(properties = "tenantpulse.security.dev-token-endpoint-enabled=true")
public abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected String token(String subject, Role role, String tenant) throws Exception {
        String body = tenant == null
                ? "{\"subject\":\"" + subject + "\",\"role\":\"" + role + "\"}"
                : "{\"subject\":\"" + subject + "\",\"role\":\"" + role + "\",\"tenant\":\"" + tenant + "\"}";
        String response = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("token").asText();
    }

    protected ResultActions publish(String token, String body) throws Exception {
        return mvc.perform(post("/v1/change-sets").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions send(String token, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions read(String token, String pathAndQuery) throws Exception {
        return mvc.perform(get(pathAndQuery).header("Authorization", "Bearer " + token));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** A change-set body that sets one setting at one scope. */
    protected static String setOne(String key, String level, String client, String office, String value,
                                   String idempotencyKey) {
        String scope = "{\"level\":\"" + level + "\""
                + (client == null ? "" : ",\"clientId\":\"" + client + "\"")
                + (office == null ? "" : ",\"officeId\":\"" + office + "\"") + "}";
        return "{\"reason\":\"test\"" + (idempotencyKey == null ? "" : ",\"idempotencyKey\":\"" + idempotencyKey + "\"")
                + ",\"changes\":[{\"key\":\"" + key + "\",\"scope\":" + scope + ",\"value\":\"" + value + "\"}]}";
    }
}
