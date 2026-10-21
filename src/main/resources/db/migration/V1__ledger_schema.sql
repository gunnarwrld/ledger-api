create table accounts (
    id uuid primary key,
    name varchar(120) not null,
    currency_code varchar(3) not null check (currency_code ~ '^[A-Z]{3}$'),
    kind varchar(24) not null check (kind in ('CUSTOMER_WALLET', 'CASH_ASSET')),
    balance_minor bigint not null default 0 check (balance_minor >= 0),
    system_key varchar(80) unique,
    version bigint not null default 0,
    created_at timestamptz not null,
    constraint account_system_key_kind check (
        (kind = 'CUSTOMER_WALLET' and system_key is null) or
        (kind = 'CASH_ASSET' and system_key is not null)
    )
);

create table journal_entries (
    id uuid primary key,
    entry_type varchar(30) not null check (entry_type in ('OPENING_BALANCE', 'TRANSFER')),
    reference_id uuid,
    created_at timestamptz not null
);

create table postings (
    id uuid primary key,
    entry_id uuid not null references journal_entries(id) on delete restrict,
    account_id uuid not null references accounts(id) on delete restrict,
    side varchar(6) not null check (side in ('DEBIT', 'CREDIT')),
    amount_minor bigint not null check (amount_minor > 0),
    currency_code varchar(3) not null check (currency_code ~ '^[A-Z]{3}$'),
    created_at timestamptz not null
);

create index idx_postings_account_statement on postings(account_id, created_at desc, id desc);

create table idempotency_records (
    idempotency_key varchar(128) primary key,
    request_hash varchar(64) not null,
    transfer_id uuid not null unique,
    journal_entry_id uuid not null unique,
    created_at timestamptz not null,
    constraint idempotency_journal_fk foreign key (journal_entry_id) references journal_entries(id) deferrable initially deferred
);

create function reject_ledger_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'ledger journal entries and postings are append-only';
end;
$$;

create trigger journal_entries_append_only
    before update or delete on journal_entries
    for each row execute function reject_ledger_mutation();

create trigger postings_append_only
    before update or delete on postings
    for each row execute function reject_ledger_mutation();

create function assert_journal_entry_balanced() returns trigger language plpgsql as $$
declare
    target_entry_id uuid;
    posting_count bigint;
    debit_total numeric;
    credit_total numeric;
    currency_count bigint;
    account_currency_mismatch_count bigint;
begin
    if tg_table_name = 'journal_entries' then
        target_entry_id := new.id;
    else
        target_entry_id := new.entry_id;
    end if;

    select count(*),
           coalesce(sum(amount_minor) filter (where side = 'DEBIT'), 0),
           coalesce(sum(amount_minor) filter (where side = 'CREDIT'), 0),
           count(distinct p.currency_code),
           count(*) filter (where p.currency_code <> a.currency_code)
      into posting_count, debit_total, credit_total, currency_count, account_currency_mismatch_count
      from postings p
      join accounts a on a.id = p.account_id
     where p.entry_id = target_entry_id;

    if posting_count < 2 or debit_total <> credit_total or currency_count <> 1 or account_currency_mismatch_count <> 0 then
        raise exception 'journal entry % is unbalanced or mixes currencies', target_entry_id;
    end if;
    return null;
end;
$$;

create constraint trigger journal_entry_must_balance
    after insert on journal_entries
    deferrable initially deferred
    for each row execute function assert_journal_entry_balanced();

create constraint trigger posting_must_balance_entry
    after insert on postings
    deferrable initially deferred
    for each row execute function assert_journal_entry_balanced();
