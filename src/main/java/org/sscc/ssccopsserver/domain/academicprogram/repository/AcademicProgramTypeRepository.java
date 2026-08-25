package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;

public interface AcademicProgramTypeRepository
        extends JpaRepository<AcademicProgramTypeEntity, String> {

    /*
     * 목록 조회(#130)는 indctSeqno 순으로 고정한다. 비활성 유형도 관리 목록에는 남기므로
     * 필터를 두지 않는다 — 기획안 작성 화면의 활성 유형만 노출하는 것은 웹의 몫이다
     * (학술관리_데이터모델.md §... 웹 이슈 W1).
     */
    List<AcademicProgramTypeEntity> findAllByOrderByDisplayOrderAsc();

    /*
     * 이름으로 유형을 되찾는 자리 (#150 · 기획안 이관). 기획안 폼의 유형 선택지는 문자열
     * ("스터디")로 접수되는데 academic_program은 코드("STUDY")를 참조하므로, 그 다리가 필요하다.
     *
     * 코드 안의 상수 맵(스터디 → STUDY)을 두지 않은 것은 유형이 **배포 없이 시드 추가만으로
     * 늘어나야 하는 기준정보**이기 때문이다(#130) — 맵을 두면 세미나 유형 하나를 여는 데 서버
     * 배포가 함께 필요해진다.
     *
     * type_nm에는 UNIQUE가 없어(관리 화면에서 자유롭게 바꾸는 값이다) 같은 이름이 둘일 수 있다.
     * 그때 무엇을 고를지를 표시 순번 → 코드 순으로 못 박는 것은 결과가 요청마다 달라지지 않게
     * 하기 위해서다 — 이관은 되돌릴 수 없으므로 "그때그때 다른 유형으로 저장되는" 것이
     * 실패보다 나쁘다(MemberRoleRepository.findAllByNameOrderByIdAsc와 같은 판단).
     */
    Optional<AcademicProgramTypeEntity> findFirstByNameOrderByDisplayOrderAscCodeAsc(String name);
}
