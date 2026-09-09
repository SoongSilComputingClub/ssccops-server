package org.sscc.ssccopsserver.domain.event.service;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;
import org.sscc.ssccopsserver.domain.share.service.SharePreviewProvider;

import lombok.RequiredArgsConstructor;

/*
 * 행사의 공유 미리보기 (ssccops#312 · ADR-0016).
 *
 * 경계는 앞의 둘과 같다 — 공유 도메인은 행사가 무엇인지 모르고 이 클래스는 토큰이 무엇인지
 * 모른다(`SharePreviewProvider` 주석). 다른 것은 **재료가 넉넉하다**는 것 하나다.
 *
 * ## 요약은 본문을 자른 것이다 — 조립하지 않는다
 *
 * `WorkSharePreviewProvider`는 요약을 유형+기간으로 조립하는데, 그것은 업무에 본문이 아예
 * 없어서 한 선택이었다(ssccops#251). 행사에는 `mtxt_cn`이 있고 그것이 기획한 사람이 직접 쓴
 * 설명이므로, `SubWorkSharePreviewProvider`처럼 **잘라서 그대로 내주는 쪽**이 맞는다.
 *
 * ## 일시·장소를 앞에 붙이지 않는다 (ssccops#312에서 정한다)
 *
 * `#251`·`#252`가 기간을 담은 근거는 그것이 **확정된 사실**이라는 것이었다. **그 전제가 여기서는
 * 성립하지 않는다** — 이 기능이 여는 것은 게시 전(DRAFT) 행사뿐이고(`ShareTargetType.EVENT`),
 * 게시 전 행사의 일시·장소는 아직 사람이 정하는 중인 값이다. 카드는 한 번 굳는데(ssccops#194
 * 제약 ②) 굳는 자리에 아직 굳지 않은 값을 적으면, 날짜가 바뀐 뒤에도 옛 날짜를 말하는 카드가
 * 방에 남는다. 메신저 캐시는 우리가 지울 수 없다.
 *
 * 두 번째 이유는 자리다툼이다. 카드에 실제로 보이는 것은 두 줄 남짓인데 그 앞을 서버가 만든
 * 머리말이 먹으면, 정작 "이렇게 준비 중이다"를 말하는 본문의 첫 문장이 밀려난다.
 *
 * **본문에 일시·장소가 적혀 있으면 그것은 그대로 나간다** — 기획자가 본문에 쓴 것은 스스로
 * 고칠 수 있는 값이고, 서버가 컬럼에서 뽑아 붙이는 것과 다르다.
 *
 * ## 이미지를 열지 않는다
 *
 * 미리보기에 `thmb_url_addr`도 본문 이미지 주소도 싣지 않는다. `SharePreview`에 자리 자체가
 * 없기도 하지만, 근거는 그보다 앞선다 — 행사 이미지의 읽기 주소는
 * `GET /public/v1/events/{eventId}/images/{fileName}`이고 그 핸들러가 게시 여부를 본다.
 * **게시 전 행사의 이미지를 익명에게 여는 것은 이 이슈와 별개의 보안 결정이다.** 본문 문자열에
 * 박힌 마크다운 이미지 주소는 요약에 따라 나갈 수 있지만, 그 주소를 여는 것은 여전히 저쪽
 * 층이 판단한다 — 카드는 이미지 없이 성립한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventSharePreviewProvider implements SharePreviewProvider {

    /*
     * 익명에게 내주는 본문의 상한. 근거는 `SubWorkSharePreviewProvider`와 같다 — **자르는
     * 이유는 표시가 아니라 새는 양이다.** 행사 본문은 10만 자까지 갈 수 있어(D12) 그대로
     * 흘리면 카드 두 줄을 위해 원고 한 편이 익명 경로로 나간다. 실제로 몇 자를 보여줄지는
     * 카드를 만드는 웹의 몫이고 `@ssccops/share-meta`가 그 규칙을 갖는다.
     */
    private static final int SUMMARY_LIMIT = 500;

    /*
     * 카드가 열리는 상태. **보관(ARCHIVED)이 빠져 있는 것이 요점이다** — 삭제가 없어진 뒤로
     * (ADR-0014) 보관은 잘못 만든 행사를 치우는 유일한 길이고, 치운 행사의 제목이 링크로 계속
     * 열리면 "내렸다"는 화면의 표시가 사실이 아니게 된다(삭제된 운영 건을 없는 것으로 답하는
     * `SubWorkSharePreviewProvider`와 같은 자리). 공개 상세도 보관된 행사를 404로 답한다.
     *
     * 게시(PUBLISHED)가 들어 있는 것은 반대 방향의 같은 이유다. 발급은 DRAFT에서만 되지만
     * 그 뒤에 게시되는 것이 정상 경로이므로, 게시되는 순간 카드가 깨지면 **볼 수 있게 된
     * 시점에** 이미 나간 링크가 죽는다.
     */
    private static final Set<EventStatus> READABLE_STATUSES =
            EnumSet.of(EventStatus.DRAFT, EventStatus.PUBLISHED);

    private final EventRepository eventRepository;

    @Override
    public ShareTargetType targetType() {
        return ShareTargetType.EVENT;
    }

    @Override
    public Optional<SharePreview> preview(Long targetId) {
        return eventRepository
                .findByIdAndStatusIn(targetId, READABLE_STATUSES)
                .map(event -> new SharePreview(event.getTitle(), summaryOf(event)));
    }

    /*
     * 본문이 공백뿐이면 null이다 — **서버가 대체 문구를 만들지 않는다**(`SharePreview` 계약).
     * 채워 버리면 "본문이 없다"와 "서버가 그 문구를 줬다"를 웹이 구별할 수 없다.
     *
     * `mtxt_cn`은 NOT NULL이지만 null도 함께 막는다 — 이 자리는 익명 경로라 NPE 하나가 500이
     * 되고, 컬럼 제약은 이 클래스가 지키는 것이 아니다.
     */
    private String summaryOf(EventEntity event) {
        String content = event.getContentMarkdown();
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.strip();
        return trimmed.length() <= SUMMARY_LIMIT ? trimmed : trimmed.substring(0, SUMMARY_LIMIT);
    }
}
