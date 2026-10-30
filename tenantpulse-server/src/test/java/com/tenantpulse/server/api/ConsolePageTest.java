package com.tenantpulse.server.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo console is a static page: it needs no token, and it opens no door to the data. */
class ConsolePageTest extends ApiTestSupport {

    @Test
    void theConsolePageIsServedWithoutAToken() throws Exception {
        mvc.perform(get("/index.html").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TenantPulse demo console")));
        mvc.perform(get("/").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
    }

    @Test
    void theDataIsStillLockedBehindATokenAndOtherPathsStayClosed() throws Exception {
        mvc.perform(get("/v1/snapshot")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/application.yml")).andExpect(status().isUnauthorized());
    }
}
