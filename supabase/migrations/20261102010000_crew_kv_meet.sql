-- 키 추가: meet(모이기 좋은 휴게소 추천, 리더가 계산해 공유)
alter table public.crew_kv drop constraint crew_kv_k_check;
alter table public.crew_kv add constraint crew_kv_k_check check (k ~ '^(meta|req|meet|m/[A-Za-z0-9_-]{1,40})$');
