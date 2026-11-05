%%%-------------------------------------------------------------------
%%% @doc Presence berbasis socket: online = punya koneksi WS hidup.
%%%
%%% `nusa_registry' memanggil `online/1' saat socket pertama user
%%% terdaftar dan `offline/1' saat socket terakhir lepas (termasuk
%%% DOWN/monitor). Keduanya kembali LANGSUNG (spawn pekerja) agar
%%% registry tak pernah blokir pada I/O MySQL.
%%%
%%% Pekerja menulis `users.is_online' + `last_seen_at' lalu menyiarkan
%%% event `presence' LANGSUNG ke socket tetangga yang online (tanpa
%%% baris realtime_events): user offline tak butuh presence — snapshot
%%% awal mereka baca dari kolom `is_online' saat fetch REST.
%%%
%%% Tidak ada heartbeat/polling: koneksi itu sendiri adalah sinyal.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_presence).

-export([online/1, offline/1, reset_all/0]).
-export([transition/2, payload/3]).

-spec online(integer()) -> ok.
online(UserId) ->
    spawn(fun() -> apply_transition(UserId, true) end),
    ok.

-spec offline(integer()) -> ok.
offline(UserId) ->
    spawn(fun() -> apply_transition(UserId, false) end),
    ok.

%%%-------------------------------------------------------------------
%%% @doc Reset boot: registry mulai kosong → semua offline. Dipanggil
%%% sekali saat supervisor naik (lihat nusa_registry:init) agar
%%% `is_online' basi (node crash tanpa terminate) tak menggantung.
%%% @end
%%%-------------------------------------------------------------------
-spec reset_all() -> ok.
reset_all() ->
    try nusa_mysql:query(<<"UPDATE users SET is_online = 0">>, []) of
        _ -> ok
    catch C:R ->
        error_logger:warning_msg("presence reset failed ~p:~p~n", [C, R]),
        ok
    end.

%%%-------------------------------------------------------------------
%%% @doc Transisi murni dari jumlah socket sebelum/sesudah perubahan
%%% (di-unit-test; tanpa I/O).
%%% @end
%%%-------------------------------------------------------------------
-spec transition(non_neg_integer(), non_neg_integer()) -> online | offline | stable.
transition(0, N) when N > 0 -> online;
transition(N, 0) when N > 0 -> offline;
transition(_, _) -> stable.

%%%-------------------------------------------------------------------
%%% @doc Payload kabel `presence' (murni, di-unit-test).
%%% @end
%%%-------------------------------------------------------------------
-spec payload(integer(), boolean(), binary()) -> map().
payload(UserId, Online, LastSeen) ->
    #{<<"user_id">> => UserId,
      <<"online">> => Online,
      <<"last_seen_at">> => LastSeen}.

%% ------------------------------------------------------------------ worker

-spec apply_transition(integer(), boolean()) -> ok.
apply_transition(UserId, Online) ->
    try
        Now = nusa_fanout:cutoff_seconds_back(0),
        Flag = case Online of true -> 1; false -> 0 end,
        _ = nusa_mysql:query(
              <<"UPDATE users SET is_online = ?, last_seen_at = ? WHERE id = ?">>,
              [Flag, Now, UserId]),
        %% Tulis DB dulu, siar kemudian: penerima event yang langsung
        %% fetch REST dijamin melihat snapshot yang konsisten.
        emit_to_neighbors(UserId, payload(UserId, Online, Now), Now)
    catch C:R ->
        error_logger:warning_msg("presence failed uid=~p online=~p ~p:~p~n",
                                 [UserId, Online, C, R]),
        ok
    end,
    ok.

-spec emit_to_neighbors(integer(), map(), binary()) -> ok.
emit_to_neighbors(UserId, Data, Now) ->
    case neighbor_ids(UserId) of
        [] ->
            ok;
        Neighbors ->
            Payload = jsx:encode(#{<<"type">> => <<"presence">>,
                                   <<"data">> => Data}),
            Envelope = nusa_envelope:encode(0, Payload, Now),
            lists:foreach(
              fun(N) ->
                  lists:foreach(fun(Pid) -> Pid ! {deliver, Envelope} end,
                                nusa_registry:members(N))
              end, Neighbors),
            ok
    end.

%% Cermin Conversation_model::peerUserIds (semua teman chat).
-spec neighbor_ids(integer()) -> [integer()].
neighbor_ids(UserId) ->
    case nusa_mysql:query(
           <<"SELECT DISTINCT m2.user_id FROM conversation_members m1 "
             "JOIN conversation_members m2 ON m2.conversation_id = m1.conversation_id "
             "WHERE m1.user_id = ? AND m2.user_id != ?">>,
           [UserId, UserId]) of
        {ok, _, Rows} ->
            [U || [R] <- Rows, U <- [to_int(R)], U > 0, U =/= UserId];
        _ ->
            []
    end.

-spec to_int(term()) -> integer().
to_int(N) when is_integer(N) -> N;
to_int(B) when is_binary(B) ->
    try binary_to_integer(B) catch _:_ -> 0 end;
to_int(L) when is_list(L) ->
    try list_to_integer(L) catch _:_ -> 0 end;
to_int(_) -> 0.
