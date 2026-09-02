package org.sscc.ssccopsserver.domain.academicprogram.dto;

/*
 * 회차 상세(#135)의 출석 인증사진(회차당 1건, 학술관리_데이터모델.md §2 file_rfrnc).
 *
 * **fileUrlAddr은 만료가 있는 서명된 URL이다** (#200에서 뜻이 바뀌었다). 버킷이 비공개라
 * 저장된 주소를 그대로 내려도 열리지 않으므로, 조회 시점에 발급한 presigned GET URL을 싣는다
 * (발급 자리는 SessionFileReferenceViewer). 필드 이름을 그대로 둔 것은 화면이 하는 일이
 * 달라지지 않기 때문이다 — 받은 값을 <img src>에 넣는다.
 *
 * expiresInSeconds는 그 URL이 몇 초 뒤에 만료되는지다. 화면을 오래 열어 둔 뒤 이미지를 다시
 * 그리면 깨질 수 있고, 그때 할 일은 상세를 다시 부르는 것이다 — 서버가 그 시점을 알려 주지
 * 않으면 웹은 깨진 이미지를 보고서야 알게 된다.
 *
 * 이 블록이 null인 경우는 둘이며 **응답만으로는 구별되지 않는다**: 사진이 아직 없는 회차이거나,
 * 요청자가 그 활동의 관계자가 아니거나(#200 · 팀원·스터디장·학술국장만 받는다). 사진 유무는
 * 관계자가 아닌 사람에게 알릴 값이 아니라 같은 응답으로 둔다. 필드만 비운 껍데기를 내리지 않는
 * 것은 #137부터의 규칙이다 — 화면이 "사진 있음"을 판단하는 자리를 하나로 유지한다.
 *
 * **URL이 가리키는 오브젝트가 실제로 있다는 보장은 없다.** 참조 행은 업로드 허가를 발급할 때
 * 태어나고 서버는 PUT을 관측하지 않는다(#137, FileReferenceEntity 주석) — 없으면 R2가 404를
 * 돌려주고 화면은 다시 올린다(재업로드가 UPSERT다).
 */
public record SessionFileReferenceResponse(
        Long fileReferenceId, String fileUrlAddr, long expiresInSeconds) {}
