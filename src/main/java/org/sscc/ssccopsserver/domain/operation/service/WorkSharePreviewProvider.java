package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;
import org.sscc.ssccopsserver.domain.share.service.SharePreviewProvider;

import lombok.RequiredArgsConstructor;

/*
 * 업무의 공유 미리보기 (ssccops#306 · ADR-0016).
 *
 * `SubWorkSharePreviewProvider`와 경계는 같다 — 공유 도메인은 업무가 무엇인지 모르고 이
 * 클래스는 토큰이 무엇인지 모른다. **삭제된 운영 건은 없는 것으로 답하는 판정도 같은 질의다**
 * (`findByIdAndOperationDeletedAtIsNull`). 빈 Optional은 폐기된 링크와 같은 404로 나간다.
 *
 * ## 다른 것 하나 — 업무에는 본문이 없다
 *
 * 하위 업무는 `title`·`content`를 자기가 갖는데 업무는 **둘 다 없다.** 제목은 부모 운영 건의
 * `oper_ttl`이고, 자유 텍스트는 `grvw_cn`(행사 종료 후 회고) 하나뿐인데 그건 등록 시점에
 * 비어 있어 미리보기에 쓸 것이 아니다.
 *
 * **그래서 요약을 조립한다 — 유형 + 기간이다**(ssccops#251, 2026-09-08 확정).
 *
 * <pre>행사 · 2026-09-15 ~ 09-20</pre>
 *
 * 담을 수 있는 근거는 **확정된 사실**이라는 것이다. 카드는 한 번 굳으므로(ssccops#194 제약 ②)
 * 시간에 따라 변하는 값(상태·진행률·하위 업무 목록)은 담지 않는데, 유형과 기간은 시간이 바꾸는
 * 값이 아니라 사람이 정한 값이다. **감수하는 것**은 일정이 바뀌면 이미 나간 카드가 옛 기간을
 * 말한다는 것이며, 메신저 캐시를 우리가 지울 수 없으므로 되돌릴 방법이 없다 — 업무의 기간이
 * 자주 바뀌지 않는다는 전제 위의 판단이고, 자주 바뀐다는 것이 드러나면 이 자리를 다시 본다.
 *
 * **`SubWorkSharePreviewProvider`의 "서버가 대체 문구를 만들지 않는다"와 부딪히지 않는다.**
 * 그쪽이 막는 것은 *없는 것을 채우는* 일이다 — 본문이 비었을 때 서버가 문구를 지어 넣으면
 * 웹이 "본문이 없다"와 구별할 수 없다. 여기서 넣는 것은 지어낸 문구가 아니라 **실제 값**이고,
 * 값이 하나도 없으면(유형만 남으면) 그때도 지어내지 않는다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkSharePreviewProvider implements SharePreviewProvider {

    /*
     * 표기 기준 시간대. `bgng_dt`·`end_dt`가 `Instant`라 날짜로 자르려면 시간대가 필요하고,
     * 그 기준은 서비스 타임존이다 — `ShareLinkResponse`가 같은 자리에서 쓰는 값과 같다(AP-12).
     */
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /*
     * 같은 해의 끝 날짜는 연도를 떼고 적는다(`2026-09-15 ~ 09-20`). 해가 다르면 붙인다 —
     * `2026-12-28 ~ 2027-01-03`에서 연도를 떼면 거꾸로 가는 기간처럼 읽힌다.
     */
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    /** 유형과 기간을 가르는 구분자. 카드 한 줄에 두 값을 담으므로 줄바꿈이 아니라 가운뎃점이다 */
    private static final String SEPARATOR = " · ";

    private final WorkRepository workRepository;

    @Override
    public ShareTargetType targetType() {
        return ShareTargetType.WORK;
    }

    @Override
    public Optional<SharePreview> preview(Long targetId) {
        return workRepository
                .findByIdAndOperationDeletedAtIsNull(targetId)
                .map(work -> new SharePreview(work.getOperation().getTitle(), summaryOf(work)));
    }

    /*
     * 유형 + 기간. **기간이 없어도 문구가 깨지지 않아야 한다** — `bgng_dt`·`end_dt`는 둘 다
     * nullable이고 등록 화면에서 선택 입력이라 실제로 비어 있는 업무가 있다.
     *
     * | 있는 값 | 요약 |
     * |---|---|
     * | 둘 다 | `행사 · 2026-09-15 ~ 09-20` |
     * | 시작만 | `행사 · 2026-09-15부터` |
     * | 종료만 | `행사 · 2026-09-20까지` |
     * | 없음 | `행사` |
     *
     * 한쪽만 있을 때 `2026-09-15 ~`처럼 물결을 남기지 않는 것은 그것이 **잘린 문자열로 보이기**
     * 때문이다 — 카드에서 사람이 보는 것은 이 문장 하나뿐이라 미완성으로 읽히면 안 된다.
     */
    private String summaryOf(WorkEntity work) {
        String typeLabel = displayNameOf(work.getWorkType());
        OperationEntity operation = work.getOperation();
        String period = periodOf(operation.getBeginAt(), operation.getEndAt());
        return period == null ? typeLabel : typeLabel + SEPARATOR + period;
    }

    private String periodOf(Instant beginAt, Instant endAt) {
        LocalDate begin = toServiceDate(beginAt);
        LocalDate end = toServiceDate(endAt);
        if (begin == null && end == null) {
            return null;
        }
        if (begin == null) {
            return FULL_DATE.format(end) + "까지";
        }
        if (end == null) {
            return FULL_DATE.format(begin) + "부터";
        }
        DateTimeFormatter endFormat = begin.getYear() == end.getYear() ? MONTH_DAY : FULL_DATE;
        return FULL_DATE.format(begin) + " ~ " + endFormat.format(end);
    }

    private LocalDate toServiceDate(Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, SERVICE_ZONE);
    }

    /*
     * 업무 유형의 표시명. **이 값은 `work_type_cd`의 표시명이지 `oper_type_cd`의 것이 아니다** —
     * 후자는 업무 행에서 언제나 `WORK`라 카드에 적어도 아무것도 알려 주지 않는다. ssccops#251이
     * 든 예(`행사 · ...`)가 가리키는 것도 `EVENT`이므로 이쪽이다.
     *
     * **표시명이 서버에 오는 것은 여기가 처음이다.** 다른 코드의 한글 이름은 전부 웹의
     * `codes.ts`가 갖는데, 이 문자열만은 **웹이 아니라 메신저가 그린다** — 크롤러는 코드값을
     * 받아 이름으로 바꿔 줄 수 없으므로 서버가 완성된 문장을 내줄 수밖에 없다.
     *
     * 그래서 세 낱말이 두 곳에 있게 된다. 갈리면 카드와 화면이 다른 이름을 말하므로, **정본은
     * 데이터사전의 `work_type_cd`**이고 그 표시명을 고칠 때 이 자리도 함께 고친다. switch를
     * default 없이 두어 유형이 늘면 컴파일이 깨지게 한다 — 새 값이 조용히 빈 이름으로 나가는
     * 것보다 낫다.
     */
    private String displayNameOf(WorkType workType) {
        return switch (workType) {
            case EVENT -> "행사";
            case REGULAR -> "상시";
            case ROUTINE -> "정례운영";
        };
    }
}
