package com.tenantpulse.server.api;

import com.tenantpulse.core.model.PublishedChangeSet;
import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.service.ChangeSetService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** HTTP only: turns a request into a domain call and the result back into JSON. */
@RestController
@RequestMapping("/v1/change-sets")
public class ChangeSetController {

    private final ChangeSetService service;

    public ChangeSetController(ChangeSetService service) {
        this.service = service;
    }

    @PostMapping
    public PublishResponse publish(Caller caller, @RequestBody ChangeSetRequest request) {
        PublishedChangeSet published = service.publish(caller, request.toChangeSet(caller.subject()));
        return new PublishResponse(published.version(), published.publishedAt());
    }

    public record PublishResponse(long version, Instant publishedAt) {
    }
}
