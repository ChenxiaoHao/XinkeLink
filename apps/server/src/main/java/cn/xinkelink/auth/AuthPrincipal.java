package cn.xinkelink.auth;

import java.security.Principal;

public record AuthPrincipal(long subjectId, long sessionId, String actorType) implements Principal {
    @Override public String getName() { return Long.toString(subjectId); }
}
