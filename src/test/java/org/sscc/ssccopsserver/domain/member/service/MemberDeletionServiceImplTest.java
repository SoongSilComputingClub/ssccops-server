package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.repository.MemberDeletionQueryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberReferenceConstraints;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditLog;

/*
 * 플래그가 꺼진 회원 하드 삭제 (#361 · ADR-0021).
 *
 * 컨텍스트 없이 생성자에 false를 넣어 본다 — 공용 테스트 컨텍스트는 application-test.yaml이
 * 켜 두었고(그 이유는 그 파일에), 끄려고 컨텍스트를 하나 더 띄우는 것은 판정 한 줄에 비해
 * 비싸다. 여기서 못 박는 것은 셋이다: 꺼져 있으면 **404이되 코드가 FEATURE_DISABLED**(없는
 * 회원의 NOT_FOUND와 다르다), 미리보기도 같은 코드, 그리고 **DB에 아무것도 묻지 않는다** —
 * 존재 검사가 플래그 검사보다 앞이면 꺼진 기능이 회원의 존재 여부를 코드로 새게 한다.
 *
 * 번역 표의 매칭 규칙도 여기서 본다 — PostgreSQL(소문자 제약 이름)과 H2(대문자 해시 · 손으로
 * 지은 이름은 다른 해시) 두 문구 모두에서 같은 표기가 나와야 한다.
 */
@ExtendWith(MockitoExtension.class)
class MemberDeletionServiceImplTest {

    @Mock private MemberRepository memberRepository;
    @Mock private MemberDeletionQueryRepository deletionQueryRepository;

    @Test
    void deleteIs404FeatureDisabledWhenFlagIsOff() {
        MemberDeletionServiceImpl service =
                new MemberDeletionServiceImpl(
                        memberRepository, deletionQueryRepository, false, new AuditLog());

        assertThatThrownBy(() -> service.delete(1L, 2L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(MemberErrorCode.FEATURE_DISABLED);

        verifyNoInteractions(memberRepository, deletionQueryRepository);
    }

    @Test
    void previewIs404FeatureDisabledWhenFlagIsOff() {
        MemberDeletionServiceImpl service =
                new MemberDeletionServiceImpl(
                        memberRepository, deletionQueryRepository, false, new AuditLog());

        assertThatThrownBy(() -> service.preview(1L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(MemberErrorCode.FEATURE_DISABLED);

        verifyNoInteractions(memberRepository, deletionQueryRepository);
    }

    /** 플래그가 켜져 있어도 본인은 400이고, 그 판정은 존재 검사보다 앞이라 DB를 보지 않는다 */
    @Test
    void deletingSelfIs400BeforeTouchingTheDatabase() {
        MemberDeletionServiceImpl service =
                new MemberDeletionServiceImpl(
                        memberRepository, deletionQueryRepository, true, new AuditLog());

        assertThatThrownBy(() -> service.delete(7L, 7L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(MemberErrorCode.CANNOT_DELETE_SELF);

        verifyNoInteractions(memberRepository, deletionQueryRepository);
    }

    /** PostgreSQL 문구 — 제약 이름이 소문자로 실린다 */
    @Test
    void resolvesPostgresConstraintName() {
        RuntimeException ex =
                new RuntimeException(
                        "could not execute statement",
                        new RuntimeException(
                                "ERROR: update or delete on table \"mbr\" violates foreign key"
                                        + " constraint \"fknn5rhjtcayw32pk718rr8hugc\" on table"
                                        + " \"form\""));

        assertThat(MemberReferenceConstraints.resolve(ex))
                .map(MemberReferenceConstraints.Reference::label)
                .contains("폼 작성자");
    }

    /** H2 문구 — 해시가 대문자이고, 손으로 지은 V5 이름은 해시가 달라 테이블·컬럼으로 찾는다 */
    @Test
    void resolvesH2ConstraintByHashOrByTableAndColumn() {
        RuntimeException hashed =
                new RuntimeException(
                        "Referential integrity constraint violation: \"FKQ5CQ3CKDJLSS8D3T1SCVDC0MQ:"
                                + " PUBLIC.EVENT FOREIGN KEY(CREATR_MBR_ID) REFERENCES"
                                + " PUBLIC.MBR(MBR_ID) (CAST(3 AS BIGINT))\"");
        RuntimeException named =
                new RuntimeException(
                        "Referential integrity constraint violation: \"FK1ABCDEFGHIJKLMNOPQRSTUVW:"
                            + " PUBLIC.SUB_WORK_CHCK_LIST_HSTRY FOREIGN KEY(PRFMR_ID) REFERENCES"
                            + " PUBLIC.MBR(MBR_ID) (CAST(3 AS BIGINT))\"");

        assertThat(MemberReferenceConstraints.resolve(hashed))
                .map(MemberReferenceConstraints.Reference::label)
                .contains("행사 작성자");
        assertThat(MemberReferenceConstraints.resolve(named))
                .map(MemberReferenceConstraints.Reference::label)
                .contains("하위 업무 점검 목록 변경자");
    }

    @Test
    void unknownConstraintResolvesToNothing() {
        assertThat(MemberReferenceConstraints.resolve(new RuntimeException("unrelated"))).isEmpty();
    }

    /** 표의 제약 이름은 서로 달라야 한다 — 미리보기 질의가 이름을 키로 쓴다 */
    @Test
    void blockingTableHasDistinctConstraintNamesAndLabels() {
        assertThat(MemberReferenceConstraints.BLOCKING)
                .extracting(MemberReferenceConstraints.Reference::constraintName)
                .doesNotHaveDuplicates();
        assertThat(MemberReferenceConstraints.BLOCKING)
                .extracting(MemberReferenceConstraints.Reference::label)
                .doesNotHaveDuplicates();
        assertThat(MemberReferenceConstraints.BLOCKING).hasSize(21);
    }
}
