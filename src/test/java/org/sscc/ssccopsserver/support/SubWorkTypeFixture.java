package org.sscc.ssccopsserver.support;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;

/*
 * data.sql이 넣는 하위 업무 유형을 이름으로 찾아 준다.
 *
 * 식별자를 상수로 박지 않는 것은 시드가 sub_work_type_id를 지정하지 않기 때문이다 —
 * IDENTITY 컬럼에 값을 박아 넣으면 시퀀스가 1에 머물러 관리 화면(#43)이 유형을 추가하는
 * 순간 PK가 충돌한다. 그래서 시드 순서·식별자는 보장되지 않고, 이름만이 안정적인 키다.
 */
public final class SubWorkTypeFixture {

    /** 승인이 필요하고 지출로 분류되는 유형 */
    public static final String EXPENDITURE = "예산지출";

    /** 승인이 필요 없는 유형 */
    public static final String APPROVAL_FREE = "내부행사";

    /** 승인이 필요하고 승인자가 회장인 유형. 정족수 테스트가 이 유형을 정족수로 바꿔 쓴다 */
    public static final String ANNOUNCEMENT = "대외공지";

    private SubWorkTypeFixture() {}

    public static Long idOf(SubWorkTypeRepository repository, String typeName) {
        return entityOf(repository, typeName).getId();
    }

    public static SubWorkTypeEntity entityOf(SubWorkTypeRepository repository, String typeName) {
        return repository.findAll().stream()
                .filter(type -> typeName.equals(type.getTypeName()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "data.sql이 넣어야 할 하위 업무 유형이 없다: " + typeName));
    }
}
