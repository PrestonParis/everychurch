package com.everychurch.server.controller;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ParseUrlRequest;
import com.everychurch.server.service.ChurchService;
import com.everychurch.server.service.UrlParserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/churches")
public class ChurchController {

    private final ChurchService churchService;
    private final UrlParserService urlParserService;

    public ChurchController(ChurchService churchService, UrlParserService urlParserService) {
        this.churchService = churchService;
        this.urlParserService = urlParserService;
    }

    @GetMapping
    public List<ChurchDto> list() {
        return churchService.findAll();
    }

    @GetMapping("/{id}")
    public ChurchDto get(@PathVariable Long id) {
        return churchService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChurchDto create(@RequestBody ChurchDto dto) {
        return churchService.create(dto);
    }

    @PutMapping("/{id}")
    public ChurchDto update(@PathVariable Long id, @RequestBody ChurchDto dto) {
        return churchService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        churchService.delete(id);
    }

    @PostMapping("/parse-url")
    public ChurchDto parseUrl(@RequestBody ParseUrlRequest request) {
        return urlParserService.parse(request.url());
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NoSuchElementException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
