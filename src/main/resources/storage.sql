-- Additive independent history: identities remain after profile retirement/deletion.
CREATE TABLE IF NOT EXISTS ob_dungeon_runs (
    run_id uuid PRIMARY KEY,
    group_size integer NOT NULL CHECK(group_size BETWEEN 1 AND 4),
    mode text NOT NULL,
    rules_version text NOT NULL,
    rules_hash text NOT NULL,
    completed_wave bigint NOT NULL CHECK(completed_wave>=0),
    elapsed_ms bigint NOT NULL CHECK(elapsed_ms>=0),
    reached_at timestamptz,
    created_at timestamptz NOT NULL,
    started_at timestamptz,
    ended_at timestamptz,
    end_reason text,
    participants jsonb NOT NULL CHECK(jsonb_typeof(participants)='array'),
    payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object')
);
CREATE INDEX IF NOT EXISTS ob_dungeon_ranking ON ob_dungeon_runs(mode,group_size,completed_wave DESC,elapsed_ms ASC,run_id) WHERE completed_wave>0;
CREATE TABLE IF NOT EXISTS ob_system (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    schema_version integer NOT NULL CHECK(schema_version=12),
    revision bigint NOT NULL DEFAULT 0,
    market_sequence bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE ob_system DROP CONSTRAINT IF EXISTS ob_system_schema_version_check;
UPDATE ob_system SET schema_version=12 WHERE singleton;
ALTER TABLE ob_system ADD CONSTRAINT ob_system_schema_version_check CHECK(schema_version=12);
ALTER TABLE ob_system ADD COLUMN IF NOT EXISTS commit_token uuid;
INSERT INTO ob_system(singleton,schema_version) VALUES(true,12) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS ob_players (
    player_id uuid PRIMARY KEY,
    nickname text,
    active_profile uuid,
    state jsonb NOT NULL CHECK(jsonb_typeof(state)='object')
);
CREATE TABLE IF NOT EXISTS ob_profiles (
    profile_id uuid PRIMARY KEY,
    name text NOT NULL,
    owner_id uuid NOT NULL REFERENCES ob_players(player_id) DEFERRABLE INITIALLY DEFERRED,
    coins bigint NOT NULL DEFAULT 0 CHECK(coins>=0),
    blocks_mined bigint NOT NULL DEFAULT 0 CHECK(blocks_mined>=0),
    core_x integer NOT NULL DEFAULT 0,
    core_y integer NOT NULL DEFAULT 60,
    core_z integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    state jsonb NOT NULL CHECK(jsonb_typeof(state)='object')
);
ALTER TABLE ob_profiles ADD COLUMN IF NOT EXISTS public_id varchar(4) CHECK(public_id ~ '^[0-9]{4}$' AND public_id <> '0000');
ALTER TABLE ob_profiles ADD COLUMN IF NOT EXISTS name_number integer CHECK(name_number BETWEEN 1 AND 46);
ALTER TABLE ob_profiles ADD COLUMN IF NOT EXISTS custom_name text;
UPDATE ob_profiles SET custom_name=name WHERE public_id IS NULL AND custom_name IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ob_profiles_public_id ON ob_profiles(public_id);
ALTER TABLE ob_players ADD COLUMN IF NOT EXISTS inventory_full boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS ob_profiles_owner ON ob_profiles(owner_id);
CREATE INDEX IF NOT EXISTS ob_players_nickname ON ob_players(lower(nickname)) WHERE nickname IS NOT NULL;
CREATE TABLE IF NOT EXISTS ob_market (
    record_id uuid NOT NULL,
    record_type text NOT NULL CHECK(record_type IN ('order','claim','handoff','bazaar_trade','auction','merchant_sale','player_trade','combat_receipt','room_chest','chest_progress')),
    profile_id uuid REFERENCES ob_profiles(profile_id) DEFERRABLE INITIALLY DEFERRED,
    other_profile_id uuid REFERENCES ob_profiles(profile_id) DEFERRABLE INITIALLY DEFERRED,
    actor_id uuid REFERENCES ob_players(player_id) DEFERRABLE INITIALLY DEFERRED,
    other_actor_id uuid REFERENCES ob_players(player_id) DEFERRABLE INITIALLY DEFERRED,
    status text NOT NULL,
    product text,
    quantity bigint CHECK(quantity IS NULL OR quantity>0),
    unit_price bigint CHECK(unit_price IS NULL OR unit_price>=0),
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
    PRIMARY KEY(record_type,record_id)
);
-- Additive schema12 kind: receipts are never included in pruned market history.
ALTER TABLE ob_market DROP CONSTRAINT IF EXISTS ob_market_record_type_check;
ALTER TABLE ob_market ADD CONSTRAINT ob_market_record_type_check CHECK(record_type IN ('order','claim','handoff','bazaar_trade','auction','merchant_sale','player_trade','combat_receipt','room_chest','chest_progress'));
CREATE INDEX IF NOT EXISTS ob_market_profile ON ob_market(profile_id,record_type,status);
CREATE INDEX IF NOT EXISTS ob_market_actor ON ob_market(actor_id,record_type,status);
CREATE INDEX IF NOT EXISTS ob_market_product ON ob_market(product,record_type,status);
CREATE INDEX IF NOT EXISTS ob_market_expiry ON ob_market(expires_at) WHERE expires_at IS NOT NULL;
CREATE TABLE IF NOT EXISTS ob_events (
    event_id uuid PRIMARY KEY,
    time timestamptz NOT NULL DEFAULT now(),
    action text NOT NULL,
    status text NOT NULL,
    success boolean,
    player_name text,
    profile_name text,
    reason text,
    operation_id uuid,
    player_id uuid,
    profile_id uuid,
    details jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS ob_events_time ON ob_events(time DESC);
CREATE INDEX IF NOT EXISTS ob_events_problem ON ob_events(time DESC) WHERE success=false OR status='REVIEW';
CREATE INDEX IF NOT EXISTS ob_events_operation ON ob_events(operation_id) WHERE operation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ob_events_player ON ob_events(player_id,time DESC);
CREATE OR REPLACE VIEW ob_profile_overview AS SELECT p.name AS profile_name,u.nickname AS owner_name,p.coins,p.blocks_mined,p.core_x,p.core_y,p.core_z,'oneblock:island_'||p.profile_id::text AS world_key,p.profile_id,p.owner_id,p.created_at,p.public_id,p.name_number FROM ob_profiles p LEFT JOIN ob_players u ON u.player_id=p.owner_id;
CREATE OR REPLACE VIEW ob_market_overview AS SELECT m.record_type,m.status,p.name AS profile_name,u.nickname AS player_name,q.name AS other_profile_name,v.nickname AS other_player_name,m.product,m.quantity,m.unit_price,m.created_at,m.expires_at,m.record_id,m.profile_id,m.other_profile_id,m.actor_id,m.other_actor_id,m.payload FROM ob_market m LEFT JOIN ob_profiles p ON p.profile_id=m.profile_id LEFT JOIN ob_profiles q ON q.profile_id=m.other_profile_id LEFT JOIN ob_players u ON u.player_id=m.actor_id LEFT JOIN ob_players v ON v.player_id=m.other_actor_id;
CREATE OR REPLACE VIEW ob_problems AS SELECT time,action,status,player_name,profile_name,reason,operation_id,details,event_id FROM ob_events WHERE success=false OR status='REVIEW';
