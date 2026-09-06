package org.sscc.ssccopsserver.domain.event.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.sscc.ssccopsserver.domain.file.code.ImageFileType;

/*
 * 행사 이미지가 버킷의 어디에 있고 우리 도메인의 어느 주소로 읽히는가 (#161 · #208).
 *
 * **발급 쪽과 읽기 쪽이 이 한 곳을 함께 쓴다.** 키 규칙을 두 벌로 적으면 한쪽만 바뀌는 날
 * 발급한 주소가 가리키는 오브젝트가 사라진다 — 게다가 읽기 쪽은 파일명을 **요청에서** 받으므로
 * 그 값이 키에 그대로 들어가면 `../`나 경로 구분자로 버킷 안의 다른 오브젝트를 지목할 수 있다.
 * 학술 인증사진이 같은 버킷에 있다는 것이 이 이슈의 출발점이므로(ssccops#156) 그 조작은
 * 곧 남의 얼굴 사진이다.
 *
 * 그래서 파일명은 **우리가 발급한 형태만** 통과시킨다: 소문자 UUID + `.` + ImageFileType이
 * 정한 확장자. 정규화(대문자를 소문자로 바꿔 준다든지)를 하지 않는 것은 그 관용이 곧 "무엇이
 * 키가 되는가"를 흐리기 때문이고, 우리가 마크다운에 넣는 값은 언제나 이 형태다.
 */
public final class EventImageLocation {

    /** 버킷 안의 키 접두사. 학술 인증사진(academic-programs/…)과 갈리는 자리다 */
    private static final String KEY_FORMAT = "events/%d/%s";

    /** 우리 도메인의 영구 읽기 주소. 익명 공개이므로 /public/v1 아래다 */
    private static final String PUBLIC_PATH_FORMAT = "/public/v1/events/%d/images/%s";

    /*
     * `{소문자 UUID}.{확장자}`. UUID.randomUUID().toString()이 내는 형태 그대로이며,
     * 확장자 자체의 허용 여부는 정규식이 아니라 ImageFileType 표가 판단한다(형식을 늘리는
     * 자리를 한 곳으로 묶는다).
     */
    private static final Pattern FILE_NAME_PATTERN =
            Pattern.compile(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]([a-z0-9]+)$");

    private static final Set<String> ALLOWED_EXTENSIONS =
            Arrays.stream(ImageFileType.values())
                    .map(ImageFileType::getExtension)
                    .collect(Collectors.toUnmodifiableSet());

    private EventImageLocation() {}

    /*
     * 새 파일명을 만든다. 원본 파일명을 쓰지 않는 이유는 ImageFileType 주석에 있다 —
     * 한글·공백·`../`가 키가 되고 대소문자만 다른 같은 형식이 여러 벌로 쌓인다.
     */
    public static String newFileName(ImageFileType imageType) {
        return UUID.randomUUID() + "." + imageType.getExtension();
    }

    /** 우리가 발급한 형태인가. 읽기 경로가 키를 만들기 전에 묻는 유일한 질문이다 */
    public static boolean isValidFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
        return matcher.matches() && ALLOWED_EXTENSIONS.contains(matcher.group(1));
    }

    /*
     * 오브젝트 키. **파일명을 다시 검사한다** — 부르는 쪽이 이미 검사했더라도, 검사를 통과하지
     * 않은 값으로 키가 만들어지는 경로가 하나라도 생기면 그것이 곧 키 조작이다.
     */
    public static String objectKeyOf(long eventId, String fileName) {
        return KEY_FORMAT.formatted(eventId, requireIssuedFileName(fileName));
    }

    /** 행사 본문 마크다운에 굳는 영구 주소의 경로 부분. 호스트는 AppPublicBaseUrl이 붙인다 */
    public static String publicPathOf(long eventId, String fileName) {
        return PUBLIC_PATH_FORMAT.formatted(eventId, requireIssuedFileName(fileName));
    }

    /*
     * 글에 박힌 이 행사의 이미지 주소에서 파일명을 긁어낸다 (ssccops#188).
     *
     * **마크다운을 파싱하지 않는다.** 우리가 조립한 주소의 모양을 그대로 되읽을 뿐이라
     * `![](...)`든 `<img>`든 맨 URL이든 상관이 없고, 편집기가 문법을 바꿔도 따라오지 않아도
     * 된다. 조립하는 자리(publicPathOf)와 되읽는 자리를 같은 클래스에 두는 것이 요점이며,
     * 갈라지면 지워야 할 것을 못 찾거나 엉뚱한 것을 지운다.
     *
     * **행사 번호를 패턴에 박아 이 행사의 것만 찾는다.** 남의 행사 주소가 본문에 복사돼 있어도
     * (운영자가 손으로 옮긴 경우) 그것은 이 행사의 소유가 아니므로 후보에 들지 않는다.
     *
     * 호스트는 보지 않는다 — app.public-base-url이 환경마다 다르고 바뀌기도 하는데, 그 값이
     * 달라졌다고 이미 본문에 굳은 주소를 못 알아보면 그 순간부터 정리가 멈춘다.
     */
    public static Set<String> fileNamesReferencedIn(long eventId, String... texts) {
        Set<String> fileNames = new LinkedHashSet<>();
        Pattern pattern =
                Pattern.compile(
                        Pattern.quote(PUBLIC_PATH_FORMAT.formatted(eventId, ""))
                                + "([0-9a-f-]+[.][a-z0-9]+)");
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                // 우리가 발급한 형태만 남긴다 — 아니면 우리 오브젝트가 아니다
                if (isValidFileName(candidate)) {
                    fileNames.add(candidate);
                }
            }
        }
        return fileNames;
    }

    private static String requireIssuedFileName(String fileName) {
        if (!isValidFileName(fileName)) {
            throw new IllegalArgumentException("행사 이미지 파일명 형태가 아닙니다: " + fileName);
        }
        return fileName;
    }
}
