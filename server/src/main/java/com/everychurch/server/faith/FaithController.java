package com.everychurch.server.faith;

import com.everychurch.server.model.User;
import com.everychurch.server.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import static com.everychurch.server.faith.FaithTypes.*;

@RestController
public class FaithController {
    private final FaithCatalogService catalogs;
    private final FaithProfileService profiles;
    private final FaithEvaluationService evaluations;
    private final UserRepository users;
    public FaithController(FaithCatalogService catalogs,FaithProfileService profiles,FaithEvaluationService evaluations,UserRepository users) {
        this.catalogs=catalogs;this.profiles=profiles;this.evaluations=evaluations;this.users=users;
    }
    @GetMapping("/api/learn/quiz") public Catalog current() { return catalogs.current(); }
    @GetMapping("/api/learn/quiz/versions/{version}") public Catalog version(@PathVariable String version) {return catalogs.get(version);}
    @PostMapping("/api/learn/quiz/evaluate") public Result evaluate(@RequestBody Evaluation request) { return evaluations.evaluate(request); }
    @PostMapping("/api/admin/faith/catalogs") @ResponseStatus(HttpStatus.CREATED)
    public Catalog publishCatalog(@RequestBody Catalog catalog) { return catalogs.publish(catalog); }
    @GetMapping("/api/churches/{id}/beliefs") public Profile profile(@PathVariable long id) {return profiles.published(id);}
    @GetMapping("/api/churches/{id}/beliefs/editor") public Editor editor(@PathVariable long id,Authentication auth) {return profiles.editor(id,user(auth));}
    @PutMapping("/api/churches/{id}/beliefs/draft") public Profile draft(@PathVariable long id,@RequestBody ProfileInput input,Authentication auth) {return profiles.draft(id,user(auth),input);}
    @PostMapping("/api/churches/{id}/beliefs/publish") public Profile publish(@PathVariable long id,@RequestBody ProfileInput input,Authentication auth) {return profiles.publish(id,user(auth),input);}
    @GetMapping("/api/users/me/quiz-attempts") public List<Saved> saved(Authentication auth) {return evaluations.list(user(auth));}
    @PostMapping("/api/users/me/quiz-attempts") @ResponseStatus(HttpStatus.CREATED)
    public Saved save(@RequestBody Evaluation request,Authentication auth) {return evaluations.save(user(auth),request);}
    @DeleteMapping("/api/users/me/quiz-attempts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id,Authentication auth) {evaluations.delete(user(auth),id);}
    private User user(Authentication auth) {
        if(auth==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in first");
        return users.findByEmail(auth.getName()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in first"));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException e) {return Map.of("error",e.getMessage());}
}
