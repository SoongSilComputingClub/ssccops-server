package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionFileReferenceResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 출석 인증사진을 읽는 자리 (#200).
 *
 * **버킷은 비공개다.** #137은 공개 도메인에 올려 두고 저장된 URL을 그대로 화면에 내리는 구조를
 * 전제했는데, 인증사진은 얼굴이 찍힌 사진이라 URL만 알면 열리는 자리에 둘 값이 아니다. 그래서
 * 읽기도 업로드와 같은 방식이 됐다 — 조회 시점에 짧은 서명 URL을 발급하고, 바이트는 브라우저가
 * R2에서 직접 받는다(서버는 여전히 파일을 만지지 않는다).
 *
 * 하는 일은 둘이며 순서가 있다: **누구에게 내줄지 정하고, 그 다음에 서명한다.** 자격이 없으면
 * 서명 자체를 만들지 않는다 — 발급은 곧 읽기 권한이라, 만들어 두고 응답에서 빼는 구조는 한 줄만
 * 어긋나도 그대로 새어 나간다(업로드 URL 발급을 소유권 뒤에 두는 것과 같은 태도).
 */
@Component
public class SessionFileReferenceViewer {

    private final FilePresigner filePresigner;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;

    public SessionFileReferenceViewer(
            FilePresigner filePresigner,
            AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy) {
        this.filePresigner = filePresigner;
        this.academicProgramOwnershipPolicy = academicProgramOwnershipPolicy;
    }

    /*
     * 회차 상세에 실을 사진 블록. 사진이 없거나 요청자가 관계자가 아니면 null이다.
     *
     * **두 경우의 응답이 같다.** 사진 유무는 관계자가 아닌 사람에게 알릴 값이 아니고, "블록이
     * 없으면 사진이 없는 것"이라는 규칙이 #137부터 있어 화면이 새 분기를 만들지 않아도 된다.
     */
    public SessionFileReferenceResponse viewOf(
            AcademicProgramEntity academicProgram,
            FileReferenceEntity fileReference,
            MemberEntity requester) {
        if (fileReference == null || !canView(academicProgram, requester)) {
            return null;
        }
        return new SessionFileReferenceResponse(
                fileReference.getId(),
                presignGet(fileReference.objectKey()),
                filePresigner.viewUrlTtlSeconds());
    }

    /*
     * 그 활동의 관계자인가 (#200 결정 2). **판정은 AcademicProgramOwnershipPolicy.isRelated 한 벌이다**
     * (#688) — 여기 있던 구현(리더 · 팀원 · 학술국장)을 그리로 올렸다. 회차 상세·출석부·팀원 명단이
     * 같은 판정으로 관계자에게만 열리게 되면서(그전에는 «인증만»이었다) 사진만 따로 판정하면 두 벌이
     * 어긋나 «출석부는 막히는데 사진은 열린다»(또는 그 반대)가 생길 자리가 된다.
     *
     * 회차 상세가 이제 관계자에게만 열리므로 이 자리를 지나는 요청자는 대개 이미 관계자다. 그래도
     * 판정을 남기는 것은 서명이 곧 읽기 권한이라 «판정 없이 서명하는 경로»를 만들지 않기 위해서다.
     */
    private boolean canView(AcademicProgramEntity academicProgram, MemberEntity requester) {
        return academicProgramOwnershipPolicy.isRelated(academicProgram, requester);
    }

    /*
     * 읽기 서명 (#220부터 FilePresigner에 위임한다). 업로드 URL 발급(#137)도 이 메서드를 쓴다 —
     * 그 경로는 이미 소유권을 통과한 뒤라 자격을 다시 묻지 않지만, **학술 인증사진의 서명이
     * 나가는 자리는 여전히 이 클래스 하나**다. 서명 자체가 공통으로 내려간 뒤에도 이 자리를
     * 남겨 두는 것은, 자격 판정과 서명이 붙어 있어야 "판정 없이 서명하는 경로"가 생기지 않기
     * 때문이다(발급이 곧 읽기 권한이다).
     */
    public String presignGet(String objectKey) {
        return filePresigner.presignGet(objectKey);
    }
}
