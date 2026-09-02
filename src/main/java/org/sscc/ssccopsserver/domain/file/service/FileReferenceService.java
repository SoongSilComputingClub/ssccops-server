package org.sscc.ssccopsserver.domain.file.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;

import lombok.RequiredArgsConstructor;

/*
 * 파일 참조 행을 다루는 유일한 자리 (#220).
 *
 * **이 서비스가 아는 것은 "무엇이 버킷의 어디에 있는가" 하나다.** 누가 그 파일을 볼 수 있는지,
 * 화면에 어떤 주소로 내려가는지, 대상당 몇 건까지 허용되는지는 전부 각 도메인이 답한다 —
 * 그 판정들을 대상 구분(FileTargetType)별 분기로 여기에 모으면 인가 규칙이 두 벌이 되고,
 * 두 번째 벌은 화면과 갈린 채로 조용히 자란다(학술 인증사진은 팀원·리더·학술국장만,
 * 행사 이미지는 게시된 행사면 익명 — 애초에 한 표에 들어가지 않는다).
 *
 * **대상 행을 잠그지 않는다.** 동시 발급을 막는 잠금은 대상 쪽 행(sesn 등)에 걸어야 하고,
 * 그 테이블을 아는 것은 도메인이다(SessionFileReferenceServiceImpl.upsert 주석). 여기서
 * file_rfrnc를 잠그면 참조가 아직 없는 대상에서는 잠글 행 자체가 없어 아무것도 막지 못한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FileReferenceService {

    private final FileReferenceRepository fileReferenceRepository;

    public Optional<FileReferenceEntity> findByTarget(FileTargetType targetType, Long targetId) {
        return fileReferenceRepository.findByTargetTypeAndTargetId(targetType, targetId);
    }

    /*
     * 대상당 1건인 도메인의 재업로드 (#137 설계 결정 #1). 지웠다 넣지 않는 것은
     * fileReferenceId가 바뀌면 화면이 들고 있던 식별자가 무효가 되기 때문이다.
     *
     * **부르기 전에 대상 행을 잠가야 한다** — 참조가 아직 없는 대상에 두 요청이 동시에 닿으면
     * 둘 다 "없다"를 보고 각자 INSERT 한다. 그 잠금이 도메인 쪽에 있는 이유는 위 주석에 있다.
     */
    @Transactional
    public FileReferenceEntity upsert(FileTargetType targetType, Long targetId, String objectKey) {
        return findByTarget(targetType, targetId)
                .map(
                        existing -> {
                            existing.changeFileUrl(objectKey);
                            return existing;
                        })
                .orElseGet(
                        () ->
                                fileReferenceRepository.saveAndFlush(
                                        FileReferenceEntity.of(targetType, targetId, objectKey)));
    }
}
