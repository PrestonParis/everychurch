package com.everychurch.server.faith;

import org.springframework.stereotype.Component;
import java.util.*;
import static com.everychurch.server.faith.FaithTypes.*;
import static com.everychurch.server.faith.FaithCatalogService.require;

/** No inferred positions, randomness, network calls or user profiling. Rules are alignment-v1. */
@Component
public class FaithMatcher {
    public void validate(Catalog catalog, Map<String,Answer> answers) {
        require(answers!=null && answers.size()<=catalog.dimensions().size(),"Invalid answers");
        answers.forEach((id,a)->{
            Dimension d=catalog.dimensions().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown question"));
            require(a!=null && a.importance()>=0 && a.importance()<=3 && a.option()!=null,"Invalid answer importance");
            require(a.option().equals("unknown") || d.options().stream().anyMatch(o->o.id().equals(a.option())),"Unknown answer option");
        });
    }
    private String topic(Dimension d) {
        if(d.id().startsWith("baptism")) return "baptism";
        if(Set.of("gifts","tongues").contains(d.id())) return "gifts";
        if(Set.of("authority","scripture").contains(d.id())) return "authority";
        return d.id();
    }
    public Match score(Catalog catalog, Map<String,Answer> answers, Map<String,Statement> positions,
                       String id,String name,String kind,Long revision,String date,String participation) {
        List<Detail> details=new ArrayList<>();
        double[] total={0,0}, known={0,0}, earned={0,0};
        boolean conflict=false, essentialUnknown=false;
        int answered=0,compared=0;
        Map<String,Long> counts=new HashMap<>();
        for(Dimension d:catalog.dimensions()) {
            Answer a=answers.get(d.id());
            if(a!=null && a.importance()>0 && !a.option().equals("unknown")) counts.merge(topic(d),1L,Long::sum);
        }
        for(Dimension d:catalog.dimensions()) {
            Answer a=answers.get(d.id());
            if(a==null || a.importance()==0 || a.option().equals("unknown")) continue;
            answered++;
            int category=d.category().equals("doctrine")?0:1;
            // Related baptism/gift/authority questions share a topic budget.
            double w=(a.importance()==3?4:a.importance())/(double)counts.get(topic(d));
            total[category]+=w;
            Statement statement=positions.get(d.id());
            String position=statement==null?"unknown":statement.option();
            boolean missing=position==null || Set.of("unknown","unstated").contains(position)
                || (!d.category().equals("devotion") && d.options().stream().noneMatch(o->o.id().equals(position)) && !position.equals("multiple"));
            String outcome;
            if(missing) { outcome="unknown"; essentialUnknown|=a.importance()==3; }
            else {
                compared++;
                known[category]+=w;
                double compatibility=compatibility(d,a.option(),position);
                earned[category]+=w*compatibility;
                outcome=compatibility==1?"agreement":compatibility==0?"difference":"partial";
                if(a.importance()==3 && compatibility<1) {
                    if(position.equals("multiple")) essentialUnknown=true;
                    else conflict=true;
                }
            }
            details.add(new Detail(d.id(),d.title(),label(d,a.option()),churchLabel(d,position),outcome,a.importance()==3));
        }
        double mixTotal=(total[0]>0?.75:0)+(total[1]>0?.25:0);
        double coverage=0,lower=0,upper=0;
        for(int i=0;i<2;i++) if(total[i]>0) {
            double mix=(i==0?.75:.25)/mixTotal;
            coverage+=mix*known[i]/total[i];
            lower+=mix*earned[i]/total[i];
            upper+=mix*(earned[i]+total[i]-known[i])/total[i];
        }
        String status=answered==0?"explore":conflict?"essential_conflict":essentialUnknown?"needs_confirmation":
            (compared<5 || coverage<.65)?"limited_information":lower>=.75?"strong_alignment":"compare";
        return new Match(id,name,kind,status,known[0]==0?null:round(100*earned[0]/known[0]),
            known[1]==0?null:round(100*earned[1]/known[1]),round(100*coverage),round(100*lower),round(100*upper),
            answered,compared,revision,date,participation,details);
    }
    private double compatibility(Dimension d,String user,String church) {
        if(church.equals("multiple")) return .5;
        if(d.category().equals("devotion")) {
            if(user.equals("comfortable")) return 1;
            if(user.equals("seek")) return switch(church) { case "regular"->1; case "occasional"->.5; case "private"->.25; default->0; };
            return switch(church) { case "not_offered","private"->1; case "occasional"->.5; default->0; };
        }
        if(user.equals(church) || (d.id().equals("worship") && user.equals("both"))) return 1;
        if(d.id().equals("worship") && church.equals("both")) return 1;
        var a=d.options().stream().filter(o->o.id().equals(user)).findFirst();
        var b=d.options().stream().filter(o->o.id().equals(church)).findFirst();
        return a.isPresent() && b.isPresent() && a.get().group().equals(b.get().group())?.5:0;
    }
    private String label(Dimension d,String value) { return d.options().stream().filter(o->o.id().equals(value)).map(Option::label).findFirst().orElse(value==null?"Not provided":value); }
    private String churchLabel(Dimension d,String value) {
        if(value==null) return "Not provided";
        return switch(value) { case "unknown"->"Not provided"; case "unstated"->"No stated position"; case "multiple"->"Multiple positions permitted — ask the church"; case "regular"->"Offered regularly"; case "occasional"->"Offered occasionally"; case "private"->"Encouraged privately"; case "not_offered"->"Not offered locally"; default->label(d,value); };
    }
    private double round(double v) { return Math.round(v*10)/10.0; }
}
