create function assert_account_balance_matches_postings() returns trigger language plpgsql as $$
declare
    target_account uuid;
    expected bigint;
    actual bigint;
begin
    if tg_table_name = 'accounts' then
        target_account := new.id;
    else
        target_account := new.account_id;
    end if;

    select a.balance_minor
      into actual
      from accounts a
     where a.id = target_account;

    select coalesce(sum(
               case
                   when a.kind = 'CUSTOMER_WALLET' and p.side = 'CREDIT' then p.amount_minor
                   when a.kind = 'CUSTOMER_WALLET' and p.side = 'DEBIT' then -p.amount_minor
                   when a.kind = 'CASH_ASSET' and p.side = 'DEBIT' then p.amount_minor
                   when a.kind = 'CASH_ASSET' and p.side = 'CREDIT' then -p.amount_minor
                   else 0
               end
           ), 0)::bigint
      into expected
      from postings p
      join accounts a on a.id = p.account_id
     where p.account_id = target_account;

    if actual is distinct from expected then
        raise exception 'account % balance % does not match postings %', target_account, actual, expected;
    end if;
    return null;
end;
$$;

create constraint trigger account_balance_matches_postings
    after insert or update on accounts
    deferrable initially deferred
    for each row execute function assert_account_balance_matches_postings();

create constraint trigger posting_balance_matches_account
    after insert on postings
    deferrable initially deferred
    for each row execute function assert_account_balance_matches_postings();
