package com.everychurch.server.faith;

import com.everychurch.server.model.*;
import com.everychurch.server.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import static com.everychurch.server.faith.FaithTypes.*;
import static com.everychurch.server.faith.FaithCatalogService.*;

@Service
public class FaithProfileService {
    private final JdbcTemplate jdbc;
    private final FaithCatalogService catalogs;
    private final ChurchStaffRepository staff;
    private final ChurchRepository churches;
    public FaithProfileService(JdbcTemplate jdbc,FaithCatalogService catalogs,ChurchStaffRepository staff,ChurchRepository churches) {
        this.jdbc=jdbc;this.catalogs=catalogs;this.staff=staff;this.churches=churches;
    }
    public void authorize(long churchId,User user) {
        if(user==null || (user.getRole()!=Role.PLATFORM_ADMIN && !staff.findByChurchIdAndUserId(churchId,user.getId()).map(s->s.hasPermission(ChurchPermission.MANAGE_BELIEFS)).orElse(false)))
            throw new AccessDeniedException("Only church staff with Manage Beliefs permission may publish beliefs");
    }
    private String name(long id) { return churches.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Church not found")).getName(); }
    public Profile published(long id) {
        String name=name(id);
        var rows=jdbc.query("SELECT p.* FROM faith_profiles p JOIN faith_profile_current c ON c.profile_id=p.id WHERE c.church_id=?",(rs,n)->
            new Profile(id,name,rs.getLong("id"),rs.getTimestamp("published_at").toInstant().toString(),rs.getString("confirmed_by"),catalogs.decode(rs.getString("document"),ProfileInput.class)),id);
        return rows.isEmpty()?new Profile(id,name,null,null,null,null):rows.getFirst();
    }
    public Editor editor(long id,User user) {
        authorize(id,user);
        Profile pub=published(id);
        var draft=jdbc.query("SELECT document FROM faith_drafts WHERE church_id=?",(rs,n)->catalogs.decode(rs.getString(1),ProfileInput.class),id);
        var history=jdbc.query("SELECT * FROM faith_profiles WHERE church_id=? ORDER BY id DESC LIMIT 30",(rs,n)->new Profile(id,pub.churchName(),rs.getLong("id"),rs.getTimestamp("published_at").toInstant().toString(),rs.getString("confirmed_by"),catalogs.decode(rs.getString("document"),ProfileInput.class)),id);
        ProfileInput content=draft.isEmpty()?pub.content():draft.getFirst();
        if(draft.isEmpty() && content!=null) content=new ProfileInput(content.catalogVersion(),content.traditionId(),content.answers(),content.participation(),pub.revision());
        return new Editor(new Profile(id,pub.churchName(),pub.revision(),null,null,content),pub,history);
    }
    public void validate(ProfileInput input) {
        require(input!=null && input.catalogVersion()!=null && input.answers()!=null,"Missing profile");
        Catalog catalog=catalogs.get(input.catalogVersion());
        require(input.traditionId()==null || input.traditionId().isBlank() || catalog.traditions().stream().anyMatch(t->t.id().equals(input.traditionId())),"Unknown reference tradition");
        require(input.participation()==null || input.participation().length()<=3000,"Participation guidance is too long");
        require(input.answers().size()<=catalog.dimensions().size(),"Too many answers");
        input.answers().forEach((id,s)->{
            Dimension d=catalog.dimensions().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown belief dimension"));
            require(s!=null && s.option()!=null,"Missing statement option");
            boolean valid=Set.of("unknown","unstated","multiple").contains(s.option()) ||
                (d.category().equals("devotion")?Set.of("regular","occasional","private","not_offered").contains(s.option()):d.options().stream().anyMatch(o->o.id().equals(s.option())));
            require(valid,"Invalid church position");
            require(s.note()==null || s.note().length()<=1500,"Statement note is too long");
            safeUrl(s.sourceUrl());
        });
    }
    @Transactional public Profile draft(long id,User user,ProfileInput input) {
        authorize(id,user);validate(input);String name=name(id);
        jdbc.update("INSERT INTO faith_drafts(church_id,document,updated_by) VALUES (?,?,?) ON CONFLICT(church_id) DO UPDATE SET document=EXCLUDED.document,updated_by=EXCLUDED.updated_by,updated_at=now()",id,catalogs.encode(input),user.getId());
        return new Profile(id,name,input.baseRevision(),null,null,input);
    }
    @Transactional public Profile publish(long id,User user,ProfileInput input) {
        authorize(id,user);validate(input);
        // Serialize publications for this church and reject stale forms instead of overwriting.
        jdbc.queryForObject("SELECT id FROM churches WHERE id=? FOR UPDATE",Long.class,id);
        Profile previous=published(id);
        if(!Objects.equals(previous.revision(),input.baseRevision())) throw new ResponseStatusException(HttpStatus.CONFLICT,"This church profile changed. Reload before publishing.");
        long filled=input.answers().values().stream().filter(a->!Set.of("unknown","unstated").contains(a.option())).count();
        require(filled>=5,"Confirm at least five topics before publishing");
        Long revision=jdbc.queryForObject("INSERT INTO faith_profiles(church_id,catalog_version,document,confirmed_by) VALUES (?,?,?,?) RETURNING id",Long.class,id,input.catalogVersion(),catalogs.encode(input),user.getDisplayName());
        jdbc.update("INSERT INTO faith_profile_current(church_id,profile_id) VALUES (?,?) ON CONFLICT(church_id) DO UPDATE SET profile_id=EXCLUDED.profile_id",id,revision);
        jdbc.update("DELETE FROM faith_drafts WHERE church_id=?",id);
        return published(id);
    }
}
