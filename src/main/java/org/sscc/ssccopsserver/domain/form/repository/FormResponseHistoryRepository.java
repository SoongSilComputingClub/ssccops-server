package org.sscc.ssccopsserver.domain.form.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 폼 응답 조회. 응답 제출(#35)·자동 저장(#36)·조회 및 상태 변경(#37)이 쓸 시그니처를 잡아 둔다.
 */
public interface FormResponseHistoryRepository
        extends JpaRepository<FormResponseHistoryEntity, Long> {

    /*
     * "내가 이 폼에 낸 응답" 전부 (#143 · 응답 순번 오름차순).
     *
     * **원래 Optional을 돌려주는 findByFormAndMember였다.** 다중 응답이 열리면서 그 단건 전제가
     * 깨졌고, 시그니처를 그대로 두면 두 번째 응답이 있는 회원의 조회가 조용히 예외
     * (IncorrectResultSizeDataAccessException)로 떨어진다 — 이름을 바꾸는 편이 호출부를 전부
     * 다시 보게 만든다(제출 중복 판정 · 공개 폼의 alreadySubmitted · 내 응답 목록).
     *
     * 정렬 기준이 순번인 것은 그것이 "몇 번째로 시작한 응답인가"이기 때문이다. 제출 일시로
     * 정렬하면 아직 내지 않은 초안이 NULL로 끝이나 처음에 몰린다.
     */
    List<FormResponseHistoryEntity> findAllByFormAndMemberOrderByResponseSequenceAsc(
            FormEntity form, MemberEntity member);

    /*
     * 작성 중인 내 응답 (#36 · #143). 초안은 폼 종류와 무관하게 언제나 최대 1건이라 Optional이다 —
     * 그 사실을 지키는 것은 부분 유니크 인덱스(PostgreSQL)와 saveDraft의 판정 두 겹이며,
     * 자동 저장 API(GET·PUT .../responses/draft)의 단건 계약이 여기에 얹혀 있다.
     */
    Optional<FormResponseHistoryEntity> findByFormAndMemberAndStatus(
            FormEntity form, MemberEntity member, ResponseStatus status);

    /*
     * 새 초안을 시작할 수 있는가의 판단 근거 (#143 · #192).
     *
     * **원래 상태를 보지 않는 existsByFormAndMember였다.** 초안이 없음을 이미 확인한 뒤라 남은
     * 것은 정의상 제출 이상이라는 논리였는데, 그 "제출 이상"에 반려가 들어 있어 반려된 응답자가
     * 새 초안조차 만들지 못했다 — 반려는 그 응답에 대한 종결이지 그 폼에 대한 종결이 아니다.
     * 그래서 막는 상태를 호출부가 명시해 넘긴다(ResponseStatus.blockingNewResponse).
     *
     * 상태 집합을 여기 적어 굳히지 않는 것은 이 리포지토리의 다른 질의와 같다 — 기준이 두 벌이
     * 되면 갈린다.
     */
    boolean existsByFormAndMemberAndStatusIn(
            FormEntity form, MemberEntity member, Collection<ResponseStatus> statuses);

    /*
     * 이 회원이 이 폼에서 마지막으로 쓴 응답 순번 (#143). 다음 응답은 이 값 + 1로 시작한다.
     *
     * count(*)로 세지 않는 것은 응답이 지워진 적이 있으면 이미 쓴 번호를 다시 배정하기 때문이다 —
     * 그 순간 UNIQUE 위반이 나고, 사용자에게는 "왜인지 두 번째 제안이 안 된다"로 보인다.
     * 행이 없으면 max가 NULL이므로 coalesce로 0을 돌려준다(첫 응답이 1이 된다).
     */
    @Query(
            "select coalesce(max(r.responseSequence), 0) from FormResponseHistoryEntity r"
                    + " where r.form = :form and r.member = :member")
    int findLastResponseSequence(
            @Param("form") FormEntity form, @Param("member") MemberEntity member);

    /*
     * "내가 행사에 낸 신청" 전부 (ssccops#145 · GET /v1/events/my-applications).
     *
     * 행사에 연결된 폼의 응답만 고른다 — 폼 응답 전부를 끌어와 서비스에서 거르면 행사와 무관한
     * 지원서·설문 응답까지 메모리로 올라오고, 그 수는 회원이 오래 활동할수록 는다. 폼은 행사에
     * 전속(uk_event_form)이라 exists 하나로 끝난다.
     *
     * 상태 집합을 호출부가 넘기는 것은 이 리포지토리의 다른 질의와 같다 — 내 신청 조회는
     * ResponseStatus.submittedOrLater()를 넘겨 DRAFT를 뺀다(제출 전 초안은 신청이 아니다).
     * 같은 EnumSet을 여기 적어 굳히면 그 기준이 두 벌이 된다.
     *
     * 폼을 함께 페치하는 것은 호출부가 form_id로 행사를 짝지어야 하기 때문이다 — LAZY 프록시의
     * 식별자 접근에 기대면 매핑을 조금만 손대도 조용히 N+1로 되돌아간다 (findAllForOperatorList
     * 주석과 같은 자리).
     *
     * 정렬은 '최신 신청 순'(제출 일시 내림차순)이고 동률은 식별자로 끊는다 — DRAFT가 빠져
     * sbmsn_dt가 언제나 있으므로 운영자 목록과 달리 coalesce가 필요 없다.
     *
     * **지워진 행사에 붙은 폼의 응답은 신청이 아니다** (#347 · `e.deletedAt is null`). 이 조건은
     * EventRepository.findAllByFormIdIn의 필터와 짝이다 — 여기서만 빼면 응답은 오는데 짝지을
     * 행사가 없어 호출부가 NPE로 500이 되고, 저기서만 빼면 지운 행사의 신청이 그대로 보인다.
     * 그 응답은 아래 findNonEventResponsesByMember로 옮겨 가 '내 폼 응답'에 선다 — 행사에서
     * 폼 연결을 풀었을 때(#336)와 같은 결과이며, 응답 자체는 어디에도 지워지지 않는다.
     */
    @Query(
            "select r from FormResponseHistoryEntity r join fetch r.form f"
                    + " where r.member = :member and r.status in :statuses"
                    + " and exists (select e.id from EventEntity e"
                    + "   where e.form = f and e.deletedAt is null)"
                    + " order by r.submittedAt desc, r.id desc")
    List<FormResponseHistoryEntity> findEventApplicationsByMember(
            @Param("member") MemberEntity member,
            @Param("statuses") Collection<ResponseStatus> statuses);

    /*
     * "내가 폼에 낸 응답" 전부 (ssccops#221 · GET /v1/forms/responses/mine).
     *
     * **행사에 붙은 폼을 뺀다.** 조건이 findEventApplicationsByMember의 정확히 반대이고, 그
     * 이유는 두 목록이 같은 화면에 함께 놓이기 때문이다 — 행사 신청도 폼 응답이라 거르지 않으면
     * 같은 응답이 '내 신청'에 두 줄로 보인다. 웹이 formRspnsId로 걸러 낼 수는 있지만 그러면
     * "무엇이 신청이고 무엇이 폼 응답인가"의 판정이 화면에 한 벌 더 생긴다.
     *
     * **상태를 가리지 않아 DRAFT도 온다.** 폼별 내 응답 목록(#143)과 같은 기준이며 근거도 같다 —
     * 운영자 목록이 DRAFT를 빼는 규칙은 "남의 제출 전 답안이 심사 목록에 섞이지 않게"이고, 내
     * 것을 나에게 숨길 이유는 없다. 행사 신청 조회가 상태 집합을 받아 DRAFT를 빼는 것과 갈리는데
     * 그쪽은 "제출 전 초안은 신청이 아니다"라는 신청의 정의 문제이고, 이쪽은 "이어서 쓸 것이
     * 있다"를 응답자에게 보여 주는 자리다.
     *
     * 폼을 함께 페치하는 것은 호출부가 폼 제목과 sys_form_cd(대표 문항 판정)를 읽기 때문이다.
     * 라벨은 여기서 끌어오지 않는다 — 폼에 라벨 컬렉션 연관이 없어 별도 조회(findAllByFormIdIn)로
     * 한 번에 모은다.
     *
     * 정렬은 '마지막으로 움직인 순'이다. DRAFT가 섞여 sbmsn_dt가 NULL일 수 있으므로 mdfcn_dt로
     * 폴백한다 — NULL을 그대로 태우면 DB에 따라 맨 앞이나 맨 뒤로 몰려 방금 저장한 초안이 어디
     * 있는지 알 수 없다 (findAllForOperatorList와 같은 자리).
     *
     * **지워진 폼의 응답은 빠진다** (#329 · f.deletedAt is null). 상세가 404가 되는 것만으로는
     * 부족하다 — 목록에 줄이 남아 있으면 응답자가 그것을 눌렀을 때 갈 곳이 없고, 그 화면은
     * 폼에서 제목·문항을 가져온다(ssccops#261 결정 코멘트가 "목록에서 사라지는 것과 상세가
     * 404가 되는 것은 다르다"고 못 박은 자리다). 되살리면 그 줄이 그대로 돌아온다.
     *
     * 행사 신청(findEventApplicationsByMember)에는 같은 조건을 걸지 않는다. 그쪽 목록의 축은
     * 폼이 아니라 event이고 폼을 지운다고 행사가 지워지지 않아, 그 화면은 폼 없이도 선다.
     *
     * 반대로 **지워진 행사(#347)는 여기서도 없는 행사다** — not exists가 살아 있는 행사만 보므로
     * 지운 행사의 신청 응답은 '내 신청'에서 빠지는 대신 이 목록으로 온다. 두 목록의 경계가
     * "살아 있는 행사가 붙은 폼인가" 하나로 유지되어야 같은 응답이 두 줄이 되거나 어디에도
     * 없는 일이 없다.
     */
    @Query(
            "select r from FormResponseHistoryEntity r join fetch r.form f"
                    + " where r.member = :member"
                    + " and f.deletedAt is null"
                    + " and not exists (select e.id from EventEntity e"
                    + "   where e.form = f and e.deletedAt is null)"
                    + " order by coalesce(r.submittedAt, r.updatedAt) desc, r.id desc")
    List<FormResponseHistoryEntity> findNonEventResponsesByMember(
            @Param("member") MemberEntity member);

    /*
     * 문항 식별자 보호(#32 수정)의 판단 근거. 상태를 가리지 않고 한 건이라도 있으면 참이다 —
     * 임시저장(DRAFT) 응답의 rspns_cn도 key가 qitemId라, 제출 전이라고 해서 문항을 지워도
     * 되는 것은 아니다. 목록의 responseCount가 DRAFT를 빼는 것과는 판단 기준이 다르다.
     */
    boolean existsByForm(FormEntity form);

    /*
     * 폼별 응답 목록(#37). 운영자용 목록 표가 회원_명·학번·학과·등급·상태를 그리므로 회원과
     * 그 등급·상태 기준 코드까지 한 번에 끌어온다 — 응답마다 mbr을 따로 조회하면 그대로 N+1이고
     * (DB-13), 모집 폼은 응답이 수백 건이라 그 배수가 그대로 쿼리 수가 된다.
     *
     * 등급·상태까지 그래프에 넣는 것은 지연 로딩 프록시의 식별자 접근에 기대지 않기 위해서다.
     * mbr_grd_cd·mbr_stts_cd는 응답에 실을 값이 코드(= 식별자) 하나뿐이라 프록시를 초기화하지
     * 않고도 읽히는 것이 보통이지만, 그 최적화가 도는지 여부에 쿼리 수가 걸려 있으면 매핑을
     * 조금만 손대도 조용히 N+1로 되돌아간다.
     *
     * 상태 필터가 선택 사항이라 FormRepository와 같은 이유로 상태 집합을 받는다 (열거형
     * 파라미터에 NULL을 넣고 분기하면 Hibernate가 타입을 추론하지 못한다).
     *
     * 정렬은 '제출 일시 내림차순'이되 DRAFT는 sbmsn_dt가 NULL이므로 mdfcn_dt로 폴백한다 —
     * NULL을 그대로 정렬에 태우면 DB에 따라 맨 앞이나 맨 뒤로 몰려 '작성 중' 응답만 따로 볼 때
     * 최근 저장한 것이 어디 있는지 알 수 없다. 동시각 동률은 식별자로 끊어 페이지를 다시 열어도
     * 순서가 흔들리지 않게 한다 — 상세의 이전/다음 이동이 이 순서를 그대로 쓴다.
     *
     * 페이징을 두지 않는다. 근거는 FormResponseServiceImpl.getResponses 주석에 있다.
     */
    @EntityGraph(attributePaths = {"member", "member.membershipGrade", "member.membershipStatus"})
    @Query(
            "select r from FormResponseHistoryEntity r"
                    + " where r.form = :form and r.status in :statuses"
                    + " order by coalesce(r.submittedAt, r.updatedAt) desc, r.id desc")
    List<FormResponseHistoryEntity> findAllForOperatorList(
            @Param("form") FormEntity form, @Param("statuses") Collection<ResponseStatus> statuses);

    /*
     * 위 목록의 식별자만. 상세의 이전/다음 이동(#37)이 인접 응답을 고를 때 쓴다.
     *
     * 같은 조건·같은 정렬을 두 번 적는 대신 엔티티 목록을 그대로 다시 부르지 않는 것은, 상세
     * 화면 한 번에 폼의 모든 응답과 그 회원을 전부 적재하게 되기 때문이다. 이동에 필요한 것은
     * 앞뒤 식별자 두 개뿐이다.
     */
    @Query(
            "select r.id from FormResponseHistoryEntity r"
                    + " where r.form = :form and r.status in :statuses"
                    + " order by coalesce(r.submittedAt, r.updatedAt) desc, r.id desc")
    List<Long> findIdsForOperatorList(
            @Param("form") FormEntity form, @Param("statuses") Collection<ResponseStatus> statuses);

    /*
     * 폼별·상태별 응답 건수 일괄 집계 (#32 폼 목록 · #37 폼 상세의 응답 요약).
     *
     * 상태를 GROUP BY에 넣은 것은 #37에서다. 폼 상세가 전체·제출·승인·반려 네 숫자를 보여주는데,
     * 총합용 질의와 상태별 질의를 따로 두면 폼 목록이 폼마다 두 번씩 집계하게 되고 두 결과가
     * 어긋날 여지도 생긴다. 호출부가 필요한 만큼 접어 쓴다 — 목록은 합으로, 상세는 그대로.
     *
     * 어떤 상태를 셀지는 여전히 호출부가 정한다(임시저장 제외 여부가 갈린다).
     *
     * 폼 식별자를 직접 꺼내는 것은(f.id) 연관을 타면 프로젝션 이름이 form.id가 되어
     * getFormId()와 맞지 않기 때문이다.
     */
    @Query(
            "select f.id as formId, r.status as status, count(r) as responseCount"
                    + " from FormResponseHistoryEntity r join r.form f"
                    + " where f.id in :formIds and r.status in :statuses"
                    + " group by f.id, r.status")
    List<FormResponseCount> countByFormIds(
            @Param("formIds") Collection<Long> formIds,
            @Param("statuses") Collection<ResponseStatus> statuses);

    /*
     * 응답 단건 조회 (#37). **폼과 응답 식별자를 반드시 함께 건다.**
     *
     * 경로에 두 값이 다 있는데 응답 식별자만으로 조회하면 /v1/forms/1/responses/999가 다른 폼의
     * 응답을 그대로 돌려준다 — 지원자 답변과 개인정보가 폼 경계를 넘어 새어 나가는 데 그
     * 한 줄이면 충분하다. 없는 응답과 남의 폼 응답은 여기서 같은 빈 결과가 된다.
     */
    @EntityGraph(attributePaths = {"member", "member.membershipGrade", "member.membershipStatus"})
    Optional<FormResponseHistoryEntity> findByIdAndForm(Long id, FormEntity form);

    /*
     * 제출자용 본인 응답 단건 조회 (#177 · GET .../responses/mine/{formRspnsId}).
     *
     * 운영자용(findByIdAndForm)에 **응답자 조건을 하나 더 건다.** 폼 범위만으로 찾으면 제출자가
     * 남의 응답 식별자를 대입하는 것으로 그 답과 검토 사유를 통째로 읽는다 — 폼 범위 검사가 폼
     * 경계를 넘는 것을 막듯, 여기서는 회원 경계를 넘는 것을 막는 조건이다. 세 값을 함께 걸어
     * 없는 응답과 남의 응답이 같은 빈 결과가 되게 한다(코드를 나누면 그 응답이 존재하는지가
     * 새어 나간다).
     *
     * 엔티티 그래프를 걸지 않는 것은 이 조회가 회원 정보를 응답에 싣지 않기 때문이다
     * (MyFormResponseDetailResponse) — 조건에 쓰는 회원은 이미 인증 주체로 손에 있다.
     */
    Optional<FormResponseHistoryEntity> findByIdAndFormAndMember(
            Long id, FormEntity form, MemberEntity member);

    /*
     * 첨부를 바꾸는 작성자 경로의 응답 조회 — **행을 잠근다** (server#685). 조건은 위
     * findByIdAndFormAndMember와 같아 남의 응답은 같은 빈 결과(404)다.
     *
     * 잠그는 이유는 개수(5)·합계(20MB) 상한이 DB 제약으로 걸 수 없는 규칙이라서다. 잠그지 않으면 동시
     * 발급 둘이 각자 «남은 용량 15MB»를 보고 함께 통과해 합계가 30MB가 된다. 잠그는 대상이 file_rfrnc가
     * 아니라 응답 행인 것은 회차 인증사진이 sesn 행을 잠그는 것(SessionRepository.lockById)과 같은
     * 이유다 — 첨부가 아직 없는 응답에는 잠글 file_rfrnc 행이 없다. 덤으로 제출(상태 UPDATE)과도 줄을
     * 선다: 잠근 뒤 읽은 상태로 판정하므로 «판정은 DRAFT로 봤는데 그사이 제출됐다»가 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select r from FormResponseHistoryEntity r"
                    + " where r.id = :id and r.form = :form and r.member = :member")
    Optional<FormResponseHistoryEntity> lockByIdAndFormAndMember(
            @Param("id") Long id,
            @Param("form") FormEntity form,
            @Param("member") MemberEntity member);

    /*
     * 검토자 경로의 첨부 조회가 «그 폼의 응답인가»만 묻는 자리 (server#685). findByIdAndForm은 목록 표를
     * 위해 회원·등급·상태를 함께 끌어오는데 첨부 목록에는 그중 아무것도 필요 없다.
     */
    boolean existsByIdAndForm(Long id, FormEntity form);

    /*
     * 접수가 끝난 폼에 남은 미제출 초안을 걷어낸다 (#557 · ssccops#502 · #36 결정).
     *
     * ── 왜 «폼의 접수 종료»를 보는가 ────────────────────────────────────────
     * `domain/form/AGENTS.md` 가 적어 둔 문장은 «폼이 CLOSED된 뒤 남은 DRAFT는 **접수 종료 후
     * 90일**까지 보존한다» 이고, 즉시 지우지 않는 근거는 **마감 철회(CLOSED→OPEN)가 허용된다**는
     * 것이다(#33). 그러므로 기준은 초안이 아니라 **폼**에 있다.
     *
     * 같은 문서가 대안으로 적어 둔 수동 SQL 은 `mdfcn_dt < now() - interval '90 days'` 로 **폼
     * 상태를 보지 않았다.** 그것을 그대로 옮기면 **접수가 열려 있는 폼의 초안도 지운다** — 장기
     * 모집 폼에서 90일 넘게 손대지 않은 작성 중인 지원서가 사라진다. 문장에는 근거가 있고 SQL 에는
     * 없으므로 문장을 정본으로 삼았다(그 SQL 줄은 이 PR 에서 문서에서 지웠다).
     *
     * ── DRAFT 만 지운다 ────────────────────────────────────────────────────
     * 제출된 응답은 지원 의사를 남긴 것이라 보관 근거가 있다. 여기서 지우는 것은 «낸 적 없는
     * 것»뿐이며, 그래서 조건이 `ResponseStatus.DRAFT` 하나다.
     *
     * ── 멱등한 DELETE 다 ──────────────────────────────────────────────────
     * 인스턴스가 둘 떠도(배포 겹침) 결과가 같다. `AGENTS.md` 가 이 배치를 미룬 근거였던 «다중
     * 인스턴스에서 중복 실행을 막을 장치가 없다» 는 이 종류의 작업에 애초에 필요 없었고,
     * `NotificationRepository.deleteReadBefore` 가 그것을 이미 코드로 증명해 두었다.
     *
     * ── 식별자를 먼저 모은다 (server#685) ───────────────────────────────────
     * 처음에는 이 조건으로 바로 지우는 벌크 DELETE 하나였다. 기획안 응답에 첨부(file_rfrnc ·
     * FORM_RESPONSE)가 붙으면서 **지운 초안이 무엇이었는지**를 알아야 그 첨부 행과 R2 오브젝트를 함께
     * 걷을 수 있게 됐는데 벌크 DELETE는 건수만 돌려준다. file_rfrnc에 FK가 없어 DB가 대신 지워 주지도
     * 않는다(domain/file/AGENTS.md — 정리는 도메인의 책임). 그래서 조건은 여기 그대로 두고 식별자를
     * 고른 뒤, 아래 deleteDraftsByIdIn이 그 식별자로 지운다.
     */
    @Query(
            "select r.id from FormResponseHistoryEntity r"
                    + " where r.status = :draft"
                    + " and r.form.status = :closed"
                    + " and r.form.receiptEndAt is not null"
                    + " and r.form.receiptEndAt < :before")
    List<Long> findDraftIdsOfFormsClosedBefore(
            @Param("draft") ResponseStatus draft,
            @Param("closed") FormStatus closed,
            @Param("before") Instant before);

    /*
     * 위 질의가 고른 초안을 지운다 (server#685). **상태를 한 번 더 건다** — 고른 뒤 지우기 전 사이에 마감
     * 철회(CLOSED→OPEN)와 제출이 끼어들면 그 행은 더 이상 «낸 적 없는 초안»이 아니다. 그 사이가 같은
     * 트랜잭션의 몇 밀리초라 실제로 닿을 일은 거의 없지만, 지울 근거(DRAFT)를 지우는 문장에서 다시
     * 확인하는 값이 0에 가깝다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FormResponseHistoryEntity r where r.id in :ids and r.status = :draft")
    int deleteDraftsByIdIn(
            @Param("ids") Collection<Long> ids, @Param("draft") ResponseStatus draft);
}
