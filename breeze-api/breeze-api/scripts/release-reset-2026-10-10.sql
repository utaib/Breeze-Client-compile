-- Release reset, 2026-10-10 (owner's request before the 1.0.32 release).
--
-- Wind Charges were tested with PayPal sandbox payments, so balances and the
-- three Wind Charge orders are test data. This puts the economy at a clean
-- start without deleting any history:
--   1. every Wind Charge balance becomes 0 (owner included), with one ledger
--      row per changed balance so the wallet history explains the drop
--   2. every earned (owed) Wind Charge balance becomes 0
--   3. Creator Passes: 6400 for every role that holds them (owner, admin,
--      developer, creator), 0 for everyone else
--   4. the test Wind Charge orders are marked cancelled, so revenue reads 0
--   5. unpaid earnings and pending withdrawals, if any, are closed
--
-- Safe to run twice: the second run changes nothing.

begin;

insert into public.wallet_transactions (user_uuid, type, amount_wc, balance_after, ref, note)
select uuid, 'reset', -wind_charges, 0, 'release-reset-2026-10-10', 'Balance reset for launch'
from public.users
where wind_charges <> 0;

update public.users
set wind_charges = 0, earned_wind_charges = 0
where wind_charges <> 0 or earned_wind_charges <> 0;

update public.users
set creator_passes = case
    when role in ('owner', 'admin', 'developer', 'creator') then 6400
    else 0
end
where creator_passes is distinct from case
    when role in ('owner', 'admin', 'developer', 'creator') then 6400
    else 0
end;

update public.orders
set status = 'cancelled',
    metadata = coalesce(metadata, '{}'::jsonb)
        || jsonb_build_object('test_order', true, 'reset', 'release-reset-2026-10-10')
where order_type = 'wind_charges'
  and status in ('pending', 'approved', 'completed');

update public.earnings set paid_out = true where paid_out = false;

update public.withdrawal_requests
set status = 'cancelled', admin_note = 'Release reset 2026-10-10', resolved_at = now()
where status = 'pending';

commit;

-- Check: every line should read 0 except passes_holders = passes_total / 6400.
select
    (select coalesce(sum(wind_charges), 0) from public.users) as wind_charges_total,
    (select coalesce(sum(earned_wind_charges), 0) from public.users) as earned_total,
    (select count(*) from public.users where role in ('owner', 'admin', 'developer', 'creator')) as passes_holders,
    (select coalesce(sum(creator_passes), 0) from public.users) as passes_total,
    (select count(*) from public.users where role = 'user' and creator_passes <> 0) as users_with_passes,
    (select count(*) from public.orders where order_type = 'wind_charges' and status = 'completed') as completed_wc_orders,
    (select count(*) from public.earnings where paid_out = false) as unpaid_earnings,
    (select count(*) from public.withdrawal_requests where status = 'pending') as pending_withdrawals;
