-- data2flow_ai 초기 스키마(소유: data2flow-ai). 정본 초안: data2flow-docs design/erd/ddl/31-ai.sql
-- 초안과 다른 점(M6 구현, 문서 갱신 요청):
--   ai_settings.provider에 NONE·FAKE 추가(AIA-07.05: 키가 없으면 NONE, 개발·시험은 FAKE), 기본 NONE
--   script_assists.script_id NULL 허용(API-AIA-03 scriptId는 선택), stage·status 열 추가
--   prompt_logs 추가(AIA-07.06: 마스킹한 프롬프트·응답·도구 호출·지연. 보관 기간이 지나면 지우고 usage_logs는 남김, BR-AIA-15)
--   help_chunks.embedding은 public.vector(pgvector는 DB 초기 구성에서 public에 설치, bootstrap/db/00-bootstrap.sql)
-- ADR-030: staging과 prod가 DB 하나를 쓰므로 이후 마이그레이션은 추가만(expand) 한다.

CREATE TABLE data2flow_ai.ai_settings (
  id                     bigint GENERATED ALWAYS AS IDENTITY,
  organization_id        bigint       NOT NULL,
  enabled                boolean      NOT NULL DEFAULT true,
  provider               varchar(32)  NOT NULL DEFAULT 'NONE',
  model                  varchar(64)  NOT NULL,
  embedding_model        varchar(64)  NOT NULL DEFAULT 'hashing-1024',
  daily_request_limit    integer      NOT NULL DEFAULT 1000,
  daily_token_limit      bigint       NOT NULL DEFAULT 2000000,
  per_user_daily_limit   integer      NOT NULL DEFAULT 100,
  log_retention_days     integer      NOT NULL DEFAULT 90,
  auto_commentary        boolean      NOT NULL DEFAULT false,
  eval_threshold         numeric(4,3) NOT NULL DEFAULT 0.900,
  suggestion_ttl_minutes integer      NOT NULL DEFAULT 30,
  version                integer      NOT NULL DEFAULT 0,
  created_by             bigint,
  updated_by             bigint,
  created_at             timestamptz  NOT NULL DEFAULT now(),
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_ai_settings PRIMARY KEY (id),
  CONSTRAINT uq_ai_settings_organization_id UNIQUE (organization_id),
  CONSTRAINT ck_ai_settings_provider CHECK (provider IN ('NONE','FAKE','ANTHROPIC','OPENAI','GOOGLE','OLLAMA')),
  CONSTRAINT ck_ai_settings_log_retention_days CHECK (log_retention_days BETWEEN 7 AND 365),
  CONSTRAINT ck_ai_settings_suggestion_ttl_minutes CHECK (suggestion_ttl_minutes BETWEEN 5 AND 120)
);
COMMENT ON TABLE data2flow_ai.ai_settings IS '조직당 1행. 한도 초과 시 AI만 차단(BR-AIA-08)';

-- ───────────── Conversation ─────────────
CREATE TABLE data2flow_ai.conversations (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint       NOT NULL,
  user_id         bigint       NOT NULL,
  title           varchar(100) NOT NULL,
  mode            varchar(16)  NOT NULL,
  created_at      timestamptz  NOT NULL DEFAULT now(),
  updated_at      timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_conversations PRIMARY KEY (id),
  CONSTRAINT ck_conversations_mode CHECK (mode IN ('DATA','HELP'))
);
CREATE INDEX ix_conversations_organization_id_user_id_updated_at ON data2flow_ai.conversations (organization_id, user_id, updated_at DESC);
COMMENT ON TABLE data2flow_ai.conversations IS '본인만 조회(BR-AIA-14). 도메인 문서의 deleted_at 대신 물리 삭제(erd/README §5)';

CREATE TABLE data2flow_ai.messages (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  conversation_id bigint      NOT NULL,
  role            varchar(16) NOT NULL,
  content         text        NOT NULL,
  tool_calls      jsonb,
  citations       jsonb,
  tokens_in       integer,
  tokens_out      integer,
  verification    jsonb,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_messages PRIMARY KEY (id),
  CONSTRAINT fk_messages_conversation_id FOREIGN KEY (conversation_id) REFERENCES data2flow_ai.conversations (id) ON DELETE CASCADE,
  CONSTRAINT ck_messages_role CHECK (role IN ('USER','ASSISTANT','TOOL'))
);
CREATE INDEX ix_messages_conversation_id_id ON data2flow_ai.messages (conversation_id, id);
COMMENT ON COLUMN data2flow_ai.messages.content IS '개인정보는 가린 형태로 저장';

-- ───────────── Commentary ─────────────
CREATE TABLE data2flow_ai.commentaries (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  subject_type    varchar(16) NOT NULL,
  subject_id      bigint      NOT NULL,
  content_md      text        NOT NULL DEFAULT '',
  status          varchar(16) NOT NULL DEFAULT 'GENERATING',
  mismatches      jsonb,
  model           varchar(64) NOT NULL,
  superseded_by   bigint,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_commentaries PRIMARY KEY (id),
  CONSTRAINT fk_commentaries_superseded_by FOREIGN KEY (superseded_by) REFERENCES data2flow_ai.commentaries (id) ON DELETE SET NULL,
  CONSTRAINT ck_commentaries_subject_type CHECK (subject_type IN ('ANALYSIS_RUN','REPORT','ALARM')),
  CONSTRAINT ck_commentaries_status CHECK (status IN ('GENERATING','VERIFIED','UNVERIFIED','FAILED'))
);
CREATE INDEX ix_commentaries_organization_id_subject ON data2flow_ai.commentaries (organization_id, subject_type, subject_id);
COMMENT ON TABLE data2flow_ai.commentaries IS '수치 대조, 불일치면 재생성 1회 후 UNVERIFIED(BR-AIA-01·02)';

-- ───────────── Report ─────────────
CREATE TABLE data2flow_ai.report_schedules (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint       NOT NULL,
  name            varchar(100) NOT NULL,
  period          varchar(16)  NOT NULL,
  run_at          jsonb        NOT NULL,
  scope           jsonb        NOT NULL,
  sections        text[]       NOT NULL,
  recipients      jsonb        NOT NULL,
  language        varchar(8)   NOT NULL DEFAULT 'ko',
  enabled         boolean      NOT NULL DEFAULT true,
  owner_user_id   bigint       NOT NULL,
  version         integer      NOT NULL DEFAULT 0,
  created_by      bigint,
  updated_by      bigint,
  created_at      timestamptz  NOT NULL DEFAULT now(),
  updated_at      timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_report_schedules PRIMARY KEY (id),
  CONSTRAINT ck_report_schedules_period CHECK (period IN ('DAILY','WEEKLY','MONTHLY')),
  CONSTRAINT ck_report_schedules_language CHECK (language IN ('ko','en','ja','zh')),
  CONSTRAINT ck_report_schedules_sections CHECK (cardinality(sections) >= 1
    AND sections <@ ARRAY['ENV_SUMMARY','TARGET_DEVIATION','ALARMS','SENSOR_HEALTH','ANALYSIS_SUMMARY','RECOMMENDATIONS']::text[])
);
CREATE INDEX ix_report_schedules_organization_id_enabled ON data2flow_ai.report_schedules (organization_id) WHERE enabled;

CREATE TABLE data2flow_ai.reports (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint       NOT NULL,
  schedule_id     bigint,
  period_from     timestamptz  NOT NULL,
  period_to       timestamptz  NOT NULL,
  scope           jsonb        NOT NULL,
  status          varchar(24)  NOT NULL DEFAULT 'QUEUED',
  figures         jsonb        NOT NULL DEFAULT '{}'::jsonb,
  content_md      text,
  generation_mode varchar(16)  NOT NULL DEFAULT 'LLM',
  pdf_uri         varchar(500),
  created_at      timestamptz  NOT NULL DEFAULT now(),
  updated_at      timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_reports PRIMARY KEY (id),
  CONSTRAINT fk_reports_schedule_id FOREIGN KEY (schedule_id) REFERENCES data2flow_ai.report_schedules (id) ON DELETE SET NULL,
  CONSTRAINT ck_reports_status CHECK (status IN ('QUEUED','COLLECTING','WRITING','READY','DELIVERING','DELIVERED','PARTIALLY_DELIVERED','FAILED')),
  CONSTRAINT ck_reports_generation_mode CHECK (generation_mode IN ('LLM','TEMPLATE')),
  CONSTRAINT ck_reports_period CHECK (period_from < period_to)
);
CREATE INDEX ix_reports_organization_id_created_at ON data2flow_ai.reports (organization_id, created_at DESC);
COMMENT ON COLUMN data2flow_ai.reports.scope IS '생성 시점 범위 고정(BR-AIA-13), 권한 필터에 사용';

CREATE TABLE data2flow_ai.report_deliveries (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  report_id       bigint      NOT NULL,
  channel_id      bigint,
  user_id         bigint,
  status          varchar(16) NOT NULL DEFAULT 'PENDING',
  attempts        smallint    NOT NULL DEFAULT 0,
  last_error      text,
  sent_at         timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_report_deliveries PRIMARY KEY (id),
  CONSTRAINT fk_report_deliveries_report_id FOREIGN KEY (report_id) REFERENCES data2flow_ai.reports (id) ON DELETE CASCADE,
  CONSTRAINT ck_report_deliveries_status CHECK (status IN ('PENDING','SENT','FAILED')),
  CONSTRAINT ck_report_deliveries_attempts CHECK (attempts BETWEEN 0 AND 5),
  CONSTRAINT ck_report_deliveries_target CHECK ((channel_id IS NULL) <> (user_id IS NULL))
);
CREATE INDEX ix_report_deliveries_report_id ON data2flow_ai.report_deliveries (report_id);
COMMENT ON COLUMN data2flow_ai.report_deliveries.channel_id IS 'data2flow_core.notification_channels.id (FK 없음)';

-- ───────────── Suggestion ─────────────
CREATE TABLE data2flow_ai.suggestions (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint       NOT NULL,
  kind            varchar(16)  NOT NULL,
  target_ref      jsonb        NOT NULL,
  rationale       jsonb        NOT NULL,
  expected_effect jsonb,
  status          varchar(20)  NOT NULL DEFAULT 'PROPOSED',
  decided_by      bigint,
  decision_reason varchar(200),
  decided_at      timestamptz,
  command_id      uuid,
  actual_effect   jsonb,
  expires_at      timestamptz  NOT NULL,
  created_at      timestamptz  NOT NULL DEFAULT now(),
  updated_at      timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_suggestions PRIMARY KEY (id),
  CONSTRAINT ck_suggestions_kind CHECK (kind IN ('RULE_DRAFT','FLOW_DRAFT','CONTROL')),
  CONSTRAINT ck_suggestions_status CHECK (status IN ('PROPOSED','APPROVED','EXECUTED','EXECUTION_FAILED','REJECTED','EXPIRED'))
);
CREATE INDEX ix_suggestions_expires_at_proposed ON data2flow_ai.suggestions (expires_at) WHERE status = 'PROPOSED';
CREATE INDEX ix_suggestions_organization_id_status ON data2flow_ai.suggestions (organization_id, status);
COMMENT ON COLUMN data2flow_ai.suggestions.command_id IS 'data2flow_action.commands.id (uuid, FK 없음). 승인 명령의 출처는 {type:AI, suggestionId, approvedBy}(BR-ACT-24)';

CREATE TABLE data2flow_ai.root_causes (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  alarm_id        bigint      NOT NULL,
  candidates      jsonb       NOT NULL DEFAULT '[]'::jsonb,
  status          varchar(20) NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_root_causes PRIMARY KEY (id),
  CONSTRAINT ck_root_causes_status CHECK (status IN ('READY','INSUFFICIENT_DATA','FAILED'))
);
CREATE INDEX ix_root_causes_organization_id_alarm_id ON data2flow_ai.root_causes (organization_id, alarm_id);
COMMENT ON COLUMN data2flow_ai.root_causes.alarm_id IS 'data2flow_core.alarms.id (FK 없음)';

CREATE TABLE data2flow_ai.script_assists (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  user_id         bigint      NOT NULL,
  script_id       bigint,
  stage           varchar(16) NOT NULL DEFAULT 'DECODE',
  requirement     text        NOT NULL,
  attempts        jsonb       NOT NULL DEFAULT '[]'::jsonb,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_script_assists PRIMARY KEY (id),
  CONSTRAINT ck_script_assists_attempts CHECK (jsonb_array_length(attempts) <= 5),
  CONSTRAINT ck_script_assists_stage CHECK (stage IN ('DECODE','TRANSFORM'))
);
CREATE INDEX ix_script_assists_organization_id_user_id ON data2flow_ai.script_assists (organization_id, user_id, created_at DESC);

-- ───────────── AiOps ─────────────
CREATE TABLE data2flow_ai.usage_logs (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint        NOT NULL,
  time            timestamptz   NOT NULL,
  user_id         bigint,
  feature         varchar(16)   NOT NULL,
  provider        varchar(32)   NOT NULL,
  model           varchar(64)   NOT NULL,
  tokens_in       integer       NOT NULL DEFAULT 0,
  tokens_out      integer       NOT NULL DEFAULT 0,
  cost_estimate   numeric(14,6),
  status          varchar(16)   NOT NULL,
  CONSTRAINT pk_usage_logs PRIMARY KEY (id, time),
  CONSTRAINT ck_usage_logs_feature CHECK (feature IN ('CHAT','COMMENTARY','REPORT','SCRIPT','FLOW_DRAFT','ROOT_CAUSE','MCP','HELP')),
  CONSTRAINT ck_usage_logs_status CHECK (status IN ('OK','LIMITED','REFUSED','ERROR'))
) PARTITION BY RANGE (time);
CREATE TABLE data2flow_ai.usage_logs_default PARTITION OF data2flow_ai.usage_logs DEFAULT;
CREATE TABLE data2flow_ai.usage_logs_y2026m10 PARTITION OF data2flow_ai.usage_logs
  FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
CREATE INDEX ix_usage_logs_organization_id_time ON data2flow_ai.usage_logs (organization_id, time DESC);
-- 월 파티션은 ai 스케줄러가 미리 만든다(pg_partman 없음, ADR-019)
CREATE TABLE data2flow_ai.prompt_logs (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint       NOT NULL,
  time            timestamptz  NOT NULL,
  user_id         bigint,
  feature         varchar(16)  NOT NULL,
  provider        varchar(32)  NOT NULL,
  model           varchar(64)  NOT NULL,
  prompt_masked   text         NOT NULL,
  response        text,
  tool_calls      jsonb,
  tokens_in       integer      NOT NULL DEFAULT 0,
  tokens_out      integer      NOT NULL DEFAULT 0,
  latency_ms      integer      NOT NULL DEFAULT 0,
  status          varchar(16)  NOT NULL,
  CONSTRAINT pk_prompt_logs PRIMARY KEY (id),
  CONSTRAINT ck_prompt_logs_status CHECK (status IN ('OK','LIMITED','REFUSED','ERROR'))
);
CREATE INDEX ix_prompt_logs_organization_id_time ON data2flow_ai.prompt_logs (organization_id, time DESC);
COMMENT ON TABLE data2flow_ai.prompt_logs IS '요청별 마스킹한 프롬프트·응답(AIA-07.06). 조직 log_retention_days가 지나면 삭제, usage_logs는 유지(BR-AIA-15)';

COMMENT ON COLUMN data2flow_ai.usage_logs.status IS 'OK 정상, LIMITED 한도 초과로 거절, REFUSED 정책상 거절(근거 없음·주입 차단), ERROR 제공자 오류(2026-10-03 제안값)';

CREATE TABLE data2flow_ai.eval_sets (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint       NOT NULL DEFAULT 0,
  name            varchar(100) NOT NULL,
  cases           jsonb        NOT NULL DEFAULT '[]'::jsonb,
  created_at      timestamptz  NOT NULL DEFAULT now(),
  updated_at      timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_eval_sets PRIMARY KEY (id)
);
COMMENT ON COLUMN data2flow_ai.eval_sets.cases IS 'eval_case 배열: question, expected_numbers[], expected_refusal, injection, fixture';

CREATE TABLE data2flow_ai.eval_runs (
  id                   bigint GENERATED ALWAYS AS IDENTITY,
  organization_id      bigint       NOT NULL DEFAULT 0,
  eval_set_id          bigint       NOT NULL,
  model                varchar(64)  NOT NULL,
  prompt_version       varchar(32)  NOT NULL,
  accuracy             numeric(5,4),
  number_match_rate    numeric(5,4),
  injection_block_rate numeric(5,4),
  passed               boolean      NOT NULL,
  created_at           timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_eval_runs PRIMARY KEY (id),
  CONSTRAINT fk_eval_runs_eval_set_id FOREIGN KEY (eval_set_id) REFERENCES data2flow_ai.eval_sets (id) ON DELETE CASCADE
);
CREATE INDEX ix_eval_runs_eval_set_id_created_at ON data2flow_ai.eval_runs (eval_set_id, created_at DESC);
COMMENT ON TABLE data2flow_ai.eval_runs IS '평가 기준 미달 모델 적용 금지(BR-AIA-10, AIA-07.07)';

CREATE TABLE data2flow_ai.help_chunks (
  id         bigint GENERATED ALWAYS AS IDENTITY,
  source     varchar(16)  NOT NULL,
  doc_id     varchar(200) NOT NULL,
  chunk_no   integer      NOT NULL DEFAULT 0,
  chunk      text         NOT NULL,
  embedding  public.vector(1024) NOT NULL,
  url        varchar(500),
  created_at timestamptz  NOT NULL DEFAULT now(),
  updated_at timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_help_chunks PRIMARY KEY (id),
  CONSTRAINT uq_help_chunks_doc_id_chunk_no UNIQUE (doc_id, chunk_no),
  CONSTRAINT ck_help_chunks_source CHECK (source IN ('USER_GUIDE','TEMPLATE_GUIDE','ERROR_CODE'))
);
CREATE INDEX ix_help_chunks_embedding ON data2flow_ai.help_chunks USING hnsw (embedding public.vector_cosine_ops);
COMMENT ON TABLE data2flow_ai.help_chunks IS '도움말 의미 검색 색인(전역, 조직 없음). 차원 1024는 AIA domain-model 기준(AIA-09)';
