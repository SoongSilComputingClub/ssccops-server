package org.sscc.ssccopsserver.domain.member.repository;

/*
 * 권한 트리의 간선 하나 (자식 코드, 부모 코드). 부모가 없으면 parentCode가 null이다.
 *
 * 펼침 판정은 트리 전체를 봐야 하는데, AuthorityEntity의 self @ManyToOne을 타고 다니면
 * 권한 수만큼 지연 로딩 쿼리가 나간다. 코드 문자열 두 개만 있으면 되므로 질의 한 번으로
 * 간선만 받아 온다.
 */
public record AuthorityLink(String code, String parentCode) {}
