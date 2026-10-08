package org.sscc.ssccopsserver.domain.member.dto;

import org.springframework.core.io.ByteArrayResource;

/*
 * 내려받을 회원명부 파일 (#674). 파일 이름은 서버가 정한다 — 화면이 따로 지으면 제목·파일 이름
 * 규칙이 두 벌이 된다(그래서 CORS가 Content-Disposition을 노출한다 · SecurityConfig).
 *
 * 본문을 byte[]가 아니라 ByteArrayResource로 담는 것은 record의 equals·hashCode가 배열을
 * 참조로 비교하기 때문이다.
 */
public record MemberRosterFile(String fileName, ByteArrayResource content) {}
