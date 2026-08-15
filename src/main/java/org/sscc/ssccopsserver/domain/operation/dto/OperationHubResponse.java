package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

/*
 * 운영 통합 조회 응답 (OPS-001 · GET /v1/operations · ssccops-web#63).
 *
 * '운영 통합' 화면 한 장이 이 호출 하나다 — 상단 유형 카드(건수), 좌측 목록(전체·업무·
 * 하위 업무·회의 탭), 우측 트리(업무→하위 업무 묶음 + 회의)를 전부 이 응답으로 그린다.
 *
 * 정의서 원안의 판별 유니온(data[].operationType + detail) 대신 유형별 배열 세 개를 내린다 —
 * 세 유형은 화면이 그리는 값이 서로 달라 한 배열에 섞으면 프론트가 detail 스키마 세 벌을
 * 다시 판별해야 하고, 이미 나가 있는 목록 DTO(OPS-020·OPS-008·OPS-031)를 그대로 재사용할
 * 수도 없게 된다. 탭 필터·트리 묶음은 화면이 이 배열 위에서 한다(대시보드 OPS-038과 같은
 * 판단). 하위 업무 행의 work.workId가 트리의 연결 고리다.
 *
 * 커서 페이징이 없다 — 목록과 트리가 같은 데이터를 다른 모양으로 그리는 화면이라 페이지를
 * 나누면 트리가 잘린다(회의 목록 OPS-031이 페이징을 두지 않은 것과 같은 판단).
 */
public record OperationHubResponse(
        List<WorkListItemResponse> works,
        List<SubWorkSummaryResponse> subWorks,
        List<MeetingListItemResponse> meetings) {}
