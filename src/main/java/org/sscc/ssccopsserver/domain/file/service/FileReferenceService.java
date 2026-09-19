package org.sscc.ssccopsserver.domain.file.service;

import java.util.List;
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

    /*
     * 갈아 끼운 옛 오브젝트를 지우는 자리 (ssccops#188). 파일 도메인이 이것을 갖는 것은
     * 키를 바꾸는 자리가 여기 하나뿐이기 때문이다 — 대상 도메인마다 지우게 하면 대상이 늘 때
     * 마다 한 곳씩 잊는다.
     */
    private final FileEraser fileEraser;

    public Optional<FileReferenceEntity> findByTarget(FileTargetType targetType, Long targetId) {
        return fileReferenceRepository.findByTargetTypeAndTargetId(targetType, targetId);
    }

    /*
     * 대상당 여러 건인 대상의 참조 목록 (ssccops#381 · CONTENT_POST 갤러리). 단건 findByTarget를
     * 그 대상에 쓰면 두 번째 행부터 조용히 사라진다(저장소 주석) — 갤러리는 반드시 이쪽이다.
     */
    public List<FileReferenceEntity> findAllByTarget(FileTargetType targetType, Long targetId) {
        return fileReferenceRepository.findAllByTargetTypeAndTargetIdOrderByIdAsc(
                targetType, targetId);
    }

    public Optional<FileReferenceEntity> findOneOfTarget(
            FileTargetType targetType, Long targetId, Long fileId) {
        return fileReferenceRepository.findByIdAndTargetTypeAndTargetId(
                fileId, targetType, targetId);
    }

    /*
     * 참조 한 건 추가 (ssccops#381). upsert와 달리 옛 행을 갈아 끼우지 않는다 — 다중 첨부 대상의
     * «한 장 더»다. 오브젝트가 실제로 올라왔는지는 여기서도 모른다(발급형 업로드는 PUT이
     * 서버를 지나지 않는다) — 올라오지 않은 행은 대상 도메인이 지우는 경로로 정리한다.
     */
    @Transactional
    public FileReferenceEntity add(FileTargetType targetType, Long targetId, String objectKey) {
        return fileReferenceRepository.saveAndFlush(
                FileReferenceEntity.of(targetType, targetId, objectKey));
    }

    /*
     * 참조 한 건 삭제 (ssccops#381). deleteByTarget이 대상의 전부를 지우는 것과 달리 지목한
     * 한 장만 지우며, 오브젝트는 같은 규칙으로 커밋 뒤에 지운다(FileEraser — 잘못된 데이터보다
     * 고아가 낫다). 대상 조건이 함께 걸려 남의 포스트의 파일은 여기 오지 않는다(없으면 false).
     */
    @Transactional
    public boolean deleteOneOfTarget(FileTargetType targetType, Long targetId, Long fileId) {
        return findOneOfTarget(targetType, targetId, fileId)
                .map(
                        reference -> {
                            fileReferenceRepository.delete(reference);
                            fileEraser.eraseAfterCommit(reference.objectKey());
                            return true;
                        })
                .orElse(false);
    }

    /**
     * 대상이 사라질 때 그 참조와 오브젝트를 함께 지운다 (#401).
     *
     * <p><b>파일 도메인이 갖는 이유는 {@link #upsert}와 같다</b> — 키를 아는 자리가 여기 하나뿐이고, 대상 도메인이 각자 지우게 하면 대상이 늘
     * 때마다 한 곳씩 잊는다(그때 남는 것은 «행은 없는데 버킷에 있는» 오브젝트라 아무도 찾지 못한다).
     *
     * <p><b>오브젝트는 커밋 뒤에 지운다</b>({@link FileEraser}) — 안에서 지우면 롤백된 삭제 뒤에 «행은 있는데 파일이 없는» 조합이 남는다.
     *
     * <p>참조가 없으면 아무 일도 하지 않는다. 업로드가 중간에 실패해 행만 있는 대상이 정상적으로 있을 수 있고, 그것은 지울 것이 없다는 뜻이지 오류가 아니다.
     *
     * <p><b>소프트 삭제 도메인은 이것을 부르지 않는다</b> — 폼(#329)·행사(#347)는 되살아날 수 있어 오브젝트가 남아 있어야 한다. 부르는 쪽은 되살리기가
     * 없는 하드 삭제다(규정 문서 · ADR-0029).
     */
    @Transactional
    public void deleteByTarget(FileTargetType targetType, Long targetId) {
        findByTarget(targetType, targetId)
                .ifPresent(
                        reference -> {
                            fileReferenceRepository.delete(reference);
                            fileEraser.eraseAfterCommit(reference.objectKey());
                        });
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
                            /*
                             * **옛 오브젝트를 지운다** (ssccops#188). 키를 갈아 끼우는 순간 옛
                             * 것은 이미 앱에서 닿을 수 없으므로 — 이 행이 유일한 참조다 —
                             * 지워도 잃는 것이 없다. 발급만 받고 업로드를 포기해도 마찬가지다:
                             * 행은 이미 새 키를 가리켜 옛 사진은 어차피 보이지 않는다.
                             *
                             * 같은 키로의 재저장은 지나간다. 그것까지 지우면 방금 올린 것을
                             * 지우는 셈이다.
                             */
                            String previousKey = existing.objectKey();
                            existing.changeFileUrl(objectKey);
                            if (!objectKey.equals(previousKey)) {
                                fileEraser.eraseAfterCommit(previousKey);
                            }
                            return existing;
                        })
                .orElseGet(
                        () ->
                                fileReferenceRepository.saveAndFlush(
                                        FileReferenceEntity.of(targetType, targetId, objectKey)));
    }
}
