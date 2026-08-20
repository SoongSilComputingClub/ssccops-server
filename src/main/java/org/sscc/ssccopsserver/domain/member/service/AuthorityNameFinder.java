package org.sscc.ssccopsserver.domain.member.service;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;

import lombok.RequiredArgsConstructor;

/*
 * 권한 코드 → 표시명(authrt_nm) 조회 (#123).
 *
 * 운영 도메인이 승인자 결재 권한의 이름을 응답에 실을 때 쓰는 진입점이다 — 다른 도메인의
 * Repository를 직접 주입하지 않는 규칙(AR-07·LY-10) 때문에 회원 도메인 Service로 연다.
 * 판정(AuthorityPolicy)이나 관리(AuthorityAdminService)에 얹지 않은 것은, 저쪽은 각각
 * "무엇을 할 수 있는가"와 "트리·부여를 바꾼다"만 알아야 해서다(BR-M28).
 */
@Service
@RequiredArgsConstructor
public class AuthorityNameFinder {

    private final AuthorityRepository authorityRepository;

    /** 코드 → 표시명. 없는 코드는 결과에서 빠진다 */
    @Transactional(readOnly = true)
    public Map<String, String> namesOf(Collection<String> authrtCds) {
        if (authrtCds == null || authrtCds.isEmpty()) {
            return Map.of();
        }
        return authorityRepository.findAllById(authrtCds).stream()
                .collect(Collectors.toMap(AuthorityEntity::getCode, AuthorityEntity::getName));
    }
}
