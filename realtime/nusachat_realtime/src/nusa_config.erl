%%%-------------------------------------------------------------------
%%% @doc Config: OS env wins, else dev defaults matching backend/.env.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_config).

-export([get/2, db_opts/0, secret/0, port/0]).

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

db_opts() ->
    #{host => get("DB_HOST", "127.0.0.1"),
      port => get_int("DB_PORT", 3306),
      database => get("DB_NAME", "nusachat"),
      user => get("DB_USER", "nusa"),
      password => get("DB_PASS", "")}.

secret() ->
    list_to_binary(get("APP_SECRET", "dev-secret-change-me-please-32-bytes-min")).

port() ->
    get_int("REALTIME_PORT", 8091).
