package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionFileReferenceResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

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

    /*
     * 서명된 읽기 URL의 유효기간. 업로드(10분)보다 조금 길다 — 상세를 열어 둔 화면이 이미지를
     * 다시 그리는 데 쓰이고, 만료되면 상세를 다시 부르면 된다(그래서 남은 시간을 응답에 싣는다).
     *
     * 길게 두지 않는 이유는 업로드 URL과 같다: 이 URL은 그 자체로 사진을 읽을 수 있는 권한이라
     * 어딘가에 새어 나가면 만료까지 유효하다.
     */
    private static final Duration VIEW_URL_TTL = Duration.ofMinutes(15);

    private final S3Presigner r2Presigner;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;
    private final AuthorityPolicy authorityPolicy;
    private final EventParticipantRepository eventParticipantRepository;
    private final String bucketName;

    public SessionFileReferenceViewer(
            S3Presigner r2Presigner,
            AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy,
            AuthorityPolicy authorityPolicy,
            EventParticipantRepository eventParticipantRepository,
            @Value("${r2.bucket-name}") String bucketName) {
        this.r2Presigner = r2Presigner;
        this.academicProgramOwnershipPolicy = academicProgramOwnershipPolicy;
        this.authorityPolicy = authorityPolicy;
        this.eventParticipantRepository = eventParticipantRepository;
        this.bucketName = bucketName;
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
                VIEW_URL_TTL.toSeconds());
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
     * 읽기 서명. 업로드와 달리 contentType을 서명에 넣지 않는다 — 그쪽은 "이 형식만 올려도
     * 좋다"는 허가라 형식이 조건의 일부지만, 읽기는 이미 저장된 오브젝트를 그대로 내려받는
     * 것이라 조건에 넣을 것이 키뿐이다.
     *
     * **오브젝트가 실제로 있는지는 확인하지 않는다.** 서버가 PUT을 관측하지 않으므로 참조가
     * 실물을 가리킨다는 보장이 애초에 없고(FileReferenceEntity 주석), 확인하려면 조회마다
     * HeadObject가 한 번씩 더 나간다. 없으면 R2가 404를 돌려주고 화면은 다시 올린다.
     */
    private String presignGet(String objectKey) {
        GetObjectRequest getObjectRequest =
                GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();

        return r2Presigner
                .presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(VIEW_URL_TTL)
                                .getObjectRequest(getObjectRequest)
                                .build())
                .url()
                .toString();
    }
}
