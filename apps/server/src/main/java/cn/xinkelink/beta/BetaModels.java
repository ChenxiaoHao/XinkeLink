package cn.xinkelink.beta;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

public final class BetaModels {
    private BetaModels(){}
    public record Access(String phase,boolean authenticated,boolean activated){}
    public record Redeem(String code){@Override public String toString(){return "Redeem[redacted]";}}
    public record ChangePhase(@NotNull @Pattern(regexp="beta|open") String phase,@NotNull @Min(0) Long expectedVersion){}
    public record User(String id,String nickname){}
    public record Code(String id,String code,String status,User user,Instant usedAt,Instant createdAt){
        @Override public String toString(){return "Code[id="+id+",status="+status+"]";}
    }
    public record Management(String phase,long version,int total,int used,int remaining,List<Code> codes){
        @Override public String toString(){return "Management[phase="+phase+",total="+total+",used="+used+"]";}
    }
}
