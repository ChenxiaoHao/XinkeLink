package cn.xinkelink.auth;

import cn.xinkelink.auth.persistence.*;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import static cn.xinkelink.auth.AuthModels.*;

public class AuthService {
    private final AuthMapper mapper;
    private final JwtTokenService jwt;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final String dummyPasswordHash;
    private java.util.function.BiFunction<Long,AuthPrincipal,Object> avatars=(id,viewer)->null;

    public AuthService(AuthMapper mapper, JwtTokenService jwt, PasswordEncoder passwords, Clock clock) {
        this.mapper = mapper;
        this.jwt = jwt;
        this.passwords = passwords;
        this.clock = clock;
        this.dummyPasswordHash = passwords.encode(AuthSecrets.random());
    }
    public AuthService(AuthMapper mapper,JwtTokenService jwt,PasswordEncoder passwords,Clock clock,cn.xinkelink.media.MediaService media) {
        this(mapper,jwt,passwords,clock);this.avatars=(id,viewer)->media.avatars(java.util.List.of(id),viewer).get(id);
    }

    // 微信网络换码在事务外完成；这里只接受受信适配器返回的身份。
    @Transactional
    public UserTokens loginUser(WechatIdentity identity) {
        mapper.upsertWechatUser(identity.appId(), identity.openId());
        UserAccount user = requireUser(mapper.findWechatUser(identity.appId(), identity.openId()));
        mapper.touchUser(user.id(), clock.instant());
        String refresh = AuthSecrets.random();
        Instant expires = clock.instant().plus(Duration.ofDays(30));
        AuthSession session = createSession("user", user.id(), refresh, null, expires);
        return userTokens(mapper.findUser(user.id()), session, refresh);
    }

    @Transactional
    public AdminSession loginAdmin(String account, String password) {
        AdminAccount admin = mapper.findAdminByLogin(account.strip().toLowerCase(Locale.ROOT));
        // 账号不存在也执行同等哈希校验，不用响应差异泄露账号存在性。
        boolean valid = password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, admin == null ? dummyPasswordHash : admin.passwordHash());
        if (!valid || admin == null || !admin.status().equals("active")) throw failure(ErrorCode.INVALID_CREDENTIALS);
        mapper.touchAdmin(admin.id(), clock.instant());
        String refresh = AuthSecrets.random();
        String csrf = AuthSecrets.random();
        AuthSession session = createSession("admin", admin.id(), refresh, csrf, clock.instant().plus(Duration.ofHours(8)));
        return adminTokens(admin, session, refresh, csrf);
    }

    @Transactional
    public UserTokens refreshUser(String refresh) {
        AuthSession session = requireRefresh(refresh, "user");
        UserAccount user = requireUser(mapper.findUser(session.subjectId()));
        String replacement = AuthSecrets.random();
        rotate(session, replacement, null);
        return userTokens(user, session, replacement);
    }

    @Transactional
    public AdminSession refreshAdmin(String refresh, String csrf) {
        AuthSession session = requireRefresh(refresh, "admin");
        requireCsrf(csrf, session);
        AdminAccount admin = requireAdmin(mapper.findAdmin(session.subjectId()));
        String replacement = AuthSecrets.random();
        String newCsrf = AuthSecrets.random();
        rotate(session, replacement, newCsrf);
        return adminTokens(admin, session, replacement, newCsrf);
    }

    @Transactional
    public void logoutUser(AuthPrincipal principal) { mapper.revokeSession(principal.sessionId(), clock.instant()); }

    @Transactional
    public void logoutAdmin(String refresh, String csrf) {
        AuthSession session = requireRefresh(refresh, "admin");
        requireCsrf(csrf, session);
        mapper.revokeSession(session.id(), clock.instant());
    }

    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(Jwt token) {
        long sessionId = Long.parseLong(token.getClaimAsString("sid"));
        long subjectId = Long.parseLong(token.getSubject());
        String actor = token.getClaimAsString("actorType");
        AuthSession session = mapper.findSession(sessionId);
        if (!alive(session) || session.subjectId() != subjectId || !session.subjectType().equals(actor)) {
            throw failure(ErrorCode.UNAUTHENTICATED);
        }
        if (actor.equals("user")) requireUser(mapper.findUser(subjectId));
        else requireAdmin(mapper.findAdmin(subjectId));
        return new AuthPrincipal(subjectId, sessionId, actor);
    }

    @Transactional(readOnly = true)
    public Me currentUser(AuthPrincipal principal) {
        if (!principal.actorType().equals("user")) throw failure(ErrorCode.FORBIDDEN);
        return me(requireUser(mapper.findUser(principal.subjectId())),principal);
    }

    @Transactional(readOnly = true)
    public AdminMe currentAdmin(AuthPrincipal principal) {
        if (!principal.actorType().equals("admin")) throw failure(ErrorCode.FORBIDDEN);
        return adminMe(requireAdmin(mapper.findAdmin(principal.subjectId())));
    }

    private AuthSession createSession(String actor, long id, String refresh, String csrf, Instant expires) {
        byte[] hash = AuthSecrets.hash(refresh);
        mapper.insertSession(actor, id, hash, csrf == null ? null : AuthSecrets.hash(csrf), expires);
        return mapper.findSessionByRefreshHashForUpdate(hash);
    }

    private AuthSession requireRefresh(String refresh, String actor) {
        if (refresh == null || refresh.length() > 128 || refresh.isBlank()) throw failure(ErrorCode.REFRESH_INVALID);
        AuthSession session = mapper.findSessionByRefreshHashForUpdate(AuthSecrets.hash(refresh));
        if (!alive(session) || !session.subjectType().equals(actor)) throw failure(ErrorCode.REFRESH_INVALID);
        return session;
    }

    private void rotate(AuthSession session, String refresh, String csrf) {
        if (mapper.rotateSession(session.id(), session.refreshHash(), AuthSecrets.hash(refresh),
                csrf == null ? null : AuthSecrets.hash(csrf), clock.instant()) != 1) throw failure(ErrorCode.REFRESH_INVALID);
    }

    private void requireCsrf(String csrf, AuthSession session) {
        if (!AuthSecrets.matches(csrf, session.csrfHash())) throw failure(ErrorCode.FORBIDDEN);
    }

    private boolean alive(AuthSession session) {
        // JWT按秒表示到期时间，不在最后不足一秒内轮换出立刻过期的访问令牌。
        return session != null && session.revokedAt() == null
                && session.expiresAt().truncatedTo(ChronoUnit.SECONDS).isAfter(clock.instant());
    }

    private UserAccount requireUser(UserAccount user) {
        if (user == null || user.deletedAt() != null) throw failure(ErrorCode.UNAUTHENTICATED);
        if (user.banState().equals("permanent") || user.banState().equals("temporary")
                && user.banUntil() != null && user.banUntil().isAfter(clock.instant())) throw failure(ErrorCode.ACCOUNT_BANNED);
        return user;
    }

    private AdminAccount requireAdmin(AdminAccount admin) {
        if (admin == null || !admin.status().equals("active") || !admin.role().equals("ADMIN")) throw failure(ErrorCode.UNAUTHENTICATED);
        return admin;
    }

    private UserTokens userTokens(UserAccount user, AuthSession session, String refresh) {
        return new UserTokens(issueAccess("user", user.id(), session), accessSeconds(session),
                refresh, seconds(session), me(user,new AuthPrincipal(user.id(),session.id(),"user")));
    }

    private AdminSession adminTokens(AdminAccount admin, AuthSession session, String refresh, String csrf) {
        return new AdminSession(new AdminTokens(issueAccess("admin", admin.id(), session),
                accessSeconds(session), csrf, adminMe(admin)), refresh, session.expiresAt());
    }

    private String issueAccess(String actor, long id, AuthSession session) {
        try { return jwt.issue(actor, id, session.id(), session.expiresAt()); }
        catch (IllegalArgumentException exception) {
            // 查询和签发之间也可能到期；事务回滚轮换，以认证错误响应而非500。
            if (!session.expiresAt().truncatedTo(ChronoUnit.SECONDS).isAfter(clock.instant())) throw failure(ErrorCode.REFRESH_INVALID);
            throw exception;
        }
    }

    private long seconds(AuthSession session) { return Math.max(0, Duration.between(clock.instant(), session.expiresAt()).getSeconds()); }
    private long accessSeconds(AuthSession session) { return Math.min(JwtTokenService.ACCESS_SECONDS, seconds(session)); }
    private AdminMe adminMe(AdminAccount admin) { return new AdminMe(Long.toString(admin.id()), admin.loginName(), admin.displayName(), admin.role()); }
    private Me me(UserAccount user,AuthPrincipal viewer) {
        Restrictions restrictions = new Restrictions(user.restrictPublish(), user.restrictComment(), user.restrictMessage());
        return new Me(Long.toString(user.id()), user.nickname(), avatars.apply(user.id(),viewer), user.bio(), user.major(), user.className(),
                user.enrollmentYear(), true, restrictions.publish() || restrictions.comment() || restrictions.message()
                ? "restricted" : "active", restrictions, user.version());
    }
    private BusinessException failure(ErrorCode code) { return new BusinessException(code); }
}
