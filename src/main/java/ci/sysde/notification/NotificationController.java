package ci.sysde.notification;

import java.util.List;
import java.util.UUID;

import ci.sysde.notification.NotificationService.Notification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Notifications du compte connecté (endpoints-back.md §4). Le destinataire est toujours celui du jeton. */
@RestController
class NotificationController {

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/notifications")
    List<Notification> lister(Authentication auth) {
        return service.dernieres(UUID.fromString(auth.getName()));
    }

    @PostMapping("/notifications/{id}/lue")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void lue(@PathVariable UUID id, Authentication auth) {
        service.marquerLue(UUID.fromString(auth.getName()), id);
    }

    @PostMapping("/notifications/lues")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void toutesLues(Authentication auth) {
        service.toutMarquerLu(UUID.fromString(auth.getName()));
    }
}
