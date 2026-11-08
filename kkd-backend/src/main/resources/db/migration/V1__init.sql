CREATE TABLE units (
  id BIGSERIAL PRIMARY KEY, name VARCHAR(150) NOT NULL, parent_id BIGINT REFERENCES units(id)
);
CREATE TABLE goals (
  id BIGSERIAL PRIMARY KEY, unit_id BIGINT NOT NULL REFERENCES units(id),
  title VARCHAR(255) NOT NULL, owner VARCHAR(150), parent_goal_id BIGINT REFERENCES goals(id),
  status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', deadline DATE
);
CREATE TABLE key_results (
  id BIGSERIAL PRIMARY KEY, goal_id BIGINT NOT NULL REFERENCES goals(id),
  title VARCHAR(255) NOT NULL, target_value NUMERIC(14,2), actual_value NUMERIC(14,2),
  weight NUMERIC(5,2) NOT NULL DEFAULT 0, fact_updated_at TIMESTAMP
);
CREATE TABLE kkd_rules (
  code VARCHAR(50) PRIMARY KEY, title VARCHAR(255) NOT NULL, severity VARCHAR(20) NOT NULL
);
CREATE TABLE kkd_runs (
  id BIGSERIAL PRIMARY KEY, status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED', snapshot_date DATE NOT NULL, compared_to_id BIGINT REFERENCES kkd_runs(id),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE kkd_issues (
  id BIGSERIAL PRIMARY KEY, run_id BIGINT NOT NULL REFERENCES kkd_runs(id) ON DELETE CASCADE,
  goal_id BIGINT NOT NULL REFERENCES goals(id), rule_code VARCHAR(50) NOT NULL REFERENCES kkd_rules(code),
  severity VARCHAR(20) NOT NULL, description TEXT NOT NULL
);
CREATE INDEX idx_goals_unit ON goals(unit_id);
CREATE INDEX idx_issues_run ON kkd_issues(run_id);
CREATE INDEX idx_issues_rule ON kkd_issues(rule_code);

INSERT INTO units(name) VALUES ('Департамент разработки'), ('Департамент аналитики');
INSERT INTO units(name, parent_id) VALUES ('Backend-команда', 1), ('Команда данных', 2);

INSERT INTO kkd_rules(code,title,severity) VALUES
 ('GOAL_NO_OWNER','Цель без ответственного','critical'),
 ('GOAL_NO_KR','Активная цель без ключевых результатов','critical'),
 ('KR_WEIGHTS_SUM','Сумма весов КР не равна 100%','major'),
 ('KR_NO_TARGET','У КР не заполнено целевое значение','major');

-- 24 демонстрационные цели в двух подразделениях; часть намеренно содержит нарушения.
INSERT INTO goals(unit_id,title,owner,parent_goal_id,status,deadline)
SELECT CASE WHEN n <= 12 THEN 3 ELSE 4 END,
       'Цель ' || n,
       CASE WHEN n IN (2,7,14,21) THEN NULL ELSE 'Сотрудник ' || n END,
       NULL,
       'ACTIVE',
       CURRENT_DATE + (n || ' days')::interval
FROM generate_series(1,24) AS n;

-- По два КР для большинства целей; для целей 3 и 16 КР отсутствуют.
INSERT INTO key_results(goal_id,title,target_value,actual_value,weight,fact_updated_at)
SELECT g.id, 'Ключевой результат A', CASE WHEN g.id % 5 = 0 THEN NULL ELSE 100 END,
       40, CASE WHEN g.id % 4 = 0 THEN 30 ELSE 50 END, CURRENT_TIMESTAMP
FROM goals g WHERE g.id NOT IN (3,16);
INSERT INTO key_results(goal_id,title,target_value,actual_value,weight,fact_updated_at)
SELECT g.id, 'Ключевой результат B', 100, 70, 50, CURRENT_TIMESTAMP
FROM goals g WHERE g.id NOT IN (3,16);

-- Первый прогон фиксирует исходное состояние.
INSERT INTO kkd_runs(snapshot_date) VALUES (CURRENT_DATE - 7);
INSERT INTO kkd_issues(run_id,goal_id,rule_code,severity,description)
SELECT 1,g.id,'GOAL_NO_OWNER','critical','У цели не указан ответственный'
FROM goals g WHERE g.owner IS NULL;
INSERT INTO kkd_issues(run_id,goal_id,rule_code,severity,description)
SELECT 1,g.id,'GOAL_NO_KR','critical','У активной цели нет ключевых результатов'
FROM goals g WHERE g.status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM key_results k WHERE k.goal_id=g.id);
INSERT INTO kkd_issues(run_id,goal_id,rule_code,severity,description)
SELECT 1,g.id,'KR_WEIGHTS_SUM','major','Сумма весов ключевых результатов не равна 100%'
FROM goals g WHERE g.status='ACTIVE' AND EXISTS (
  SELECT 1 FROM key_results k WHERE k.goal_id=g.id GROUP BY k.goal_id HAVING SUM(k.weight) <> 100
);
INSERT INTO kkd_issues(run_id,goal_id,rule_code,severity,description)
SELECT 1,g.id,'KR_NO_TARGET','major','У ключевого результата не заполнено целевое значение'
FROM goals g WHERE g.status='ACTIVE' AND EXISTS (
  SELECT 1 FROM key_results k WHERE k.goal_id=g.id AND k.target_value IS NULL
);

-- Перед вторым прогоном исправляем одну ошибку, чтобы была видна дельта.
UPDATE goals SET owner = 'Исправленный ответственный' WHERE id = 2;
INSERT INTO kkd_runs(snapshot_date, compared_to_id) VALUES (CURRENT_DATE, 1);
INSERT INTO kkd_issues(run_id,goal_id,rule_code,severity,description)
SELECT 2,g.id,'GOAL_NO_OWNER','critical','У цели не указан ответственный'
FROM goals g WHERE g.owner IS NULL;
INSERT INTO kkd_issues(run_id,goal_id,rule_code,severity,description)
SELECT 2,g.id,'GOAL_NO_KR','critical','У активной цели нет ключевых результатов'
FROM goals g WHERE g.status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM key_results k WHERE k.goal_id=g.id);
INSERT INTO kkd_issues(run_id,goal_id,rule_code,severity,description)
SELECT 2,g.id,'KR_WEIGHTS_SUM','major','Сумма весов ключевых результатов не равна 100%'
FROM goals g WHERE g.status='ACTIVE' AND EXISTS (
  SELECT 1 FROM key_results k WHERE k.goal_id=g.id GROUP BY k.goal_id HAVING SUM(k.weight) <> 100
);
INSERT INTO kkd_issues(run_id,goal_id,rule_code,severity,description)
SELECT 2,g.id,'KR_NO_TARGET','major','У ключевого результата не заполнено целевое значение'
FROM goals g WHERE g.status='ACTIVE' AND EXISTS (
  SELECT 1 FROM key_results k WHERE k.goal_id=g.id AND k.target_value IS NULL
);
