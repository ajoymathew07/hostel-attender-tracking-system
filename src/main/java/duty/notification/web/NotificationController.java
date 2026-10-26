package duty.notification.web;

import duty.notification.application.ManagerStream;
import duty.notification.application.PushNotifications;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The manager's live feed and the attender's push endpoints (sections 9.3, 9.5, 9.6). */
@RestController
class NotificationController {

    private final ManagerStream stream;
    private final PushNotifications push;

    NotificationController(ManagerStream stream, PushNotifications push) {
        this.stream = stream;
        this.push = push;
    }

    @GetMapping(path = "/api/manager/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return stream.open();
    }

    /** 404 when push is not configured, which tells the page to leave the button hidden. */
    @GetMapping("/api/attender/push/key")
    ResponseEntity<Map<String, String>> key() {
        return push.publicKey()
                .map(key -> ResponseEntity.ok(Map.of("publicKey", key)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api/attender/push/subscribe")
    ResponseEntity<Void> subscribe(@Valid @RequestBody Subscription subscription) {
        push.subscribe(
                subscription.endpoint(),
                subscription.keys().p256dh(),
                subscription.keys().auth(),
                subscription.deviceLabel());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/attender/push/unsubscribe")
    ResponseEntity<Void> unsubscribe(@Valid @RequestBody Subscription subscription) {
        push.unsubscribe(subscription.endpoint());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/attender/push/test")
    Map<String, Integer> test() {
        return Map.of("delivered", push.sendTest());
    }

    /** The browser's {@code PushSubscription} as JSON, plus an optional label. */
    record Subscription(@NotBlank String endpoint, @NotNull @Valid Keys keys, String deviceLabel) {

        record Keys(@NotBlank String p256dh, @NotBlank String auth) {}
    }
}
