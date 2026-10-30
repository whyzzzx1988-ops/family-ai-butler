package com.familybutler.core.config;

import com.familybutler.core.account.AccountService;
import com.familybutler.core.account.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅 local Profile 生效：启动时自动初始化一套固定演示家庭，省掉 H2 内存库
 * 每次重启后手动调接口建家庭/加成员的重复劳动。
 * 内容与 README「账号与家庭自检」的演示数据一致：户主 yuge / 配偶 xifu
 * （密码同为 dev123456）、孩子只建成员不给账号；值可在 application-local.yml
 * 的 butler.seed 下改。幂等：登录名已存在就整体跳过，不覆盖已有数据。
 * 密码只经 AccountService 落 BCrypt 哈希；日志只打登录名，绝不打密码。
 * prod Profile 不加载本类，种子账号不会出现在正式环境。
 */
@Component
@Profile("local")
public class DevSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedInitializer.class);

    private final AccountService accountService;
    private final String familyName;
    private final String ownerNickname;
    private final String identifier;
    private final String password;
    private final String spouseNickname;
    private final String spouseIdentifier;
    private final String childNickname;

    public DevSeedInitializer(AccountService accountService,
                              @Value("${butler.seed.family-name:我的家}") String familyName,
                              @Value("${butler.seed.owner-nickname:宇哥}") String ownerNickname,
                              @Value("${butler.seed.identifier:yuge}") String identifier,
                              @Value("${butler.seed.password:dev123456}") String password,
                              @Value("${butler.seed.spouse-nickname:媳妇}") String spouseNickname,
                              @Value("${butler.seed.spouse-identifier:xifu}") String spouseIdentifier,
                              @Value("${butler.seed.child-nickname:儿子}") String childNickname) {
        this.accountService = accountService;
        this.familyName = familyName;
        this.ownerNickname = ownerNickname;
        this.identifier = identifier;
        this.password = password;
        this.spouseNickname = spouseNickname;
        this.spouseIdentifier = spouseIdentifier;
        this.childNickname = childNickname;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            accountService.loginByIdentifier(identifier);
            log.info("种子账号 {} 已存在，跳过初始化", identifier);
            return;
        } catch (ApiException e) {
            // 登录名不存在才继续建种子
        }
        try {
            AccountService.MemberView owner =
                    accountService.createFamily(familyName, ownerNickname, identifier, password);
            accountService.addMember(owner.familyId(), spouseNickname, "spouse", spouseIdentifier, password);
            accountService.addMember(owner.familyId(), childNickname, "child", null, null);
            log.info("种子家庭已初始化：户主 {} / 配偶 {}（密码见 application-local.yml 的 butler.seed），孩子成员「{}」无账号",
                    identifier, spouseIdentifier, childNickname);
        } catch (RuntimeException e) {
            // 种子只是本地调试便利，失败只记日志、不挡启动
            log.error("种子家庭初始化失败（不影响启动）：{}", e.getMessage());
        }
    }
}
