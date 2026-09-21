package com.everychurch.server.controller;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.service.ChurchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Public, no-login redirect for the volunteer field kit's printed QR code (Prompt
 * 15): the client's /go landing page reads the scanning device's geolocation and
 * navigates here, which forwards to that nearest church's profile on the Angular
 * client. See docs/ARCHITECTURE.md "Geocoding".
 */
@RestController
public class RedirectController {

    private final ChurchService churchService;
    private final String clientBaseUrl;

    public RedirectController(ChurchService churchService, @Value("${app.client-base-url:http://localhost:4200}") String clientBaseUrl) {
        this.churchService = churchService;
        this.clientBaseUrl = clientBaseUrl;
    }

    @GetMapping("/r/nearest")
    public ResponseEntity<Void> nearest(@RequestParam double lat, @RequestParam double lng) {
        List<ChurchDto> nearest = churchService.findNearest(lat, lng, 1);
        String target = nearest.isEmpty() ? clientBaseUrl : clientBaseUrl + "/churches/" + nearest.get(0).id();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}
