package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 권한 수정 요청 (#65 PATCH /v1/authorities/{authrtCd}).
 *
 * **메서드는 PATCH지만 본문은 노드 한 벌 전체다.** JSON은 "필드를 보내지 않음"과 "null을 보냄"을
 * 레코드로 구별할 수 없어서, upAuthrtCd를 부분 수정으로 두면 "상위를 바꾸지 마라"와 "최상위로
 * 올려라"가 같은 요청이 된다. 편집 화면은 노드 한 벌을 통째로 들고 저장하므로 전체를 받는 쪽이
 * 화면과도 맞고, 무엇보다 상위가 조용히 지워지는 경로를 만들지 않는다. 폼 라벨 지정 교체(#34)가
 * 전체 교체인 것과 같은 판단이다.
 *
 * authrtCd는 경로에 있는데도 본문에 다시 받는다. 코드는 PK라 어차피 바뀌지 않지만, 화면이
 * 코드를 고쳐 보냈을 때 조용히 무시하면 "바꿨는데 안 바뀌었다"가 되기 때문이다 — 시스템 권한이면
 * 409 SYSTEM_AUTHORITY_IMMUTABLE로, 사용자 정의 권한이면 400으로 거절한다(사용자 정의 권한은
 * 지우고 새로 만들면 되므로 막다른 길이 아니다). 생략하면 검사하지 않는다.
 *
 * indctSeqno가 null이면 현재 값을 유지한다 — 트리 위치만 옮기는 화면이 순번까지 들고 있지
 * 않아도 되게 한다.
 */
public record AuthorityUpdateRequest(
        @Size(max = 50) String authrtCd,
        @NotBlank @Size(max = 50) String authrtNm,
        @Size(max = 50) String upAuthrtCd,
        @Size(max = 500) String authrtExpln,
        Short indctSeqno) {}
