package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.PersistenceException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberDeletionPreviewResponse;
import org.sscc.ssccopsserver.domain.member.repository.MemberDeletionQueryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberReferenceConstraints;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.extern.slf4j.Slf4j;

/*
 * MemberDeletionService 구현 (#361 · ADR-0021). 설계의 근거는 인터페이스 주석에 있다.
 *
 * ── 플래그 ─────────────────────────────────────────────────
 * `ssccops.member.hard-delete.enabled`, 기본 **false**. application-dev.yml·application-prod.yml에
 * 넣지 않는다 — 배포 설정(환경변수 SSCCOPS_MEMBER_HARD_DELETE_ENABLED, Coolify)으로 켠다. 값을
 * 설정 파일에 두면 «임시»가 코드에 굳고, 끄는 일이 배포가 된다. 생성자 주입인 것은 테스트가
 * 컨텍스트 없이 off 상태를 만들기 위해서다(ProposalFormSeeder와 같은 자리).
 *
 * ── 삭제 순서 ──────────────────────────────────────────────
 * 플래그 → 본인 → 존재 → deleteById → flush. flush를 직접 부르는 것은 FK 위반이 **이 메서드
 * 안에서** 드러나야 409로 옮길 수 있기 때문이다 — 트랜잭션 커밋에서 터지면 컨트롤러 밖이라
 * 500이 된다. 본인 검사가 존재 검사보다 앞인 것은 요청자는 언제나 존재하기 때문이며(있는 회원
 * 하나를 조회하지 않아도 답할 수 있다) 순서를 바꿔도 결과는 같다.
 *
 * ── 왜 트랜잭션이 여기 하나뿐인가 ──────────────────────────────
 * "삭제는 한 트랜잭션"(이슈)은 코드가 도메인을 돌며 지우지 않기 때문에 저절로 성립한다 — DB가
 * DELETE 한 문장 안에서 cascade를 끝내므로 여러 저장소를 순서대로 부를 일이 없다.
 */
@Slf4j
@Service
public class MemberDeletionServiceImpl implements MemberDeletionService {

    private final MemberRepository memberRepository;
    private final MemberDeletionQueryRepository deletionQueryRepository;
    private final boolean enabled;

    public MemberDeletionServiceImpl(
            MemberRepository memberRepository,
            MemberDeletionQueryRepository deletionQueryRepository,
            @Value("${ssccops.member.hard-delete.enabled:false}") boolean enabled) {
        this.memberRepository = memberRepository;
        this.deletionQueryRepository = deletionQueryRepository;
        this.enabled = enabled;
    }

    @Override
    @Transactional(readOnly = true)
    public MemberDeletionPreviewResponse preview(Long memberId) {
        requireEnabled();
        requireExists(memberId);

        MemberDeletionQueryRepository.DeletionCounts counts =
                deletionQueryRepository.countOwnData(memberId);
        List<String> blockedBy =
                deletionQueryRepository.findBlockingConstraintNames(memberId).stream()
                        .map(MemberReferenceConstraints::byConstraintName)
                        .flatMap(Optional::stream)
                        .map(MemberReferenceConstraints.Reference::label)
                        .distinct()
                        .toList();
        return new MemberDeletionPreviewResponse(
                counts.responseCount(),
                counts.participationCount(),
                counts.historyCount(),
                blockedBy);
    }

    @Override
    @Transactional
    public void delete(Long memberId, Long requesterId) {
        requireEnabled();
        if (memberId.equals(requesterId)) {
            throw new GeneralException(MemberErrorCode.CANNOT_DELETE_SELF);
        }
        requireExists(memberId);

        try {
            memberRepository.deleteById(memberId);
            memberRepository.flush();
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            /*
             * 행위자 참조 FK(NO ACTION)가 막았다. 어느 제약인지는 예외 문구에 있고 그것을 사람
             * 표기로 옮긴다. 표에 없는 이름이면(V9와 표가 갈렸거나 새 FK가 생겼다) 문구 없이
             * 409만 나간다 — 삭제가 막힌 사실은 그대로이고 잃는 것은 안내뿐이다. 그 경우를 로그에
             * 남겨 표를 고칠 수 있게 한다.
             */
            Optional<MemberReferenceConstraints.Reference> blocked =
                    MemberReferenceConstraints.resolve(ex);
            if (blocked.isEmpty()) {
                log.warn(
                        "회원 {} 삭제를 막은 제약을 번역하지 못했다 — MemberReferenceConstraints와 V9를"
                                + " 대조할 것: {}",
                        memberId,
                        ex.getMessage());
                throw new GeneralException(MemberErrorCode.MEMBER_REFERENCED);
            }
            throw new GeneralException(
                    MemberErrorCode.MEMBER_REFERENCED,
                    MemberErrorCode.MEMBER_REFERENCED.getMessage()
                            + " ("
                            + blocked.get().label()
                            + ")");
        }
        log.info("회원 {} 하드 삭제 — 요청자 {} (ADR-0021 임시 기능)", memberId, requesterId);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new GeneralException(MemberErrorCode.FEATURE_DISABLED);
        }
    }

    private void requireExists(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND);
        }
    }
}
