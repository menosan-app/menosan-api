-- V1__init.sql (owned by BE-0) — copied verbatim from docs/DEVELOPMENT_PLAN.md §7.
-- Append-only: never edit this file once applied. Add V{n}__*.sql instead.

CREATE TABLE users (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  firebase_uid     text UNIQUE NOT NULL,
  email            text NOT NULL,
  display_name     text,
  consented_at     timestamptz NOT NULL,          -- privacy notice accepted (Data Privacy Act)
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE waste_subcategories (
  code        text PRIMARY KEY,
  category    text NOT NULL CHECK (category IN ('BIODEGRADABLE','RECYCLABLE','RESIDUAL','SPECIAL')),
  label       text NOT NULL,
  examples    text[] NOT NULL DEFAULT '{}',
  avoidable   boolean NOT NULL DEFAULT false,
  sort_order  int NOT NULL
);

CREATE TABLE waste_entries (
  id                  uuid PRIMARY KEY,            -- client-generated UUID → idempotent sync (NFR8)
  user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name                text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
  category            text NOT NULL,               -- derived from subcategory, validated
  subcategory_code    text NOT NULL REFERENCES waste_subcategories(code),
  quantity            int  NOT NULL CHECK (quantity BETWEEN 1 AND 999),
  source              text NOT NULL CHECK (source IN ('MANUAL','PHOTO')),
  created_at          timestamptz NOT NULL,        -- client time of creation (NFR11)
  week_start          date NOT NULL,               -- derived server-side, Asia/Manila
  updated_at          timestamptz NOT NULL DEFAULT now(),
  received_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON waste_entries (user_id, week_start);

CREATE TABLE interventions (                       -- curated library (NFR12)
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code              text UNIQUE NOT NULL,          -- e.g. RES_SACHETS_REFILL_STATION
  subcategory_code  text NOT NULL REFERENCES waste_subcategories(code),
  type              text NOT NULL CHECK (type IN ('PREVENT','REDUCE','REUSE')),
  title             text NOT NULL,
  description       text NOT NULL,
  how_to            text[] NOT NULL DEFAULT '{}',
  cost_level        text NOT NULL CHECK (cost_level IN ('FREE','SAVES_MONEY','SMALL_ONE_TIME_COST')),
  effort            text NOT NULL CHECK (effort IN ('LOW','MEDIUM')),
  active            boolean NOT NULL DEFAULT true
);

CREATE TABLE weekly_reports (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  week_start         date NOT NULL,
  week_end           date NOT NULL,
  stats              jsonb NOT NULL,               -- §5.1 output
  comparison         jsonb,                        -- §5.3 output or NULL
  algorithm_version  int NOT NULL,
  revision           int NOT NULL DEFAULT 1,
  generated_at       timestamptz NOT NULL DEFAULT now(),
  regenerated_at     timestamptz,
  UNIQUE (user_id, week_start)
);

CREATE TABLE hotspots (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  report_id         uuid NOT NULL REFERENCES weekly_reports(id) ON DELETE CASCADE,
  subcategory_code  text NOT NULL REFERENCES waste_subcategories(code),
  rank              int NOT NULL,
  criteria          text[] NOT NULL,              -- MOST_FREQUENT, HIGHEST_QUANTITY, AVOIDABLE
  frequency         int NOT NULL,
  quantity          int NOT NULL,
  score             numeric(6,4) NOT NULL,
  UNIQUE (report_id, subcategory_code)
);

CREATE TABLE report_recommendations (              -- "Hotspot Intervention" in Data Fields
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  hotspot_id       uuid NOT NULL REFERENCES hotspots(id) ON DELETE CASCADE,
  intervention_id  uuid NOT NULL REFERENCES interventions(id),
  rank             int NOT NULL,
  note             text,                           -- Gemini personalization, nullable
  continued        boolean NOT NULL DEFAULT false,
  source           text NOT NULL CHECK (source IN ('GEMINI','RULES')),
  UNIQUE (hotspot_id, intervention_id)
);

CREATE TABLE adopted_interventions (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  report_id            uuid NOT NULL REFERENCES weekly_reports(id) ON DELETE CASCADE,
  intervention_id      uuid NOT NULL REFERENCES interventions(id),
  target_subcategory   text NOT NULL REFERENCES waste_subcategories(code),
  baseline_week_start  date NOT NULL,
  baseline_quantity    int NOT NULL,
  adopted_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (report_id, intervention_id)
);

CREATE TABLE intervention_impacts (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  adoption_id        uuid NOT NULL UNIQUE REFERENCES adopted_interventions(id) ON DELETE CASCADE,
  followup_report_id uuid REFERENCES weekly_reports(id) ON DELETE CASCADE,
  followup_week_start date NOT NULL,
  baseline_quantity  int NOT NULL,
  followup_quantity  int NOT NULL,
  result             text NOT NULL CHECK (result IN ('DECREASED','SAME','INCREASED'))
);
