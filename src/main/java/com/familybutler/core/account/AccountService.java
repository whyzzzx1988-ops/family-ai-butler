package com.familybutler.core.account;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

/**
 * 账号与家庭空间的业务层（D3 落地）：建家庭、加成员、密码登录校验。
 *
 * 隔离原则（全项目通用）：家庭边界只认登录态反查出的 family_id，
 * 任何接口都不信任前端传来的 familyId —— 跨家庭的数据在这里就查不到。
 * 密码只存 BCrypt 哈希（spring-security-crypto），明文不落库、不进日志。
 */
@Service
public class AccountService {

    /** 成员角色：本人 / 配偶 / 孩子 / 长辈，和设计文档一致，不做菜单式 RBAC。 */
    public static final Set<String> ROLES = Set.of("self", "spouse", "child", "elder");

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MemberView(long id, long familyId, String role, String nickname, String avatar) {
    }

    public record FamilyView(long id, String name, String plan) {
    }

    public record LoginResult(long memberId, long familyId) {
    }

    /** 建家庭：同时创建家庭、户主成员（role=self）与其密码账号，返回户主成员。 */
    @Transactional
    public MemberView createFamily(String familyName, String ownerNickname, String identifier, String rawPassword) {
        ensureIdentifierFree(identifier);
        long familyId = insert("insert into family(name) values (?)", familyName);
        long memberId = insert("insert into member(family_id, role, nickname) values (?, 'self', ?)",
                familyId, ownerNickname);
        insertAccount(memberId, identifier, rawPassword);
        return new MemberView(memberId, familyId, "self", ownerNickname, null);
    }

    /**
     * 给指定家庭加成员。familyId 只能由调用方从登录态反查后传入（见 FamilyController），
     * 不接受前端直传。可选带登录账号：孩子/长辈可以先只建成员、暂不给账号。
     */
    @Transactional
    public MemberView addMember(long familyId, String nickname, String role, String identifier, String rawPassword) {
        if (!ROLES.contains(role)) {
            throw ApiException.badRequest("role 只能是 self / spouse / child / elder");
        }
        boolean withAccount = identifier != null && !identifier.isBlank();
        if (withAccount) {
            if (rawPassword == null || rawPassword.length() < 6) {
                throw ApiException.badRequest("带账号的成员密码至少 6 位");
            }
            ensureIdentifierFree(identifier);
        }
        long memberId = insert("insert into member(family_id, role, nickname) values (?, ?, ?)",
                familyId, role, nickname);
        if (withAccount) {
            insertAccount(memberId, identifier, rawPassword);
        }
        return new MemberView(memberId, familyId, role, nickname, null);
    }

    private record CredentialRow(long memberId, long familyId, String credentialHash) {
    }

    /** 密码登录校验：按 identifier 查账号，BCrypt 比对；失败统一报「用户名或密码不正确」，不泄露哪一项错。 */
    public LoginResult verifyPasswordLogin(String identifier, String rawPassword) {
        List<CredentialRow> rows = jdbc.query(
                """
                select a.member_id as member_id, a.credential_hash as credential_hash, m.family_id as family_id
                from account a join member m on m.id = a.member_id
                where a.login_type = 'password' and a.identifier = ?
                """,
                (rs, i) -> new CredentialRow(rs.getLong("member_id"), rs.getLong("family_id"),
                        rs.getString("credential_hash")),
                identifier);
        if (rows.isEmpty() || rows.get(0).credentialHash() == null
                || !passwordEncoder.matches(rawPassword, rows.get(0).credentialHash())) {
            throw ApiException.unauthorized("用户名或密码不正确");
        }
        return new LoginResult(rows.get(0).memberId(), rows.get(0).familyId());
    }

    public MemberView getMember(long memberId) {
        List<MemberView> rows = jdbc.query(
                "select id, family_id, role, nickname, avatar from member where id = ?",
                (rs, i) -> new MemberView(rs.getLong("id"), rs.getLong("family_id"),
                        rs.getString("role"), rs.getString("nickname"), rs.getString("avatar")),
                memberId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "登录成员不存在");
        }
        return rows.get(0);
    }

    public FamilyView getFamily(long familyId) {
        List<FamilyView> rows = jdbc.query(
                "select id, name, plan from family where id = ?",
                (rs, i) -> new FamilyView(rs.getLong("id"), rs.getString("name"), rs.getString("plan")),
                familyId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("家庭不存在");
        }
        return rows.get(0);
    }

    /** 只查本家庭的成员列表 —— family_id 是唯一的过滤边界。 */
    public List<MemberView> listMembers(long familyId) {
        return jdbc.query(
                "select id, family_id, role, nickname, avatar from member where family_id = ? order by id",
                (rs, i) -> new MemberView(rs.getLong("id"), rs.getLong("family_id"),
                        rs.getString("role"), rs.getString("nickname"), rs.getString("avatar")),
                familyId);
    }

    public String roleOf(long memberId) {
        List<String> roles = jdbc.query(
                "select role from member where id = ?",
                (rs, i) -> rs.getString(1),
                memberId);
        return roles.isEmpty() ? null : roles.get(0);
    }

    private void ensureIdentifierFree(String identifier) {
        Integer count = jdbc.queryForObject(
                "select count(*) from account where login_type = 'password' and identifier = ?",
                Integer.class, identifier);
        if (count != null && count > 0) {
            throw ApiException.conflict("该登录名已被使用");
        }
    }

    private void insertAccount(long memberId, String identifier, String rawPassword) {
        jdbc.update("insert into account(member_id, login_type, identifier, credential_hash) values (?, 'password', ?, ?)",
                memberId, identifier, passwordEncoder.encode(rawPassword));
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回主键: " + sql);
        }
        return key.longValue();
    }
}
