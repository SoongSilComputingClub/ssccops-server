package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Clock;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.OperationShareLinkResponse;
import org.sscc.ssccopsserver.domain.operation.dto.PublicSharePreviewResponse;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationShareLinkEntity;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.OperationShareLinkRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 운영 건 공유 링크 (ssccops#200 · ADR-0016).
 *
 * 이 서비스가 지키는 것은 셋이다.
 *
 * **① 한 운영 건에 유효한 토큰은 최대 하나다.** 발급을 다시 부르면 있는 것을 그대로
 * 돌려준다 — 누를 때마다 쌓이면 무엇을 폐기해야 할지 알 수 없어 폐기가 의미를 잃는다
 * (수용 기준 3). 화면이 '공유' 버튼을 두 번 누르는 것은 정상이므로 이것이 예외가 아니라
 * 기본 경로다.
 *
 * **② 발급·폐기는 그 건을 볼 수 있는 사람의 일이다.** 새 권한 코드를 만들지 않고
 * 컨트롤러의 @RequireAuthority가 기존 조회 권한을 그대로 쓴다 — 권한 어휘를 늘리면
 * 역할마다 다시 부여해야 하고, "공유할 수 있지만 볼 수는 없는" 조합이 생긴다.
 *
 * **③ 미리보기는 존재를 흘리지 않는다.** 없는 토큰·폐기된 토큰·지워진 운영 건이 모두 같은
 * 404다. 판정을 Repository의 한 쿼리에 묶어 둔 것도 그래서다 — 찾은 뒤 걸러 내면 그 분기
 * 하나가 빠지는 것으로 폐기된 링크가 열린다.
 *
 * 만료는 없다(ADR-0016). 카드는 굳는데 링크만 죽으면 멀쩡해 보이는 카드를 눌렀더니 404가
 * 되고, 공유한 사람은 자기 링크가 죽은 줄 모른다. 거두는 길은 명시적 폐기 하나뿐이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationShareLinkServiceImpl implements OperationShareLinkService {

    private final OperationRepository operationRepository;
    private final OperationShareLinkRepository shareLinkRepository;
    private final ShareTokenGenerator shareTokenGenerator;
    private final Clock clock;

    /*
     * 공유 링크 발급. 이미 유효한 링크가 있으면 **새로 만들지 않고 그것을 돌려준다**(①).
     *
     * 201이 아니라 200으로 응답하는 것은 컨트롤러의 몫이며, 두 경우(새로 만듦·기존 반환)를
     * 상태 코드로 가르지 않는다 — 화면이 할 일이 같기 때문이다(URL을 만들어 복사한다).
     */
    @Override
    @Transactional
    public OperationShareLinkResponse issue(Long operationId, MemberEntity creator) {
        OperationEntity operation = findOperation(operationId);

        return shareLinkRepository
                .findByOperationAndRevokedAtIsNull(operation)
                .map(OperationShareLinkResponse::from)
                .orElseGet(() -> OperationShareLinkResponse.from(create(operation, creator)));
    }

    /*
     * 공유 중지. **이미 유효한 링크가 없어도 성공이다** — 사용자가 원한 상태("이 건은 공유되어
     * 있지 않다")가 이미 성립하기 때문이다. 404로 거절하면 화면이 '공유 중지'를 두 번 누른
     * 것만으로 오류를 보게 되고, 그 오류로 사용자가 할 수 있는 일이 없다.
     *
     * 대상 운영 건이 없거나 지워진 경우는 그대로 404다 — 그건 사용자가 원한 상태가 아니라
     * 화면이 낡았다는 뜻이다.
     */
    @Override
    @Transactional
    public void revoke(Long operationId) {
        OperationEntity operation = findOperation(operationId);
        shareLinkRepository
                .findByOperationAndRevokedAtIsNull(operation)
                .ifPresent(link -> link.revoke(clock.instant()));
    }

    /*
     * 익명 미리보기. 크롤러가 여는 자리이며 **요청 주체가 없는 것이 정상이다.**
     *
     * 없는 토큰·폐기된 토큰·지워진 운영 건이 모두 같은 404다(③) — 코드를 나누면 그 차이가
     * 곧 "그 토큰은 있었다"는 정보가 된다.
     */
    @Override
    public PublicSharePreviewResponse preview(String token) {
        return shareLinkRepository
                .findVisibleByToken(token)
                .map(link -> PublicSharePreviewResponse.from(link.getOperation()))
                .orElseThrow(() -> new GeneralException(OperationErrorCode.SHARE_LINK_NOT_FOUND));
    }

    // ------------------------------------------------------------------ 헬퍼

    private OperationShareLinkEntity create(OperationEntity operation, MemberEntity creator) {
        OperationShareLinkEntity link =
                OperationShareLinkEntity.issue(operation, shareTokenGenerator.generate(), creator);
        try {
            return shareLinkRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException ex) {
            /*
             * 토큰 UNIQUE 충돌. 256비트 난수라 실질적으로 일어나지 않지만, 일어난다면 그것은
             * 우리 잘못이 아니라 확률이므로 사용자에게 재시도를 시키지 않고 여기서 한 번 더
             * 만든다. 두 번째도 충돌하면 그때는 난수 생성기가 고장 난 것이라 그대로 올린다.
             */
            OperationShareLinkEntity retry =
                    OperationShareLinkEntity.issue(
                            operation, shareTokenGenerator.generate(), creator);
            return shareLinkRepository.saveAndFlush(retry);
        }
    }

    /** 없는 운영 건과 소프트 삭제된 운영 건은 같은 404다 (조회 계열과 같은 태도) */
    private OperationEntity findOperation(Long operationId) {
        return operationRepository
                .findByIdAndDeletedAtIsNull(operationId)
                .orElseThrow(() -> new GeneralException(OperationErrorCode.OPERATION_NOT_FOUND));
    }
}
