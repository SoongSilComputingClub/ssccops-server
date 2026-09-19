package org.sscc.ssccopsserver.domain.content.service;

import java.util.UUID;

import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.code.ImageFileType;

/*
 * 갤러리 오브젝트 키와 공개 읽기 주소를 조립하는 유일한 자리 (ssccops#381 · EventImageLocation
 * 선례). 발급(ContentPostImageServiceImpl)과 리다이렉트(PublicContentServiceImpl)가 함께 쓴다 —
 * 두 곳이 각자 문자열을 이어 붙이면 한쪽만 바뀐다.
 *
 * 키는 content-posts/{postId}/{uuid}.{ext}다(접두사는 FileTargetType.CONTENT_POST가 갖는다).
 * 파일명이 UUID인 것은 같은 파일을 두 번 올려도 앞의 것이 덮이지 않게 하려는 것이고, 확장자는
 * 원본 파일명이 아니라 허용 목록(ImageFileType)의 값이다.
 *
 * **공개 주소는 파일명이 아니라 file_rfrnc의 id로 연다** — 행사 이미지와 갈리는 지점이다.
 * 행사는 DB에 행이 없어 파일명이 곧 열쇠였지만 갤러리는 행이 있고, 지운 장(행 삭제)이 주소로는
 * 계속 열리는 상태를 만들지 않으려면 열쇠가 행이어야 한다. 그래서 키 조립에는 검증할 파일명
 * 규칙이 없다 — 키는 발급할 때 한 번 만들어 행에 저장되고, 읽을 때는 행에서 꺼낸다(../ 가 낄
 * 자리가 없다).
 *
 * 주소의 postId는 slug가 아니라 숫자 id다. slug는 바뀔 수 있고(ContentPageEntity.update 주석)
 * 본문 마크다운에 굳은 이미지 주소가 slug 변경에 따라 깨져서는 안 된다.
 */
public final class ContentImageLocation {

    private static final String KEY_FORMAT =
            FileTargetType.CONTENT_POST.getObjectKeyPrefix() + "%d/%s";

    private static final String PUBLIC_PATH_FORMAT = "/public/v1/posts/%d/images/%d";

    private ContentImageLocation() {}

    public static String newObjectKey(long postId, ImageFileType imageType) {
        return KEY_FORMAT.formatted(postId, UUID.randomUUID() + "." + imageType.getExtension());
    }

    public static String publicPathOf(long postId, long fileId) {
        return PUBLIC_PATH_FORMAT.formatted(postId, fileId);
    }
}
