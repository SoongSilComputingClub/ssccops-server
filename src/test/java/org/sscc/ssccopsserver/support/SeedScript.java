package org.sscc.ssccopsserver.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/*
 * data.sql을 테스트에서 한 번 더 실행할 때 쓰는 populator.
 *
 * 문자셋을 여기서 UTF-8로 못 박는 것은 부트가 그렇게 읽기 때문이다(spring.sql.init.encoding).
 * 비워 두면 populator가 JVM 기본 문자셋을 쓰는데, JDK 17에서 그 값은 아직 플랫폼을 따라가므로
 * 한국어 Windows에서는 MS949가 된다 — 그러면 재실행이 넣는 한글 기준값이 부트가 넣어 둔 것과
 * 다른 문자열이 되어, '재실행해도 건수가 그대로인가'를 보는 테스트가 멱등성 위반으로 오해한다.
 * 두 자리가 같은 값을 쓰도록 조립을 한 곳에 둔다.
 */
public final class SeedScript {

    private SeedScript() {}

    public static ResourceDatabasePopulator populator() {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("data.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        return populator;
    }
}
