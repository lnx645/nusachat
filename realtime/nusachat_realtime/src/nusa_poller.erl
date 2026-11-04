%%%-------------------------------------------------------------------
%%% @doc Push delivery: PHP POSTs new outbox rows to /internal/notify and
%%% they are fanned out instantly — NO polling loop. Cursor starts from
%%% MAX(id) at boot (one catch-up read, not a poll) and is persisted to
%%% disk so a restart redelivers rather than drops. Prune (24h) stays on
%%% a 5-minute maintenance timer.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_poller).

-behaviour(gen_server).

-export([start_link/0, notify/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-define(SERVER, ?MODULE).
-define(PRUNE_MS, 300000).
-define(SAVE_MS, 10000).

start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

notify(Events) when is_list(Events) ->
    gen_server:cast(?SERVER, {notify, Events}).

init([]) ->
    MaxDb = case nusa_mysql:query(
                   <<"SELECT COALESCE(MAX(id), 0) FROM realtime_events">>, []) of
        {ok, _, [[Max]]} when is_integer(Max) -> Max;
        _ -> 0
    end,
    Saved = read_cursor(),
    Cursor = case Saved of
        N when is_integer(N), N >= 0 -> min(N, MaxDb);
        _ -> MaxDb
    end,
    error_logger:info_msg("nusa_poller cursor init at ~p (db max ~p)~n", [Cursor, MaxDb]),
    erlang:send_after(?PRUNE_MS, self(), prune),
    erlang:send_after(?SAVE_MS, self(), save_cursor),
    {ok, #{cursor => Cursor, dirty => false}}.

handle_call(_Req, _From, State) ->
    {reply, {error, bad_request}, State}.

handle_cast({notify, Events}, #{cursor := Cursor} = State) ->
    Cursor2 = lists:foldl(fun deliver_json/2, Cursor, Events),
    {noreply, State#{cursor => Cursor2, dirty => true}};
handle_cast(_Msg, State) ->
    {noreply, State}.

handle_info(save_cursor, #{cursor := Cursor, dirty := Dirty} = State) ->
    case Dirty of
        true -> write_cursor(Cursor);
        false -> ok
    end,
    erlang:send_after(?SAVE_MS, self(), save_cursor),
    {noreply, State#{dirty => false}};
handle_info(prune, State) ->
    Cutoff = cutoff_24h(),
    case nusa_mysql:query(
           <<"DELETE FROM realtime_events WHERE created_at < ?">>, [Cutoff]) of
        {ok, N} when is_integer(N) ->
            error_logger:info_msg("nusa_poller pruned ~p rows~n", [N]);
        {ok, _} ->
            ok;
        _ ->
            ok
    end,
    erlang:send_after(?PRUNE_MS, self(), prune),
    {noreply, State};
handle_info(_Info, State) ->
    {noreply, State}.

terminate(_Reason, #{cursor := Cursor}) ->
    write_cursor(Cursor),
    ok;
terminate(_Reason, _State) ->
    ok.

%% Rows from PHP arrive as maps; normalize to the deliver/2 shape.
deliver_json(#{<<"id">> := Id, <<"user_id">> := UserId,
               <<"payload">> := Payload, <<"created_at">> := CreatedAt}, Cursor) ->
    deliver([Id, UserId, Payload, CreatedAt], Cursor);
deliver_json(_, Cursor) ->
    Cursor.

deliver([Id, UserId, Payload, CreatedAt], Cursor) ->
    NewCursor = max(Cursor, to_int(Id)),
    Pids = nusa_registry:members(to_int(UserId)),
    case Pids of
        [] -> ok;
        _ ->
            Envelope = encode_envelope(to_int(Id), Payload, CreatedAt),
            lists:foreach(fun(Pid) -> Pid ! {deliver, Envelope} end, Pids)
    end,
    NewCursor.

encode_envelope(Id, Payload, CreatedAt) ->
    {Type, Data} = try jsx:decode(iolist_to_binary(Payload), [return_maps]) of
        #{<<"type">> := T} = M -> {T, maps:get(<<"data">>, M, null)};
        _ -> {<<"event">>, null}
    catch
        _:_ -> {<<"event">>, null}
    end,
    Ts = case CreatedAt of
        Bin when is_binary(Bin) -> Bin;
        List when is_list(List) -> list_to_binary(List);
        _ -> <<>>
    end,
    jsx:encode(#{<<"event_id">> => Id,
                 <<"type">> => Type,
                 <<"data">> => Data,
                 <<"ts">> => Ts}).

to_int(N) when is_integer(N) -> N;
to_int(B) when is_binary(B) -> binary_to_integer(B);
to_int(L) when is_list(L) -> list_to_integer(L);
to_int(_) -> 0.

cursor_file() ->
    filename:join([data_dir(), "nusa_cursor"]).

data_dir() ->
    case os:getenv("REALTIME_DATA_DIR") of
        false -> "/tmp/nusa_realtime";
        "" -> "/tmp/nusa_realtime";
        Dir -> Dir
    end.

read_cursor() ->
    case file:read_file(cursor_file()) of
        {ok, Bin} ->
            try binary_to_integer(string:trim(Bin)) of
                N -> N
            catch
                _:_ -> undefined
            end;
        _ -> undefined
    end.

write_cursor(Cursor) ->
    _ = filelib:ensure_dir(cursor_file()),
    file:write_file(cursor_file(), integer_to_binary(Cursor)).

cutoff_24h() ->
    {{Y, Mo, D}, {H, Mi, S}} =
        calendar:system_time_to_universal_time(erlang:system_time(second) - 86400, second),
    iolist_to_binary(io_lib:format("~4..0B-~2..0B-~2..0BT~2..0B:~2..0B:~2..0BZ",
                                   [Y, Mo, D, H, Mi, S])).
