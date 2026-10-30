package com.tenantpulse.server.api;

import com.tenantpulse.server.security.Caller;
import com.tenantpulse.server.security.TenantAccessPolicy;
import com.tenantpulse.server.service.ChangeBroadcaster;
import com.tenantpulse.server.service.ConfigReadService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live-push stream (server-sent events). Each event names a version that is new for the caller's tenant; the
 * client then asks for the changes itself. Opening the stream immediately sends the current version, so a client
 * that reconnects after a gap catches up at once.
 */
@RestController
@RequestMapping("/v1")
public class StreamController {

    private final ChangeBroadcaster broadcaster;
    private final ConfigReadService reads;
    private final TenantAccessPolicy policy;

    public StreamController(ChangeBroadcaster broadcaster, ConfigReadService reads, TenantAccessPolicy policy) {
        this.broadcaster = broadcaster;
        this.reads = reads;
        this.policy = policy;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(Caller caller, @RequestParam(required = false) String tenant) {
        String tenantId = policy.tenantToRead(caller, tenant);
        SseEmitter emitter = broadcaster.subscribe(tenantId, () -> reads.version(tenantId).relevant());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
