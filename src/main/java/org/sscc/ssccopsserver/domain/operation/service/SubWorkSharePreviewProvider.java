package org.sscc.ssccopsserver.domain.operation.service;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;
import org.sscc.ssccopsserver.domain.share.service.SharePreviewProvider;

import lombok.RequiredArgsConstructor;

/*
 * 하위 업무의 공유 미리보기 (ssccops#200).
 *
 * 공유 도메인은 하위 업무가 무엇인지 모르고, 이 클래스는 토큰이 무엇인지 모른다. 그 경계가
 * `SharePreviewProvider`이며 근거는 그 인터페이스 주석에 있다.
 *
 * **삭제된 운영 건은 없는 것으로 답한다** — `findByIdAndOperationDeletedAtIsNull`이 그 판정이고,
 * 상세 조회가 쓰는 것과 같은 질의다. 지운 업무의 제목이 링크로 계속 열리면 "지웠다"는 화면의
 * 표시가 사실이 아니게 된다. 빈 Optional은 폐기된 링크와 같은 404로 나간다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubWorkSharePreviewProvider implements SharePreviewProvider {

    /*
     * 익명에게 내주는 본문의 상한. 잘라야 하는 이유는 표시가 아니라 **새는 양**이다 — 카드에
     * 두 줄 남짓만 보이는데 본문 전체를 익명 경로로 흘려보낼 이유가 없다. 실제로 몇 자를
     * 보여줄지(마크다운 표식을 걷고 120자로 줄이는 것)는 카드를 만드는 웹의 몫이고
     * `@ssccops/share-meta`가 그 규칙을 갖는다.
     */
    private static final int SUMMARY_LIMIT = 500;

    private final SubWorkRepository subWorkRepository;

    @Override
    public ShareTargetType targetType() {
        return ShareTargetType.SUB_WORK;
    }

    @Override
    public Optional<SharePreview> preview(Long targetId) {
        return subWorkRepository
                .findByIdAndOperationDeletedAtIsNull(targetId)
                .map(subWork -> new SharePreview(subWork.getTitle(), summaryOf(subWork)));
    }

    /*
     * 본문이 없거나 공백뿐이면 null이다 — **서버가 대체 문구를 만들지 않는다.** 채워 버리면
     * "본문이 없다"와 "서버가 그 문구를 줬다"를 웹이 구별할 수 없고, 그 판단(무엇으로 떨어질
     * 것인가)은 그리는 쪽이 정할 일이다.
     */
    private String summaryOf(SubWorkEntity subWork) {
        String content = subWork.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.strip();
        return trimmed.length() <= SUMMARY_LIMIT ? trimmed : trimmed.substring(0, SUMMARY_LIMIT);
    }
}
