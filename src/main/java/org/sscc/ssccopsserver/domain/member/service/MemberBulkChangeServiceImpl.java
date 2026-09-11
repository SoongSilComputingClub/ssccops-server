package org.sscc.ssccopsserver.domain.member.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberBulkChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberBulkChangeRow;
import org.sscc.ssccopsserver.domain.member.dto.MemberBulkGradeChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberBulkStatusChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberChangeWarningResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 회원 등급·상태 일괄 변경 (#338).
 *
 * ══ 트랜잭션 경계 — 이 클래스에 @Transactional이 없는 것은 실수가 아니다 ══
 * **경계는 회원 하나마다이고, 그 경계를 여는 것은 이 클래스가 아니라 MemberChangeServiceImpl이다.**
 * 그쪽 두 메서드가 @Transactional(REQUIRED)이고 여기에 진행 중인 트랜잭션이 없으므로, 호출 한
 * 번이 곧 트랜잭션 하나다 — 한 회원이 실패하면 그 회원의 등급 갱신과 이력 INSERT만 함께
 * 되돌아가고, 앞서 성공한 회원들은 이미 커밋돼 있다. 이슈가 요구한 "한 건의 실패가 앞선 성공을
 * 되돌리지 않는다"가 그렇게 성립한다.
 *
 * 반대로 이 메서드에 @Transactional을 걸면 세 가지가 한꺼번에 무너진다.
 *   1. 안쪽 REQUIRED가 **참여**로 바뀌어 트랜잭션이 하나가 된다 — 30번째 회원의 실패가 앞선
 *      29명을 통째로 되돌린다.
 *   2. 예외를 잡아 계속 진행해도 소용없다. 참여 트랜잭션은 rollback-only로 표시되므로 커밋
 *      시점에 UnexpectedRollbackException으로 통째로 되돌아간다.
 *   3. JPA의 영속성 컨텍스트가 예외 시점에 오염돼 이후 flush가 전부 깨진다.
 * 이 사고는 CSV 이관(#85)에서 이미 겪었고 MemberImportRowExecutor 주석에 자세히 적혀 있다.
 *
 * ── 왜 REQUIRES_NEW 실행기 빈을 따로 두지 않았는가 ─────────────────
 * 이관은 행 하나를 넣는 코드가 서비스와 같은 클래스에 있어 **자기 호출로 경계가 사라지는 것**을
 * 막으려고 MemberImportRowExecutor를 별도 빈으로 떼고 REQUIRES_NEW를 걸었다. 여기는 그 조건이
 * 이미 갖춰져 있다 — 부르는 대상이 처음부터 다른 빈(MemberChangeService)이라 호출이 프록시를
 * 지나고, 그 메서드에는 이미 @Transactional이 붙어 있다. 몸통이 위임 한 줄뿐인 빈을 하나 더
 * 만들면 경계는 그대로인데 읽는 사람이 볼 자리만 늘어난다.
 *
 * 대신 규칙 자체를 테스트가 못 박는다 — MemberBulkChangeControllerTest가 이 두 메서드에
 * @Transactional이 붙어 있지 않은지를 리플렉션으로 확인한다. 값이 아니라 **없다는 사실**이
 * 규칙이라, 나중에 누군가 "일괄인데 트랜잭션이 없네"라며 붙이면 그 자리에서 빨개진다.
 *
 * ══ 판정도 이력도 여기서 하지 않는다 ═══════════════════════════════
 * 코드 해석·미래 일자·종료 예정일·NO_CHANGE·이력 INSERT·탈퇴 경고까지 전부 한 명짜리 로직의
 * 것이다. 이 클래스가 하는 일은 대상 목록을 풀고, 예외를 행별 결과로 옮기고, 셋을 세는 것뿐이다.
 * 이력을 남기는 코드가 두 벌이 되면 한쪽만 이력을 남기는 날이 오고, 그날 일괄 수정은 되돌릴 수
 * 없어진다 (#338의 전제).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberBulkChangeServiceImpl implements MemberBulkChangeService {

    /*
     * 예상하지 못한 실패의 사유. 예외 메시지를 그대로 내리지 않는 것은 제약명·SQL 조각 같은 내부
     * 사정이 화면으로 새어 나가기 때문이다 — 원인은 로그에 남긴다 (CSV 이관과 같은 판단).
     */
    private static final String UNEXPECTED_FAILURE_REASON = "변경 도중 오류가 발생했습니다.";

    private final MemberChangeService memberChangeService;

    // 실패·건너뜀 행의 회원명을 채우기 위한 조회. 아래 nameOf 주석 참고
    private final MemberRepository memberRepository;

    @Override
    public MemberBulkChangeResponse changeGrades(
            MemberBulkGradeChangeRequest request, MemberEntity changer) {

        // 대상 전원이 공유하는 값이므로 회원마다 다시 만들지 않는다
        MemberGradeChangeRequest single = request.toSingleRequest();

        return execute(
                request.mbrIds(),
                memberId -> {
                    MemberGradeChangeResponse changed =
                            memberChangeService.changeGrade(memberId, single, changer);
                    return new SingleChangeResult(changed.member(), changed.warnings());
                });
    }

    @Override
    public MemberBulkChangeResponse changeStatuses(
            MemberBulkStatusChangeRequest request, MemberEntity changer) {

        MemberStatusChangeRequest single = request.toSingleRequest();

        return execute(
                request.mbrIds(),
                memberId -> {
                    MemberStatusChangeResponse changed =
                            memberChangeService.changeStatus(memberId, single, changer);
                    return new SingleChangeResult(changed.member(), changed.warnings());
                });
    }

    /*
     * 대상 목록을 순서대로 훑는다. 등급과 상태가 이 메서드를 함께 쓰는 것은 두 흐름에서 다른
     * 부분이 **한 명짜리 서비스를 어느 쪽으로 부르는가** 하나뿐이기 때문이다 — 중복 접기·행별
     * 예외 처리·요약 집계를 두 벌로 두면 한쪽만 고쳐진 규칙이 생긴다.
     *
     * 같은 회원이 두 번 실려 오면 앞의 것만 남긴다. 그대로 두면 두 번째 호출이 NO_CHANGE로
     * 떨어져(방금 바꾼 값이니 당연하다) 같은 사람이 CHANGED 한 줄과 SKIPPED 한 줄로 나뉘어
     * 나오고, 요약의 인원 수가 실제로 손댄 인원과 갈린다. 거절하지 않고 접는 것은 화면의
     * 체크박스가 만든 목록에 중복이 섞이는 것이 실수이지 막아야 할 요청은 아니어서다.
     */
    private MemberBulkChangeResponse execute(List<Long> memberIds, SingleChange change) {
        List<Long> targets = List.copyOf(new LinkedHashSet<>(memberIds));
        List<MemberBulkChangeRow> rows = new ArrayList<>(targets.size());

        for (Long memberId : targets) {
            rows.add(changeOne(memberId, change));
        }
        return MemberBulkChangeResponse.of(rows);
    }

    /*
     * 회원 하나의 변경과 그 결과 옮기기.
     *
     * **예외를 여기서 잡는 것이 안전한 것은 이 메서드에 트랜잭션이 없기 때문이다.** 한 명짜리
     * 서비스가 자기 트랜잭션을 열고 닫으므로, 예외가 여기 닿았을 때는 그 트랜잭션이 이미
     * 되돌아간 뒤이고 오염될 영속성 컨텍스트도 남아 있지 않다 (MemberImportServiceImpl.executeRow와
     * 같은 자리).
     *
     * ── NO_CHANGE만 SKIPPED로 갈라내는 이유 ────────────────────────
     * 나머지 GeneralException(없는 회원 404 · 기준 코드 밖 400 · 미래 일자 400 · 종료 예정일 400)은
     * 전부 **운영자가 무언가를 해야 하는 실패**다. 반면 NO_CHANGE는 요청대로 이미 되어 있다는
     * 뜻이라 할 일이 없다 — 그 하나만 다른 버킷에 넣는 근거는 MemberBulkChangeStatus 주석에 있다.
     * enum 상수 동일성(==)으로 보는 것은 코드 문자열("NO_CHANGE")로 비교하면 다른 도메인이 같은
     * 문자열을 쓰기 시작한 날 조용히 함께 걸리기 때문이다.
     *
     * 예상하지 못한 예외(RuntimeException)까지 잡아 FAILED로 옮기는 것은, 한 회원의 뜻밖의 실패가
     * 요청 전체를 500으로 만들면 **앞서 성공한 회원들의 결과가 응답에 실리지 못하기 때문**이다 —
     * 그들의 변경은 이미 커밋됐으므로 운영자는 무엇이 바뀌었는지 모르는 채로 다시 시도하게 된다.
     */
    private MemberBulkChangeRow changeOne(Long memberId, SingleChange change) {
        try {
            SingleChangeResult result = change.apply(memberId);
            return MemberBulkChangeRow.changed(memberId, result.member().name(), result.warnings());

        } catch (GeneralException ex) {
            ErrorCode errorCode = ex.getErrorCode();
            if (errorCode == MemberErrorCode.NO_CHANGE) {
                return MemberBulkChangeRow.skipped(
                        memberId, nameOf(memberId), errorCode.getCode(), ex.getMessage());
            }
            log.info("회원 {} 일괄 변경이 거절됐습니다: {}", memberId, errorCode.getCode());
            return MemberBulkChangeRow.failed(
                    memberId, nameOf(memberId), errorCode.getCode(), ex.getMessage());

        } catch (RuntimeException ex) {
            log.warn("회원 {} 일괄 변경에 실패했습니다.", memberId, ex);
            return MemberBulkChangeRow.failed(
                    memberId,
                    nameOf(memberId),
                    CommonErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                    UNEXPECTED_FAILURE_REASON);
        }
    }

    /*
     * 바뀌지 않은 회원의 이름. **CHANGED 행은 이 조회를 거치지 않는다** — 한 명짜리 응답이 실어 준
     * 회원 상세에 이미 이름이 있어서다.
     *
     * 조회가 회원마다 한 번씩 나가지만 그 대상은 건너뛴·실패한 회원뿐이고 상한이 100명이라 최악도
     * 100회다. 대상 전체의 이름을 미리 한 번에 읽어 두는 쪽이 질의는 적지만, 그러면 아직 아무것도
     * 하지 않은 시점에 회원 엔티티 100개를 영속성 컨텍스트에 올려 두게 된다 — 그 뒤로 회원마다
     * 트랜잭션이 열리고 닫히는 구조라 그 인스턴스들이 어느 시점에 무엇을 들고 있는지가 읽는
     * 사람에게 보이지 않는다. 결과 표에 이름 한 칸을 채우자고 살 위험이 아니다.
     *
     * 없는 회원이면 null이다 — 이름을 알아낼 곳이 없다 (MemberBulkChangeRow 주석).
     */
    private String nameOf(Long memberId) {
        return memberRepository.findById(memberId).map(MemberEntity::getName).orElse(null);
    }

    /*
     * 한 명짜리 서비스 호출 하나. 등급·상태 응답 record가 서로 다른 타입이라(합치지 않은 근거는
     * MemberStatusChangeResponse 주석에 있다) 공통 흐름이 다룰 수 있게 두 값만 꺼내 옮긴다.
     */
    @FunctionalInterface
    private interface SingleChange {
        SingleChangeResult apply(Long memberId);
    }

    private record SingleChangeResult(
            MemberDetailResponse member, List<MemberChangeWarningResponse> warnings) {}
}
