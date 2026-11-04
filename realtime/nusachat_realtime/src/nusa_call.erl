%%%-------------------------------------------------------------------
%%% @doc Signaling voice call `nusa.call.v1' (lihat docs/VOICE-PROTOCOL.md).
%%%
%%% Hot path call (offer/answer/end/room) langsung WS Erlang → WS lawan,
%%% tanpa baris `realtime_events' (catch-up via REST `incoming').
%%% SQL cermin `Call_model'/`Calls.php'; event fanout memakai nama lama
%%% (`call_offer', ...) agar klien existing tetap jalan.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_call).

-export([handle/3, parse_frame/2]).

%% Pure (diuji tanpa DB).
-export([row_to_call/1, row_to_user/1, now_iso/0, uuid4/0]).

-define(MAX_ROOM, 8).
-define(OFFER_PER_MIN, 20).
-define(KNOWN_CODECS, [<<"opus">>, <<"amr-wb">>]).

-type action() :: {offer, pos_integer(), [binary()], binary(), binary()}
                | {answer, pos_integer(), boolean(), binary()}
                | {reject, pos_integer()}
                | {hangup, pos_integer(), binary()}
                | {room_join, pos_integer()}
                | {room_leave, pos_integer()}
                | {echo, pos_integer(), boolean()}.

%%%-------------------------------------------------------------------
%%% @doc Satu pesan signaling dari `UserId'. Balik `ok' (fire-and-forget)
%%% atau `{reply, Type, Data}' (JSON `{type,data}' ke pengirim).
%%% @end
%%%-------------------------------------------------------------------
-spec handle(integer(), binary(), map()) -> ok | {reply, binary(), map()}.
handle(UserId, Type, Data) ->
    case parse_frame(Type, Data) of
        {ok, Action} ->
            run(UserId, Action);
        {error, Code, Msg} ->
            err(Code, Msg)
    end.

%%%-------------------------------------------------------------------
%%% @doc Validasi murni satu frame. Gagal → `{error, Code, Msg}'.
%%% @end
%%%-------------------------------------------------------------------
-spec parse_frame(binary(), map()) -> {ok, action()} | {error, binary(), binary()}.
parse_frame(<<"call.offer">>, Data) ->
    case {pos_int(maps:get(<<"to_user_id">>, Data, 0)),
          codecs(maps:get(<<"codecs">>, Data, [])),
          bin_cap(maps:get(<<"key_ct">>, Data, <<>>), 200),
          bin_cap(maps:get(<<"salt">>, Data, <<>>), 32),
          kind_ok(maps:get(<<"kind">>, Data, <<"audio">>))} of
        {0, _, _, _, _} -> {error, <<"invalid_target">>, <<"to_user_id wajib">>};
        {_, {error, _}, _, _, _} -> {error, <<"invalid_codecs">>, <<"codecs wajib subset opus/amr-wb">>};
        {_, _, error, _, _} -> {error, <<"invalid_key">>, <<"key_ct wajib">>};
        {_, _, _, error, _} -> {error, <<"invalid_key">>, <<"salt wajib">>};
        {_, _, _, _, false} -> {error, <<"invalid_kind">>, <<"nusa.call.v1 khusus audio">>};
        {To, {ok, Codecs}, KeyCt, Salt, true} ->
            {ok, {offer, To, Codecs, KeyCt, Salt}}
    end;
parse_frame(<<"call.answer">>, Data) ->
    case {pos_int(maps:get(<<"call_id">>, Data, 0)),
          to_bool(maps:get(<<"accept">>, Data, no)),
          bin_cap(maps:get(<<"codec">>, Data, <<>>), 16)} of
        {0, _, _} -> {error, <<"invalid_call">>, <<"call_id wajib">>};
        {_, no, _} -> {error, <<"invalid_accept">>, <<"accept wajib boolean">>};
        {_, _, error} -> {error, <<"invalid_codec">>, <<"codec wajib">>};
        {CallId, Accept, Codec} ->
            case lists:member(Codec, ?KNOWN_CODECS) of
                true -> {ok, {answer, CallId, Accept, Codec}};
                false -> {error, <<"invalid_codec">>, <<"codec tak dikenal">>}
            end
    end;
parse_frame(<<"call.reject">>, Data) ->
    simple_id(Data, reject);
parse_frame(<<"call.room_join">>, Data) ->
    simple_id(Data, room_join);
parse_frame(<<"call.room_leave">>, Data) ->
    simple_id(Data, room_leave);
parse_frame(<<"call.end">>, Data) ->
    case pos_int(maps:get(<<"call_id">>, Data, 0)) of
        0 -> {error, <<"invalid_call">>, <<"call_id wajib">>};
        CallId ->
            Reason = case bin_cap(maps:get(<<"reason">>, Data, <<"hangup">>), 16) of
                         error -> <<"hangup">>;
                         R -> R
                     end,
            {ok, {hangup, CallId, Reason}}
    end;
parse_frame(<<"call.echo">>, Data) ->
    case {pos_int(maps:get(<<"call_id">>, Data, 0)),
          to_bool(maps:get(<<"on">>, Data, no))} of
        {0, _} -> {error, <<"invalid_call">>, <<"call_id wajib">>};
        {_, no} -> {error, <<"invalid_echo">>, <<"on wajib boolean">>};
        {CallId, On} -> {ok, {echo, CallId, On}}
    end;
parse_frame(_, _) ->
    {error, <<"unknown_type">>, <<"tipe call tak dikenal">>}.

%%% ------------------------------------------------------------ run

run(Me, {offer, To, Codecs, KeyCt, Salt}) ->
    case offer_throttled(Me) of
        true -> err(<<"rate_limited">>, <<"terlalu sering">>);
        false -> run_offer(Me, To, Codecs, KeyCt, Salt)
    end;
run(Me, {answer, CallId, Accept, Codec}) ->
    case find_call(CallId) of
        {error, _} -> err(<<"not_found">>, <<"call tidak ada">>);
        {ok, Call} ->
            case maps:get(<<"callee_id">>, Call) =:= Me of
                false -> err(<<"forbidden">>, <<"bukan callee">>);
                true when Accept =:= false -> run_reject(Me, Call);
                true -> run_answer(Me, Call, Codec)
            end
    end;
run(Me, {reject, CallId}) ->
    case find_call(CallId) of
        {error, _} -> err(<<"not_found">>, <<"call tidak ada">>);
        {ok, Call} ->
            case maps:get(<<"callee_id">>, Call) =:= Me of
                false -> err(<<"forbidden">>, <<"bukan callee">>);
                true -> run_reject(Me, Call)
            end
    end;
run(Me, {hangup, CallId, Reason}) ->
    case find_call(CallId) of
        {error, _} -> err(<<"not_found">>, <<"call tidak ada">>);
        {ok, Call} -> run_end(Me, Call, Reason)
    end;
run(Me, {room_join, CallId}) ->
    case find_call(CallId) of
        {error, _} -> err(<<"not_found">>, <<"call tidak ada">>);
        {ok, Call} -> run_room_join(Me, Call)
    end;
run(Me, {room_leave, CallId}) ->
    case find_call(CallId) of
        {error, _} -> err(<<"not_found">>, <<"call tidak ada">>);
        {ok, Call} -> run_room_leave(Me, Call)
    end;
run(Me, {echo, CallId, On}) ->
    case find_call(CallId) of
        {error, _} -> err(<<"not_found">>, <<"call tidak ada">>);
        {ok, Call} ->
            case is_party(Me, Call) of
                false -> err(<<"forbidden">>, <<"bukan peserta">>);
                true -> nusa_voice_relay:set_echo(CallId, On), ok
            end
    end.

run_offer(Me, To, Codecs, KeyCt, Salt) ->
    case active_user(To) of
        false -> err(<<"not_found">>, <<"callee tidak ada">>);
        true when To =:= Me -> err(<<"invalid_target">>, <<"tak bisa call diri sendiri">>);
        true ->
            case {blocked(Me, To), blocked(To, Me)} of
                {true, _} -> err(<<"blocked">>, <<"pengguna diblokir">>);
                {_, true} -> err(<<"not_found">>, <<"callee tidak ada">>);
                {false, false} ->
                    %% Cek online DULU: offline → tanpa baris DB,
                    %% klien fallback REST (push terkirim dari sana).
                    case nusa_registry:members(To) of
                        [] -> {reply, <<"call.peer_offline">>,
                               #{<<"to_user_id">> => To}};
                        _ -> insert_offer(Me, To, Codecs, KeyCt, Salt)
                    end
            end
    end.

insert_offer(Me, To, Codecs, KeyCt, Salt) ->
    Now = now_iso(),
    Uuid = uuid4(),
    ok = must_ok(nusa_mysql:query(
        <<"INSERT INTO calls (uuid, caller_id, callee_id, kind, status, "
          "created_at, updated_at) VALUES (?, ?, ?, 'audio', 'ringing', ?, ?)">>,
        [Uuid, Me, To, Now, Now])),
    {ok, CallId} = last_id(),
    %% Slot 1-1 deterministik: caller = 0 (relay resolve via DB).
    Slot = 0,
    Token = nusa_voice_relay:call_token(CallId, Me),
    {ok, Call} = find_call(CallId),
    emit_to([To], <<"call_offer">>,
            #{<<"call">> => Call,
              <<"from">> => user_dto(Me),
              <<"codecs">> => Codecs,
              <<"key_ct">> => KeyCt,
              <<"salt">> => Salt}),
    {reply, <<"call.prepared">>,
     #{<<"call_id">> => CallId,
       <<"slot">> => Slot,
       <<"token">> => Token,
       <<"relay_port">> => nusa_voice_relay:port(),
       <<"call">> => Call}}.

run_answer(Me, Call, Codec) ->
    case maps:get(<<"status">>, Call) of
        <<"ringing">> ->
            CallId = maps:get(<<"id">>, Call),
            Caller = maps:get(<<"caller_id">>, Call),
            Now = now_iso(),
            must_ok(nusa_mysql:query(
                <<"UPDATE calls SET status = 'active', answered_at = ?, "
                  "updated_at = ? WHERE id = ?">>,
                [Now, Now, CallId])),
            %% Slot 1-1 deterministik: callee = 1.
            Slot = 1,
            Token = nusa_voice_relay:call_token(CallId, Me),
            {ok, Call2} = find_call(CallId),
            emit_to([Caller], <<"call_answer">>,
                    #{<<"call">> => Call2,
                      <<"from_user_id">> => Me,
                      <<"codec">> => Codec}),
            {reply, <<"call.prepared">>,
             #{<<"call_id">> => CallId,
               <<"slot">> => Slot,
               <<"token">> => Token,
               <<"relay_port">> => nusa_voice_relay:port(),
               <<"call">> => Call2}};
        _ -> err(<<"gone">>, <<"call sudah berakhir">>)
    end.

run_reject(Me, Call) ->
    case maps:get(<<"status">>, Call) of
        <<"ringing">> ->
            CallId = maps:get(<<"id">>, Call),
            terminal(CallId, <<"rejected">>),
            nusa_voice_relay:purge(CallId),
            emit_to([maps:get(<<"caller_id">>, Call)], <<"call_end">>,
                    #{<<"call_id">> => CallId,
                      <<"from_user_id">> => Me,
                      <<"reason">> => <<"rejected">>}),
            ok;
        _ -> err(<<"gone">>, <<"call sudah berakhir">>)
    end.

run_end(Me, Call, Reason) ->
    case maps:get(<<"conversation_id">>, Call) of
        null -> run_end_direct(Me, Call, Reason);
        _ConvId -> run_room_leave(Me, Call)
    end.

run_end_direct(Me, Call, Reason) ->
    CallId = maps:get(<<"id">>, Call),
    Peer = case maps:get(<<"caller_id">>, Call) =:= Me of
               true -> maps:get(<<"callee_id">>, Call);
               false ->
                   case maps:get(<<"callee_id">>, Call) =:= Me of
                       true -> maps:get(<<"caller_id">>, Call);
                       false -> none
                   end
           end,
    case Peer of
        none -> err(<<"forbidden">>, <<"bukan peserta">>);
        _ ->
            case maps:get(<<"status">>, Call) of
                S when S =:= <<"ended">>; S =:= <<"rejected">>; S =:= <<"missed">> ->
                    nusa_voice_relay:purge(CallId),
                    ok;
                _ ->
                    Final = case Reason of
                                <<"missed">> -> <<"missed">>;
                                _ -> <<"ended">>
                            end,
                    terminal(CallId, Final),
                    nusa_voice_relay:purge(CallId),
                    emit_to([Peer], <<"call_end">>,
                            #{<<"call_id">> => CallId,
                              <<"from_user_id">> => Me,
                              <<"reason">> => Reason}),
                    ok
            end
    end.

run_room_join(Me, Call) ->
    CallId = maps:get(<<"id">>, Call),
    case maps:get(<<"conversation_id">>, Call) of
        null -> err(<<"forbidden">>, <<"bukan room call">>);
        ConvId ->
            Users = participant_ids(CallId),
            case {is_member(ConvId, Me), Users} of
                {false, _} -> err(<<"forbidden">>, <<"bukan anggota">>);
                {_, U} when length(U) >= ?MAX_ROOM ->
                    %% Rejoin peserta lama tetap boleh (slot existing).
                    case lists:member(Me, U) of
                        true -> run_room_join_known(Me, CallId, U);
                        false -> err(<<"room_full">>, <<"room penuh">>)
                    end;
                {true, _} ->
                    Now = now_iso(),
                    must_ok(nusa_mysql:query(
                        <<"INSERT INTO call_participants (call_id, user_id, status, "
                          "created_at, updated_at) VALUES (?, ?, 'joined', ?, ?) "
                          "ON DUPLICATE KEY UPDATE status = 'joined', updated_at = ?">>,
                        [CallId, Me, Now, Now, Now])),
                    %% Baca ulang SETELAH insert: append-only id-ASC → indeks
                    %% stabil walau join bersamaan.
                    run_room_join_known(Me, CallId, participant_ids(CallId))
            end
    end.

%% @doc Finalisasi join: slot = indeks di daftar id-ASC + kunci room.
run_room_join_known(Me, CallId, Users) ->
    Slot = nusa_voice_relay:room_slot(Users, Me),
    Token = nusa_voice_relay:call_token(CallId, Me),
    {ok, Call2} = find_call(CallId),
    Others = joined_ids(CallId) -- [Me],
    emit_to(Others, <<"call_room_joined">>,
            #{<<"call_id">> => CallId,
              <<"user">> => user_dto(Me),
              <<"slot">> => Slot}),
    {reply, <<"call.prepared">>,
     #{<<"call_id">> => CallId,
       <<"slot">> => Slot,
       <<"token">> => Token,
       <<"relay_port">> => nusa_voice_relay:port(),
       <<"key_ct">> => room_key(CallId),
       <<"salt">> => room_salt(CallId),
       <<"call">> => Call2}}.

%% @doc Kunci media room (32B) + salt (4B), b64url tanpa padding:
%% HMAC(APP_SECRET, "nvp-room-key"|"nvp-room-salt" | call_id:64).
%% Dihitung identik oleh PHP (`Calls::nvp_join'); uji silang di smoke.
room_key(CallId) ->
    b64url_nopad(crypto:mac(hmac, sha256, nusa_config:secret(),
                            <<"nvp-room-key", CallId:64>>)).

room_salt(CallId) ->
    Full = crypto:mac(hmac, sha256, nusa_config:secret(),
                      <<"nvp-room-salt", CallId:64>>),
    b64url_nopad(binary:part(Full, 0, 4)).

b64url_nopad(Bin) ->
    B64 = base64:encode(Bin),
    NoPad = binary:replace(B64, <<"=">>, <<>>, [global]),
    Step = binary:replace(NoPad, <<"+">>, <<"-">>, [global]),
    binary:replace(Step, <<"/">>, <<"_">>, [global]).

run_room_leave(Me, Call) ->
    CallId = maps:get(<<"id">>, Call),
    Now = now_iso(),
    must_ok(nusa_mysql:query(
        <<"UPDATE call_participants SET status = 'left', updated_at = ? "
          "WHERE call_id = ? AND user_id = ?">>,
        [Now, CallId, Me])),
    nusa_voice_relay:leave(CallId, Me),
    Rest = joined_ids(CallId) -- [Me],
    emit_to(Rest, <<"call_room_left">>,
            #{<<"call_id">> => CallId,
              <<"user_id">> => Me}),
    case Rest of
        [] ->
            terminal(CallId, <<"ended">>),
            nusa_voice_relay:purge(CallId),
            case maps:get(<<"conversation_id">>, Call) of
                null -> ok;
                ConvId -> emit_to(member_ids(ConvId), <<"call_room_ended">>,
                                  #{<<"call_id">> => CallId})
            end;
        _ -> ok
    end,
    ok.

%%% ------------------------------------------------------------ emit

%% Event langsung ke socket (event_id 0, pola emit_except): klien tak memakai
%% kursor event; catch-up via REST.
emit_to(UserIds, Type, Data) ->
    Payload = jsx:encode(#{<<"type">> => Type, <<"data">> => Data}),
    Ts = nusa_fanout:cutoff_seconds_back(0),
    Envelope = nusa_envelope:encode(0, Payload, Ts),
    lists:foreach(
      fun(UserId) ->
          lists:foreach(fun(Pid) -> Pid ! {deliver, Envelope} end,
                        nusa_registry:members(UserId))
      end, UserIds),
    ok.

%%% ------------------------------------------------------------ sql

find_call(CallId) ->
    case nusa_mysql:query(
           <<"SELECT id, uuid, caller_id, callee_id, conversation_id, kind, "
             "status, created_at, updated_at, answered_at, ended_at "
             "FROM calls WHERE id = ?">>,
           [CallId]) of
        {ok, _, [Row]} -> {ok, row_to_call(Row)};
        {ok, _, []} -> {error, not_found};
        {error, R} -> {error, R}
    end.

active_user(UserId) ->
    case nusa_mysql:query(
           <<"SELECT id FROM users WHERE id = ? AND is_active = 1">>,
           [UserId]) of
        {ok, _, [_|_]} -> true;
        _ -> false
    end.

blocked(A, B) ->
    case nusa_mysql:query(
           <<"SELECT 1 FROM blocks WHERE user_id = ? AND blocked_user_id = ?">>,
           [A, B]) of
        {ok, _, [_|_]} -> true;
        _ -> false
    end.

offer_throttled(UserId) ->
    Now = calendar:datetime_to_gregorian_seconds(calendar:universal_time()),
    Cutoff = iso_ts(Now - 60),
    case nusa_mysql:query(
           <<"SELECT COUNT(*) FROM calls WHERE caller_id = ? AND created_at > ?">>,
           [UserId, Cutoff]) of
        {ok, _, [[N]]} -> to_int(N) >= ?OFFER_PER_MIN;
        _ -> false
    end.

user_dto(UserId) ->
    case nusa_mysql:query(
           <<"SELECT id, username, display_name, avatar_url FROM users WHERE id = ?">>,
           [UserId]) of
        {ok, _, [Row]} -> row_to_user(Row);
        _ -> #{<<"id">> => UserId}
    end.

terminal(CallId, Status) ->
    Now = now_iso(),
    must_ok(nusa_mysql:query(
        <<"UPDATE calls SET status = ?, ended_at = ?, updated_at = ? WHERE id = ?">>,
        [Status, Now, Now, CallId])).

joined_ids(CallId) ->
    case nusa_mysql:query(
           <<"SELECT user_id FROM call_participants WHERE call_id = ? AND status = 'joined'">>,
           [CallId]) of
        {ok, _, Rows} -> [to_int(U) || [U] <- Rows];
        _ -> []
    end.

participant_ids(CallId) ->
    case nusa_mysql:query(
           <<"SELECT user_id FROM call_participants WHERE call_id = ? ORDER BY id ASC">>,
           [CallId]) of
        {ok, _, Rows} -> [to_int(U) || [U] <- Rows];
        _ -> []
    end.

is_member(ConvId, UserId) ->
    case nusa_mysql:query(
           <<"SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?">>,
           [ConvId, UserId]) of
        {ok, _, [_|_]} -> true;
        _ -> false
    end.

member_ids(ConvId) ->
    case nusa_mysql:query(
           <<"SELECT user_id FROM conversation_members WHERE conversation_id = ?">>,
           [ConvId]) of
        {ok, _, Rows} -> [to_int(U) || [U] <- Rows];
        _ -> []
    end.

is_party(Me, Call) ->
    Me =:= maps:get(<<"caller_id">>, Call)
        orelse Me =:= maps:get(<<"callee_id">>, Call)
        orelse lists:member(Me, joined_ids(maps:get(<<"id">>, Call))).

last_id() ->
    case nusa_mysql:query(<<"SELECT LAST_INSERT_ID()">>, []) of
        {ok, _, [[Id]]} -> {ok, to_int(Id)};
        {error, R} -> {error, R};
        _ -> {error, no_id}
    end.

must_ok(ok) -> ok;
must_ok({error, R}) -> erlang:error({db_unavailable, R}).

err(Code, Msg) -> {reply, <<"call.error">>, #{<<"code">> => Code, <<"message">> => Msg}}.

%%% ------------------------------------------------------------ pure

%% Baris SELECT calls (11 kolom) → DTO cermin `Calls::call_dto`.
-spec row_to_call(list()) -> map().
row_to_call([Id, Uuid, Caller, Callee, Conv, Kind, Status, Created, Updated, Answered, Ended]) ->
    #{<<"id">> => to_int(Id),
      <<"uuid">> => to_bin(Uuid),
      <<"caller_id">> => to_int(Caller),
      <<"callee_id">> => to_int(Callee),
      <<"conversation_id">> => to_null(Conv),
      <<"kind">> => to_bin(Kind),
      <<"status">> => to_bin(Status),
      <<"created_at">> => to_bin(Created),
      <<"updated_at">> => to_bin(Updated),
      <<"answered_at">> => to_null(Answered),
      <<"ended_at">> => to_null(Ended)}.

%% Baris SELECT users (4 kolom) → DTO kecil `from'/`user'.
-spec row_to_user(list()) -> map().
row_to_user([Id, Username, Display, Avatar]) ->
    #{<<"id">> => to_int(Id),
      <<"username">> => to_null(Username),
      <<"display_name">> => to_null(Display),
      <<"avatar_url">> => to_null(Avatar)}.

%% ISO UTC cermin `now_iso()` PHP (sortable sebagai string).
-spec now_iso() -> binary().
now_iso() ->
    iso_ts(calendar:datetime_to_gregorian_seconds(calendar:universal_time())).

iso_ts(Greg) ->
    {{Y, Mo, D}, {H, Mi, S}} = calendar:gregorian_seconds_to_datetime(Greg),
    iolist_to_binary(io_lib:format("~4..0B-~2..0B-~2..0BT~2..0B:~2..0B:~2..0BZ",
                                   [Y, Mo, D, H, Mi, S])).

%% UUID v4 cermin `uuid4()` PHP.
-spec uuid4() -> binary().
uuid4() ->
    <<A:32, B:16, C0:16, D0:16, E:48>> = crypto:strong_rand_bytes(16),
    C = (C0 band 16#0fff) bor 16#4000,
    D = (D0 band 16#3fff) bor 16#8000,
    iolist_to_binary(io_lib:format("~8.16.0b-~4.16.0b-~4.16.0b-~4.16.0b-~12.16.0b",
                                   [A, B, C, D, E])).

%%% ------------------------------------------------------------ coerce

simple_id(Data, Tag) ->
    case pos_int(maps:get(<<"call_id">>, Data, 0)) of
        0 -> {error, <<"invalid_call">>, <<"call_id wajib">>};
        CallId -> {ok, {Tag, CallId}}
    end.

pos_int(N) when is_integer(N), N > 0 -> N;
pos_int(_) -> 0.

codecs(L) when is_list(L), L =/= [], length(L) =< 4 ->
    case lists:all(fun(C) -> is_binary(C) andalso lists:member(C, ?KNOWN_CODECS) end, L) of
        true -> {ok, L};
        false -> {error, bad}
    end;
codecs(_) -> {error, bad}.

bin_cap(B, Max) when is_binary(B), byte_size(B) >= 1, byte_size(B) =< Max -> B;
bin_cap(_, _) -> error.

kind_ok(<<"audio">>) -> true;
kind_ok(_) -> false.

to_bool(true) -> true;
to_bool(false) -> false;
to_bool(_) -> no.

to_int(N) when is_integer(N) -> N;
to_int(B) when is_binary(B) ->
    try binary_to_integer(B) catch _:_ -> 0 end;
to_int(L) when is_list(L) ->
    try list_to_integer(L) catch _:_ -> 0 end;
to_int(_) -> 0.

to_bin(B) when is_binary(B) -> B;
to_bin(L) when is_list(L) -> list_to_binary(L);
to_bin(N) when is_integer(N) -> integer_to_binary(N);
to_bin(_) -> <<>>.

to_null(null) -> null;
to_null(undefined) -> null;
to_null(B) when is_binary(B) -> B;
to_null(L) when is_list(L) -> list_to_binary(L);
to_null(N) when is_integer(N) -> N;
to_null(_) -> null.
