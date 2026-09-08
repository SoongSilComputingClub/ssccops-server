package org.sscc.ssccopsserver.domain.member.service;

/*
 * 탈퇴·제명 경고에 실을 "담당 중인 하위 업무 수"를 알려 주는 포트 (ssccops#242).
 *
 * ── 왜 회원 도메인이 인터페이스를 갖는가 ────────────────────────
 * 회원이 조직을 떠날 때 화면은 "담당 중인 하위 업무 N건"을 경고로 보여 준다(#78). 그 숫자는
 * 운영 도메인만 셀 수 있는데, 회원 도메인이 SubWorkService 를 직접 주입받으면
 * **member → operation → member 순환**이 된다(operation 은 회원 정보를 MemberService 로 얻는다).
 *
 * 예전에는 그 순환을 빈을 쪼개서 피했다 — MemberChangeService 를 MemberService 에서 떼어낸
 * 이유가 *"한 빈에 두면 생성자 주입이 고리가 되어 애플리케이션이 뜨지 않는다"* 였다. 그것은
 * 스프링이 뜨게 만든 것이지 순환을 없앤 것이 아니어서, 패키지 수준에서는 그대로 남아 있었다.
 *
 * 그래서 방향을 뒤집는다. **회원 도메인이 필요한 사실의 모양을 선언하고 운영 도메인이 답한다**
 * — SystemFormApprovalHook(폼이 선언, 학술이 구현) · SharePreviewProvider(공유가 선언, 운영이
 * 구현)와 같은 모양이다. 회원 도메인에는 이제 operation 을 가리키는 import 가 없다.
 *
 * **구현체는 전용 빈이다**(operation 의 SubWorkOwnerLoadProvider). SubWorkServiceImpl 에 얹으면
 * 이 포트를 @MockitoBean 으로 바꾸는 테스트가 그 빈을 통째로 갈아 끼워, 같은 빈을
 * SubWorkService 로 주입받는 곳들이 함께 죽는다 — 실제로 그렇게 만들었다가 컨텍스트가 뜨지
 * 않았다.
 *
 * ── 그럼 MemberChangeService 를 다시 합쳐도 되는가 ──────────────
 * **아니다.** 빈 분리의 명분(순환 DI)은 사라졌지만 그 클래스가 지금 들고 있는 것은 그것만이
 * 아니다 — 등급·상태 변경과 이력 기록을 한 트랜잭션으로 묶는 책임이고, MemberServiceImpl 은
 * 조회·가입·수정을 맡는다. 순환이 풀렸다고 둘을 합치면 이 이슈의 범위(구조 정리)를 넘어
 * **기능 코드를 옮기는 변경**이 되므로 그대로 둔다. 분리의 근거만 갱신한다.
 */
public interface MemberSubWorkLoadProvider {

    /*
     * 이 회원이 담당 중인(완료되지 않은) 하위 업무 수. 회원이 없거나 담당 건이 없으면 0이다.
     *
     * **아무것도 바꾸지 않는다** — 담당 업무를 회수하거나 재배정하는 것은 운영 규칙이 필요한
     * 판단이라 범위 밖이고, 이 값은 화면이 사람에게 알릴 숫자일 뿐이다.
     */
    long countOngoingByOwner(Long ownerId);
}
