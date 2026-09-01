package org.sscc.ssccopsserver.domain.event.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.event.service.EventImageService;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 행사 이미지 읽기 (#208 · GET /public/v1/events/{eventId}/images/{fileName}).
 *
 * **버킷은 비공개다.** 행사 이미지와 학술 출석 인증사진이 R2 버킷 하나를 함께 쓰는데 R2의 공개
 * 접근은 버킷 단위라 접두사로 가를 수 없다(ssccops#156) — 버킷을 공개하면 얼굴이 찍힌 인증사진이
 * 함께 열리고, 그것은 #200이 비공개로 만들면서 세운 전제를 무너뜨린다. 그래서 행사 이미지도
 * 학술과 같은 방식으로 읽는다: 요청 시점에 짧은 서명 URL을 만든다.
 *
 * **저장되는 값은 서명 URL이 아니라 이 주소다.** 서명은 15분이면 만료되는데 행사 본문 마크다운에는
 * URL이 문자열로 굳는다 — 서명 URL을 그대로 저장하면 시간이 지난 본문이 통째로 깨지고, 편집
 * 화면이 본문을 불러 저장할 때 이미 만료된 주소가 다시 저장된다. 이 엔드포인트의 주소는 만료되지
 * 않으며, 열릴 때마다 서명이 새로 만들어진다.
 *
 * **302이지 프록시가 아니다.** 바이트는 R2에서 브라우저로 직접 가고 서버는 Location 헤더만
 * 내준다. 여기서 스트림을 중계하면 이미지가 전부 컨테이너 메모리를 지나게 되는데, 그것은 애초에
 * presigned URL 구조가 피하려던 비용이다(#107 — 512MB 컨테이너는 업로드 버퍼링만으로도 죽었다).
 * **이 핸들러에 InputStream·Resource를 돌려주는 코드를 더하지 말 것.**
 *
 * 경로가 /public/v1 아래인 것은 두 가지 때문이다. 하나는 익명 공개(D1)와 맞는다는 것 — 행사
 * 상세가 로그인 없이 열리는데 그 본문의 이미지가 토큰을 요구하면 화면이 성립하지 않는다. 다른
 * 하나는 카카오톡·에브리타임의 OG 크롤러가 이 주소를 열고 302를 따라간다는 것(D7)이다.
 * SecurityConfig의 permitAll이 접두사 한 줄이므로 여기에 핸들러를 더하는 것은 곧 permitAll을
 * 더하는 것이며, 그 질문("익명에게 나가도 되는가")의 답은 게시 여부 판정이 대신한다.
 *
 * 컨트롤러를 PublicEventController와 나눈 것은 응답의 성격이 다르기 때문이다 — 그쪽은 전부
 * ApiResponse로 감싼 JSON이고 이쪽은 본문이 없는 302다. 한 클래스에 두면 "이 컨트롤러의 응답은
 * ApiResponse다"라는 규칙에 예외가 생긴다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/v1/events")
public class PublicEventImageController {

    private final EventImageService eventImageService;

    /*
     * 302다(301이 아니다). 목적지가 15분마다 바뀌므로 브라우저나 중간 캐시가 이 주소를 새 주소로
     * **영구히** 기억하면 만료된 서명에 묶인다 — 영구한 것은 이 주소이지 목적지가 아니다.
     */
    @Operation(
            summary = "행사 이미지 읽기(익명)",
            description =
                    "행사 본문·썸네일 이미지를 서명된 R2 GET URL로 302 리다이렉트한다."
                            + " **인증이 필요 없다.** 이 주소 자체는 만료되지 않으며(본문 마크다운에"
                            + " 저장되는 값이다) 열릴 때마다 유효기간 15분짜리 서명이 새로 만들어진다."
                            + " 서버는 파일 바이트를 중계하지 않는다 — 브라우저가 R2에서 직접 받는다."
                            + " 게시되지 않은 행사(DRAFT·ARCHIVED)와 없는 행사는 모두 404"
                            + " EVENT_NOT_FOUND이고, 발급한 적 없는 형태의 파일명은 404"
                            + " EVENT_IMAGE_NOT_FOUND다.")
    @GetMapping("/{eventId}/images/{fileName}")
    public ResponseEntity<Void> redirectToImage(
            @PathVariable Long eventId, @PathVariable String fileName) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(eventImageService.viewUrlOf(eventId, fileName)))
                .build();
    }
}
