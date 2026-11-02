-- 크루 동기화: 크루 코드별 키-값(마지막 값 보관). 차량 앱·휴대폰 웹이 같은 형식을 쓴다.
-- 키: meta(크루 정보) · req(목적지 변경 요청) · m/{id}(크루원 상태)
-- 삭제는 v = null로 갱신한다 (필터를 건 Realtime 구독은 DELETE 이벤트를 받지 못하므로).
create table public.crew_kv (
  code text not null check (code ~ '^[A-Z0-9]{6}$'),
  k text not null check (k ~ '^(meta|req|m/[A-Za-z0-9_-]{1,40})$'),
  v jsonb check (v is null or pg_column_size(v) <= 8192),
  updated_at timestamptz not null default now(),
  primary key (code, k)
);

alter table public.crew_kv enable row level security;

-- 로그인 없이 크루 코드로만 참여하는 구조(공모전 시연). 코드를 아는 클라이언트만 해당 행을 다룬다.
create policy "crew read" on public.crew_kv for select to anon using (true);
create policy "crew insert" on public.crew_kv for insert to anon with check (true);
create policy "crew update" on public.crew_kv for update to anon using (true) with check (true);

create or replace function public.crew_kv_touch() returns trigger language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;
create trigger crew_kv_touch before update on public.crew_kv for each row execute function public.crew_kv_touch();

alter publication supabase_realtime add table public.crew_kv;

-- 하루 넘게 바뀌지 않은 크루 데이터는 매시간 지운다.
create extension if not exists pg_cron;
select cron.schedule('crew_kv_cleanup', '17 * * * *', $$delete from public.crew_kv where updated_at < now() - interval '1 day'$$);
