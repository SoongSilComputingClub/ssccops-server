package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 이관 회원 계정 연결 요청 (POST /v1/members/link, #86 · ssccops#78 A안).
 *
 * 세 값이 **모두** 필수다. 학번·회원명만으로는 명부를 한 번 본 사람이면 누구나 채울 수 있어
 * 본인 확인이 되지 않는다. 연락처를 함께 요구하는 근거는 그 값이 MEMBER_MANAGE 없이는
 * 조회되지 않는다는 사실이다 — 회원 목록·상세가 그 권한으로 막히고 /v1/members/assignable은
 * 연락처를 싣지 않는다(AssignableMemberResponse). 학번·이름과 달리 명부를 봤다는 것만으로는
 * 알 수 없는 값이라야 '본인만 아는 값'이 된다.
 *
 * 가입 요청(MemberSignupRequest)과 달리 계정 식별자·이메일뿐 아니라 **등급·상태·기수도 받지
 * 않는다.** 연결은 새 회원을 만드는 일이 아니라 명부에 이미 있는 행에 계정을 붙이는 일이라,
 * 명부의 값이 그대로 유지되어야 한다(BR-M51). 요청에 자리를 만들지 않는 것이 "덮어쓰지
 * 않는다"를 지키는 방법이다.
 *
 * 필드명이 가입 요청(name·phoneNumber·studentNumber)과 다른 것은 이슈 #86의 API 계약 표를
 * 그대로 따랐기 때문이다 — 웹(ssccops-web#58)이 이 이름으로 구현한다. 가입 요청이 #21의 계약
 * 표를 따른 것과 같은 이유이며, 이미 소비 중인 스키마를 여기서 바꾸지 않는다.
 *
 * **정규화는 여기서 하지 않는다.** 앞뒤 공백·하이픈을 어떻게 다룰지는 검증 애노테이션이 아니라
 * 비교 규칙이고, 그 규칙 한 벌은 MemberLinkPolicy에 있다. @Size가 정규화 전 길이를 보는 것도
 * 의도한 것이다 — 원본 그대로의 상한이라야 컬럼 길이(stdnt_no 20 · mbr_nm 50 · telno 20)와
 * 대응된다.
 */
public record MemberLinkRequest(
        @NotBlank @Size(max = 20) String stdntNo,
        @NotBlank @Size(max = 50) String mbrNm,
        @NotBlank @Size(max = 20) String telno) {}
