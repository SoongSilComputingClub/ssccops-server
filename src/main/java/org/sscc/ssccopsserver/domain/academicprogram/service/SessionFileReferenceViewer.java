package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionFileReferenceResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;

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
    private final AuthorityPolicy authorityPolicy;
    private final EventParticipantRepository eventParticipantRepository;

    public SessionFileReferenceViewer(
            FilePresigner filePresigner,
            AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy,
            AuthorityPolicy authorityPolicy,
            EventParticipantRepository eventParticipantRepository) {
        this.filePresigner = filePresigner;
        this.academicProgramOwnershipPolicy = academicProgramOwnershipPolicy;
        this.authorityPolicy = authorityPolicy;
        this.eventParticipantRepository = eventParticipantRepository;
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
     * 그 활동의 관계자인가 (#200 결정 2).
     *
     * 팀원(event_ptcp) · 스터디장/팀장 · 학술국장(ACADEMIC_PROGRAM_MANAGE) 셋이다. **회차 상세
     * 자체는 종전대로 인증만 요구한다** — 상세를 관계자로 좁히면 팀원이 회차 이력을 못 보게 되고
     * (그래서 애초에 소유권을 걸지 않았다) 좁혀야 하는 것은 사진 하나뿐이다.
     *
     * 순서는 싼 것부터다: 소유권은 이미 읽어 둔 엔티티의 식별자 비교라 조회가 없고, 명단 조회는
     * 한 번, 권한 조회는 앞의 둘이 모두 아닐 때만 돈다(requireLeaderOrManager와 같은 태도).
     *
     * 명단은 **상태를 가리지 않는다** — 취소(CANCELLED)된 참가자도 그 활동에 있었던 사람이고,
     * 명단이 활동 이력으로 영구 보존되는 것(D16)과 같은 기준이다.
     */
    private boolean canView(AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (requester == null) {
            return false;
        }
        if (academicProgramOwnershipPolicy.isLeader(academicProgram, requester)) {
            return true;
        }
        if (eventParticipantRepository.existsByEventAndMember(
                academicProgram.getEvent(), requester)) {
            return true;
        }
        return authorityPolicy.hasAuthority(
                requester.getId(), AuthorityCode.ACADEMIC_PROGRAM_MANAGE);
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
