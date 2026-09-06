package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationShareLinkEntity;

public interface OperationShareLinkRepository
        extends JpaRepository<OperationShareLinkEntity, Long> {

    /*
     * 이 운영 건에 지금 유효한 링크. 발급을 다시 불렀을 때 새로 쌓지 않고 이것을 돌려준다
     * (수용 기준 3 — 누를 때마다 늘면 무엇을 폐기해야 할지 알 수 없어 폐기가 의미를 잃는다).
     *
     * 폐기된 행은 남으므로 운영 건 하나에 행이 여럿일 수 있고, 그중 rvk_dt가 NULL인 것은
     * 최대 하나다(발급 경로가 그렇게 유지한다).
     */
    Optional<OperationShareLinkEntity> findByOperationAndRevokedAtIsNull(OperationEntity operation);

    /*
     * 익명 미리보기가 토큰으로 여는 자리.
     *
     * **폐기 여부와 대상의 삭제 여부를 조건에 함께 넣는다** — 토큰으로 찾은 뒤 걸러 내면
     * 그 분기 하나가 빠지는 것으로 폐기된 링크가 열린다(EventRepository.findByIdAndStatus와
     * 같은 자리·같은 태도). 없는 토큰·폐기된 토큰·지워진 운영 건이 호출부에서 모두 같은
     * 404가 되는 것도 여기서 하나로 묶었기 때문이다 — 코드를 나누면 "그 토큰은 있었다"가
     * 새어 나간다.
     *
     * 미리보기가 쓰는 값이 oper의 제목·유형뿐이라 함께 페치한다.
     */
    @Query(
            "select l from OperationShareLinkEntity l join fetch l.operation o"
                    + " where l.token = :token and l.revokedAt is null and o.deletedAt is null")
    Optional<OperationShareLinkEntity> findVisibleByToken(@Param("token") String token);
}
