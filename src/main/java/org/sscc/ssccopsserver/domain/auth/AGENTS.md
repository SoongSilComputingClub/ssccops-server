# domain/auth

이 도메인 규칙의 정본. 루트 AGENTS.md는 여기를 가리키기만 한다.

## 무엇이 있나

- `AuthController`(`GET /v1/auth/session`) · `AuthService` · 응답 `AuthSessionResponse`·`AuthUserResponse`. 엔티티도 리포지토리도 없다.
- 하지 않는 것: 로그인·로그아웃·토큰 발급(Supabase) · 회원 생성(가입 API의 몫) · 인가 판정(`global/security` + 회원 도메인 `AuthorityPolicy`).

## 규칙

- `domain/auth` — 로그인·로그아웃 자체는 Supabase(클라이언트) 책임이라 서버에 엔드포인트가 없다. 서버가 답하는 것은 "이 토큰이 우리 서비스의 누구인가" 하나뿐이고 그게 `GET /v1/auth/session`이다. **미가입 사용자에게도 200**으로 응답한다(`signedUp: false`, `member: null`) — 가입이 필요하다는 것도 정상적인 세션 상태이지 오류가 아니며, 403으로 끊으면 프론트가 가입 화면으로 갈 근거를 얻지 못한다. 응답의 `member` 블록(`MemberProfileResponse`)은 회원가입 응답과 같은 모양을 쓴다.

## 다른 도메인과 닿는 곳

- 회원: `member` 블록과 `capabilities`는 회원 도메인의 `MemberProfileResponse`·`AuthorityPolicy`에서 온다 — 여기서 다시 조립하지 않는다.
- JWT 검증(`iss`·`aud`)·principal(`AuthenticatedUser`)·`@CurrentMember`는 루트 AGENTS.md `global/security` 항목.

## 테스트 함정

- `AuthControllerTest` — 미가입 주체도 200이어야 한다(`@CurrentMember`를 쓰면 403으로 끊긴다).
