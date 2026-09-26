package com.everychurch.server.faith;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import static com.everychurch.server.faith.FaithTypes.*;

@Service
public class FaithCatalogService implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public FaithCatalogService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    @Override public void run(ApplicationArguments args) throws Exception {
        Catalog seed = json.readValue(new ClassPathResource("faith/catalog-v1.json").getInputStream(), Catalog.class);
        validate(seed);
        jdbc.update("INSERT INTO faith_catalogs(version,document) VALUES (?,?) ON CONFLICT DO NOTHING", seed.version(), encode(seed));
        jdbc.update("INSERT INTO faith_catalog_current(singleton,version) VALUES (true,?) ON CONFLICT DO NOTHING", seed.version());
    }
    public Catalog current() { return get(jdbc.queryForObject("SELECT version FROM faith_catalog_current",String.class)); }
    public Catalog get(String version) {
        var docs=jdbc.query("SELECT document FROM faith_catalogs WHERE version=?", (rs,n)->rs.getString(1),version);
        if(docs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown quiz version");
        return decode(docs.getFirst(),Catalog.class);
    }
    @Transactional public Catalog publish(Catalog catalog) {
        validate(catalog);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM faith_catalogs WHERE version=?)",Boolean.class,catalog.version())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Published versions are immutable; use a new version");
        // New versions may add topics/options, but cannot silently reinterpret saved answers.
        Catalog previous=current();
        for(Dimension old:previous.dimensions()) {
            Dimension next=catalog.dimensions().stream().filter(d->d.id().equals(old.id())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Do not remove stable dimensions"));
            require(next.category().equals(old.category()),"Do not change dimension categories");
            for(Option option:old.options()) require(next.options().contains(option),"Do not change or remove stable options; add a new dimension instead");
        }
        jdbc.update("INSERT INTO faith_catalogs(version,document) VALUES (?,?)",catalog.version(),encode(catalog));
        jdbc.update("UPDATE faith_catalog_current SET version=? WHERE singleton=true",catalog.version());
        return catalog;
    }
    public String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid faith document",e); } }
    public <T> T decode(String value,Class<T> type) { try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException("Unreadable faith document",e); } }
    public static void require(boolean test,String message) { if(!test) throw new IllegalArgumentException(message); }
    public static void safeUrl(String url) {
        if(url==null || url.isBlank()) return;
        require(url.length()<=2000,"Source URL is too long");
        try { URI u=URI.create(url); require("https".equals(u.getScheme()) && u.getHost()!=null && u.getUserInfo()==null,"Use a complete HTTPS source URL"); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("Use a complete HTTPS source URL"); }
    }
    private void validateSource(Source s) {
        require(s!=null && s.title()!=null && !s.title().isBlank() && s.title().length()<=300 && s.url()!=null && !s.url().isBlank(),"Sources need a title and HTTPS URL");
        safeUrl(s.url());
    }
    public void validate(Catalog c) {
        require(c!=null && c.version()!=null && c.version().matches("[a-zA-Z0-9-]{1,80}"),"Invalid catalog version");
        require("alignment-v1".equals(c.rulesVersion()),"Unsupported matching rules version");
        require(c.notice()!=null && c.notice().length()<=3000,"Catalog needs an introduction");
        require(c.dimensions()!=null && !c.dimensions().isEmpty() && c.dimensions().size()<=60,"Provide 1–60 dimensions");
        Set<String> ids=new HashSet<>();
        for(Dimension d:c.dimensions()) {
            require(d!=null,"Invalid dimension");
            require(d.id()!=null && d.id().matches("[a-z0-9-]{1,60}") && ids.add(d.id()),"Dimension ids must be unique");
            require(d.category()!=null && Set.of("doctrine","practice","devotion").contains(d.category()),"Invalid dimension category");
            require(d.title()!=null && !d.title().isBlank() && d.title().length()<=200 && d.question()!=null && !d.question().isBlank() && d.question().length()<=1000 && d.explanation()!=null && d.explanation().length()<=3000,"Missing question text");
            require(d.options()!=null && !d.options().isEmpty() && d.options().size()<=12,"Invalid options");
            Set<String> options=new HashSet<>();
            for(Option o:d.options()) require(o!=null && o.id()!=null && o.id().matches("[a-z0-9-]{1,60}") && !Set.of("unknown","multiple","unstated").contains(o.id()) && options.add(o.id()) && o.label()!=null && o.group()!=null,"Invalid option");
            require(d.sources()!=null && !d.sources().isEmpty(),"Every topic needs sources");
            d.sources().forEach(this::validateSource);
        }
        require(c.traditions()!=null && c.traditions().size()<=100,"Invalid traditions");
        Set<String> traditions=new HashSet<>();
        for(Tradition t:c.traditions()) {
            require(t!=null,"Invalid tradition");
            require(t.id()!=null && t.id().matches("[a-z0-9-]{1,80}") && traditions.add(t.id()) && t.name()!=null && !t.name().isBlank() && t.name().length()<=200 && t.summary()!=null && t.summary().length()<=3000,"Invalid tradition");
            require(t.answers()!=null && t.sources()!=null && !t.sources().isEmpty(),"Traditions need answers and sources");
            t.sources().forEach(this::validateSource);
            t.answers().forEach((id,option)->require(c.dimensions().stream().anyMatch(d->d.id().equals(id) && !d.category().equals("devotion") && d.options().stream().anyMatch(o->o.id().equals(option))),"Invalid tradition position"));
        }
    }
}
