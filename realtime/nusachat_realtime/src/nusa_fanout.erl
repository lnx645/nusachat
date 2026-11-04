%%%-------------------------------------------------------------------
%%% @doc Fan-out push ke socket: PHP menulis outbox lalu POST baris baru
%%% ke /internal/notify, dan modul ini mengantarnya seketika ke semua
%%% socket user tujuan — TANPA polling loop. (Nama lama modul ini
%%% `nusa_poller', diganti karena menyesatkan; tidak ada lagi polling.)
%%%
%%% Cursor = id event terbesar yang sudah diantar. Saat boot, cursor
%%% dibaca dari disk (`REALTIME_DATA_DIR/nusa_cursor') supaya restart
%%% mengirim ulang (redeliver), bukan menghilangkan. Cursor ditulis
%%% tiap 10 detik bila kotor + saat shutdown. Prune baris > 24 jam
%%% jalan tiap 5 menit sebagai maintenance.
%%%
%%% Aturan pengiriman: event hanya dikirim ke socket yang sedang
%%% terhubung (`nusa_registry'); user offline tidak menerima apa pun
%%% dari sini — mereka catch-up via REST (`after_id') saat online lagi.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_fanout).

-behaviour(gen_server).

-export([start_link/0, notify/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-define(SERVER, ?MODULE).
-define(PRUNE_MS, 300000).
-define(SAVE_MS, 10000).

-type state() :: #{cursor := non_neg_integer(), dirty := boolean()}.

-spec start_link() -> {ok, pid()} | {error, term()}.
start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

%%%-------------------------------------------------------------------
%%% @doc Antar daftar event outbox (map hasil decode JSON dari PHP).
%%% Async (cast): pengirim HTTP tidak menunggu pengantaran selesai.
%%% @end
%%%-------------------------------------------------------------------
-spec notify([map()]) -> ok.
notify(Events) when is_list(Events) ->
    gen_server:cast(?SERVER, {notify, Events}).

-spec init([]) -> {ok, state()}.
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
    error_logger:info_msg("nusa_fanout cursor init at ~p (db max ~p)~n", [Cursor, MaxDb]),
    erlang:send_after(?PRUNE_MS, self(), prune),
    erlang:send_after(?SAVE_MS, self(), save_cursor),
    {ok, #{cursor => Cursor, dirty => false}}.

-spec handle_call(term(), term(), state()) -> {reply, term(), state()}.
handle_call(_Req, _From, State) ->
    {reply, {error, bad_request}, State}.

-spec handle_cast(term(), state()) -> {noreply, state()}.
handle_cast({notify, Events}, #{cursor := Cursor} = State) ->
    Cursor2 = lists:foldl(fun deliver_json/2, Cursor, Events),
    {noreply, State#{cursor => Cursor2, dirty => true}};
handle_cast(_Msg, State) ->
    {noreply, State}.

-spec handle_info(term(), state()) -> {noreply, state()}.
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
            error_logger:info_msg("nusa_fanout pruned ~p rows~n", [N]);
        {ok, _} ->
            ok;
        _ ->
            ok
    end,
    erlang:send_after(?PRUNE_MS, self(), prune),
    {noreply, State};
handle_info(_Info, State) ->
    {noreply, State}.

-spec terminate(term(), state()) -> ok.
terminate(_Reason, #{cursor := Cursor}) ->
    write_cursor(Cursor),
    ok;
terminate(_Reason, _State) ->
    ok.

%% Rows from PHP arrive as maps; normalize to the deliver/2 shape.
%% Baris tak dikenal dilewati (cursor tidak maju untuknya).
-spec deliver_json(map(), non_neg_integer()) -> non_neg_integer().
deliver_json(#{<<"id">> := Id, <<"user_id">> := UserId,
               <<"payload">> := Payload, <<"created_at">> := CreatedAt}, Cursor) ->
    deliver([Id, UserId, Payload, CreatedAt], Cursor);
deliver_json(_, Cursor) ->
    Cursor.

-spec deliver(list(), non_neg_integer()) -> non_neg_integer().
deliver([Id, UserId, Payload, CreatedAt], Cursor) ->
    NewCursor = max(Cursor, to_int(Id)),
    Pids = nusa_registry:members(to_int(UserId)),
    case Pids of
        [] -> ok;
        _ ->
            Envelope = nusa_envelope:encode(to_int(Id), Payload, CreatedAt),
            lists:foreach(fun(Pid) -> Pid ! {deliver, Envelope} end, Pids)
    end,
    NewCursor.

-spec to_int(term()) -> integer().
to_int(N) when is_integer(N) -> N;
to_int(B) when is_binary(B) -> binary_to_integer(B);
to_int(L) when is_list(L) -> list_to_integer(L);
to_int(_) -> 0.

-spec cursor_file() -> file:filename().
cursor_file() ->
    filename:join([data_dir(), "nusa_cursor"]).

-spec data_dir() -> file:filename().
data_dir() ->
    case os:getenv("REALTIME_DATA_DIR") of
        false -> "/tmp/nusa_realtime";
        "" -> "/tmp/nusa_realtime";
        Dir -> Dir
    end.

-spec read_cursor() -> integer() | undefined.
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

-spec write_cursor(non_neg_integer()) -> ok | {error, term()}.
write_cursor(Cursor) ->
    _ = filelib:ensure_dir(cursor_file()),
    file:write_file(cursor_file(), integer_to_binary(Cursor)).

-spec cutoff_24h() -> binary().
cutoff_24h() ->
    {{Y, Mo, D}, {H, Mi, S}} =
        calendar:system_time_to_universal_time(erlang:system_time(second) - 86400, second),
    iolist_to_binary(io_lib:format("~4..0B-~2..0B-~2..0BT~2..0B:~2..0B:~2..0BZ",
                                   [Y, Mo, D, H, Mi, S])).
