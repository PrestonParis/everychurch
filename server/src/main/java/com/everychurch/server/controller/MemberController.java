package com.everychurch.server.controller;

import com.everychurch.server.dto.MemberDto;
import com.everychurch.server.dto.MembershipStatusDto;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import com.everychurch.server.service.MemberService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Every route here needs a login — SecurityConfig lists /api/churches/{id}/members/** as authenticated ahead of the public GET rule. */
@RestController
@RequestMapping("/api/churches/{churchId}/members")
public class MemberController {

    private final MemberService memberService;
    private final UserRepository userRepository;

    public MemberController(MemberService memberService, UserRepository userRepository) {
        this.memberService = memberService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<MemberDto> list(@PathVariable Long churchId, Authentication authentication) {
        return memberService.list(churchId, currentUser(authentication));
    }

    @GetMapping("/me")
    public MembershipStatusDto status(@PathVariable Long churchId, Authentication authentication) {
        return memberService.status(churchId, currentUser(authentication));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberDto join(@PathVariable Long churchId, Authentication authentication) {
        return memberService.join(churchId, currentUser(authentication));
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable Long churchId, Authentication authentication) {
        memberService.leave(churchId, currentUser(authentication));
    }

    private User currentUser(Authentication authentication) {
        String email = (String) authentication.getPrincipal();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new NoSuchElementException("Authenticated user no longer exists"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NoSuchElementException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
