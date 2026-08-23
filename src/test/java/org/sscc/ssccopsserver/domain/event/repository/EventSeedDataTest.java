package org.sscc.ssccopsserver.domain.event.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;

/*
 * data.sql이 넣는 행사 기준 데이터 검증 (ssccops#134 · CodeSeedDataTest와 같은 태도).
 *
 * 기대값을 enum이 아니라 문자열 리터럴로 적는다 — 코드값은 데이터사전 표준코드 시트와 맞춘
 * 계약이라, 서버 상수가 바뀌었을 때 테스트가 따라 바뀌어 조용히 통과하면 안 된다.
 */
@DataJpaTest
@ActiveProfiles("test")
class EventSeedDataTest {

    @Autowired private DataSource dataSource;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AuthorityRepository authorityRepository;

    // 시드 초기값(D13). 표시 순번까지 데이터사전 표준코드 시트 그대로다
    @Test
    void seedsEveryEventClassificationInDictionaryOrder() {
        assertThat(eventClassificationRepository.findAllByOrderByDisplayOrderAsc())
                .extracting(EventClassificationEntity::getCode, EventClassificationEntity::getName)
                .containsExactly(
                        tuple("RECRUIT", "모집"),
                        tuple("SEMINAR", "세미나"),
                        tuple("PROJECT", "프로젝트"),
                        tuple("EVENT", "행사"));
    }

    /*
     * EVENT_MANAGE는 OPERATOR의 자식이어야 한다(D8). 이 간선이 곧 "국장 이상 + SUPER 자동
     * 보유"의 전부다 — 역할 매핑을 따로 시드하지 않으므로, 부모가 끊기면 행사 관리는 아무도
     * 못 하는 채로 배포된다. 코드(@RequireAuthority)가 가리키는 권한이라 sys_yn도 TRUE여야
     * 화면 조작으로 삭제되지 않는다.
     */
    @Test
    void seedsEventManageUnderOperator() {
        assertThat(authorityRepository.findById("EVENT_MANAGE"))
                .get()
                .satisfies(
                        authority -> {
                            assertThat(authority.getParent().getCode()).isEqualTo("OPERATOR");
                            assertThat(authority.isSystemDefined()).isTrue();
                        });
    }

    // WHERE NOT EXISTS가 빠진 INSERT가 섞이면 여기서 잡힌다 — CodeSeedDataTest와 같은 재실행 검증
    @Test
    void reRunningTheSeedScriptChangesNothing() {
        long classifications = eventClassificationRepository.count();
        long authorities = authorityRepository.count();

        new ResourceDatabasePopulator(new ClassPathResource("data.sql")).execute(dataSource);

        assertThat(eventClassificationRepository.count()).isEqualTo(classifications);
        assertThat(authorityRepository.count()).isEqualTo(authorities);
    }
}
