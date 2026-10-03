-- 회의 안건은 운영 건을 가리키거나 제목만 갖는다 — 드래프트 안건을 되살린다 (#625 · ssccops#568 · ADR-0059).
--
-- V24(ADR-0055)가 지운 것을 역순으로 되돌린다: agnd_nm 을 다시 만들고 oper_id 의 NOT NULL 을 푼다.
-- 길이·타입은 V24 이전 정의(V1 baseline 의 character varying(100))와 같다.
--
-- 그때는 없던 것을 하나 더한다 — **«agnd_nm 과 oper_id 중 정확히 하나»를 DB 가 CHECK 로 강제한다.**
-- V24 이전에는 그 상호 배타가 서버(@AssertTrue)와 데이터사전에만 있었고 DB 는 둘 다 NULL 인 행도
-- 받았다. 드래프트가 업무로 승격되면 oper_id 를 채우고 agnd_nm 을 비우는데, 그 둘이 어긋난 행이
-- 남으면 «제목 없음 · 연결 없음» 안건이 다시 생긴다 — 그것을 화면이 아니라 여기서 막는다.
--
-- 제약 이름은 baseline 의 PostgreSQL 기본 이름 꼴(<표>_<열>_check)을 따른다.
--
-- 기존 행은 전부 oper_id 가 채워져 있고(V24 가 NOT NULL 을 걸었다) agnd_nm 은 새 컬럼이라 NULL 이므로
-- CHECK 가 그대로 성립한다. V24 가 지운 행은 돌아오지 않는다(ADR-0059 «포기하는 것»).

ALTER TABLE mtg_dtl ADD COLUMN agnd_nm character varying(100);

ALTER TABLE mtg_dtl ALTER COLUMN oper_id DROP NOT NULL;

ALTER TABLE mtg_dtl
    ADD CONSTRAINT mtg_dtl_agnd_nm_oper_id_check
    CHECK ((agnd_nm IS NULL) <> (oper_id IS NULL));
