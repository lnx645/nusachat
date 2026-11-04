%%%-------------------------------------------------------------------
%%% @doc Config: OS env wins, else dev defaults matching backend/.env.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_config).

-export([get/2, db_opts/0, secret/0, port/0]).

%%%-------------------------------------------------------------------
%%% @doc Env var bila ada dan tak kosong, else default. Aturan tunggal
%%% agar perilaku config seragam di semua modul.
%%% @end
%%%-------------------------------------------------------------------
-spec get(string(), term()) -> term().
get(Key, Default) ->
    case os:getenv(Key) of
        false -> Default;
        "" -> Default;
        Value -> Value
    end.

get_int(Key, Default) ->
    case (catch list_to_integer(get(Key, ""))) of
        N when is_integer(N) -> N;
        _ -> Default
    end.

-spec db_opts() -> #{host := string(), port := integer(), database := string(),
                       user := string(), password := string()}.
db_opts() ->
    #{host => get("DB_HOST", "127.0.0.1"),
      port => get_int("DB_PORT", 3306),
      database => get("DB_NAME", "nusachat"),
      user => get("DB_USER", "nusa"),
      password => get("DB_PASS", "")}.

-spec secret() -> binary().
secret() ->
    list_to_binary(get("APP_SECRET", "dev-secret-change-me-please-32-bytes-min")).

-spec port() -> integer().
port() ->
    get_int("REALTIME_PORT", 8091).
