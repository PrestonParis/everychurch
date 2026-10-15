package com.everychurch.server.faith;

import com.everychurch.server.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static com.everychurch.server.faith.FaithTypes.*;
import static com.everychurch.server.faith.FaithCatalogService.require;

@Service
public class FaithEvaluationService {
    private final FaithCatalogService catalogs;
    private final FaithMatcher matcher;
    private final JdbcTemplate jdbc;
    public FaithEvaluationService(FaithCatalogService catalogs,FaithMatcher matcher,JdbcTemplate jdbc) { this.catalogs=catalogs;this.matcher=matcher;this.jdbc=jdbc; }
    private int tier(Match m) { return switch(m.status()) {case "strong_alignment","compare"->0;case "limited_information"->1;case "needs_confirmation"->2;case "essential_conflict"->3;default->4;}; }
    private Comparator<Match> order() { return Comparator.comparingInt(this::tier).thenComparing(Comparator.comparingDouble(Match::lowerBound).reversed()).thenComparing(Match::name).thenComparing(Match::id); }
    @Transactional(readOnly=true) public Result evaluate(Evaluation request) {
        require(request!=null && request.catalogVersion()!=null,"Choose a quiz version");
        Catalog catalog=catalogs.get(request.catalogVersion());
        matcher.validate(catalog,request.answers());
        String city=request.city()==null?"":request.city().trim();
        String state=request.state()==null?"":request.state().trim();
        require(city.length()<=100 && state.length()<=100,"Location is too long");
        boolean coordinates=request.latitude()!=null || request.longitude()!=null;
        if(coordinates) require(request.latitude()!=null && request.longitude()!=null && Double.isFinite(request.latitude()) && Double.isFinite(request.longitude()) && Math.abs(request.latitude())<=90 && Math.abs(request.longitude())<=180,"Invalid coordinates");
        double radius=request.radiusKm()==null?40:request.radiusKm();
        require(Double.isFinite(radius) && radius>=1 && radius<=200,"Radius must be between 1 and 200 km");
        boolean location=coordinates || (!city.isEmpty() && !state.isEmpty());
        List<Match> traditions=catalog.traditions().stream().map(t->matcher.score(catalog,request.answers(),t.answers().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,e->new Statement(e.getValue(),null,null))),t.id(),t.name(),"tradition",null,null,null)).sorted(order()).toList();
        // No arbitrary nearest-N preselection: score every published profile in the chosen area,
        // in bounded batches, retaining only the best 30 matches in memory.
        PriorityQueue<Match> best=new PriorityQueue<>(31,order().reversed());
        int evaluated=0;
        long after=0;
        if(location) while(true) {
            String filter=coordinates?"ST_DWithin(c.geo_point,ST_SetSRID(ST_MakePoint(?,?),4326)::geography,?)":"UPPER(c.city)=UPPER(?) AND UPPER(c.state)=UPPER(?)";
            List<Object> params=new ArrayList<>();params.add(after);
            if(coordinates) {params.add(request.longitude());params.add(request.latitude());params.add(radius*1000);}
            else {params.add(city);params.add(state);}
            var batch=jdbc.query("SELECT c.id,c.name,p.id AS revision,p.document,p.published_at FROM churches c JOIN faith_profile_current pc ON pc.church_id=c.id JOIN faith_profiles p ON p.id=pc.profile_id WHERE c.id>? AND "+filter+" ORDER BY c.id LIMIT 200",(rs,n)->new Profile(rs.getLong("id"),rs.getString("name"),rs.getLong("revision"),rs.getTimestamp("published_at").toInstant().toString(),null,catalogs.decode(rs.getString("document"),ProfileInput.class)),params.toArray());
            if(batch.isEmpty()) break;
            for(Profile p:batch) {
                // Stable ids/options are required across catalog revisions. New dimensions are unknown.
                Match match=matcher.score(catalog,request.answers(),p.content().answers(),Long.toString(p.churchId()),p.churchName(),"church",p.revision(),p.publishedAt(),p.content().participation());
                best.add(match);if(best.size()>30) best.poll();evaluated++;after=p.churchId();
            }
        }
        String guidance=request.answers().values().stream().noneMatch(a->a.importance()>0 && !a.option().equals("unknown"))?
            "You haven't answered enough questions for us to compare yet. Answer a few more, or read about the traditions below.":
            "Here's how your answers line up with what each tradition and church has said it believes. When one hasn't said where it stands on something you answered, that topic adds nothing to its score.";
        return new Result(catalog.version(),catalog.rulesVersion(),Instant.now().toString(),guidance,traditions,best.stream().sorted(order()).toList(),evaluated,location);
    }
    @Transactional public Saved save(User user,Evaluation request) {
        Result result=evaluate(request);
        require(jdbc.queryForObject("SELECT count(*) FROM faith_saved_attempts WHERE user_id=?",Integer.class,user.getId())<50,"You can save up to 50 attempts. Delete an older attempt first.");
        Long id=jdbc.queryForObject("INSERT INTO faith_saved_attempts(user_id,request_document,result_document) VALUES (?,?,?) RETURNING id",Long.class,user.getId(),catalogs.encode(request),catalogs.encode(result));
        return get(user,id);
    }
    public List<Saved> list(User user) { return jdbc.query("SELECT * FROM faith_saved_attempts WHERE user_id=? ORDER BY id DESC LIMIT 50",(rs,n)->new Saved(rs.getLong("id"),rs.getTimestamp("created_at").toInstant().toString(),catalogs.decode(rs.getString("request_document"),Evaluation.class),catalogs.decode(rs.getString("result_document"),Result.class)),user.getId()); }
    public Saved get(User user,long id) { return list(user).stream().filter(s->s.id()==id).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved result not found")); }
    public void delete(User user,long id) {
        if(jdbc.update("DELETE FROM faith_saved_attempts WHERE id=? AND user_id=?",id,user.getId())==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved result not found");
    }
}
