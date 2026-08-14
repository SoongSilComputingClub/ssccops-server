package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;

/*
 * 응답 자동 저장 요청 (#36 · PUT /v1/forms/{formId}/responses/draft).
 *
 * 제출 요청(FormResponseSubmitRequest)과 본문 모양이 같다 — 답(rspnsCn)만 담고 응답자·상태·
 * 일시는 전부 서버가 정한다. 같은 모양인데도 record를 따로 두는 것은 두 요청의 계약이 앞으로
 * 같이 움직이지 않기 때문이다. 예컨대 제출에는 확인 문구·동의 항목이 붙을 수 있지만 자동
 * 저장에는 붙을 이유가 없고, 하나를 공유하면 그 필드가 자동 저장 요청에도 딸려 들어간다.
 *
 * PUT은 부분 갱신이 아니라 통째로 덮어쓰기다. 본문에 없는 문항의 답은 "안 바뀐 것"이 아니라
 * "지워진 것"으로 취급한다 — 웹은 작성 중인 폼 상태 전체를 들고 있다가 그대로 보내므로 이 편이
 * 화면과 저장된 값을 어긋나지 않게 유지하며, PUT의 뜻(자원을 이 표현으로 대체한다)과도 맞다.
 *
 * 빈 객체({})는 허용한다. 첫 타이핑 전에도 저장할 자리가 있어야 웹이 "마지막 저장 시각"을
 * 처음부터 표시할 수 있고, 답을 전부 지운 상태도 정상적인 작성 중 상태다.
 */
public record FormResponseDraftRequest(@NotNull ResponseContent rspnsCn) {}
