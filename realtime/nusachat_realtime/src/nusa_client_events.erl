%%%-------------------------------------------------------------------
%%% @doc Aksi tulis-buta dari klien via WebSocket: `message.ack',
%%% `message.read', `chat.typing'. Fire-and-forget — server TIDAK
%%% membalas (bandingkan `location.update' yang butuh respons).
%%%
%%% Kenapa tiga ini lewat socket (bukan REST)? Karena ketiganya
%%% idempotent dan penelepon tak butuh respons: ack/read hanya
%%% memajukan penanda, typing efemeral. Kirim/ambil/edit/revoke
%%% tetap REST karena butuh respons tegas (id pesan, flag duplikat,
%%% error validasi) — membangun RPC-over-socket untuk itu hanya
%%% menduplikasi HTTP tanpa gain UX. Rasional lengkap di
%%% docs/ARCHITECTURE.md ("Hibrida REST + WS").
%%%
%%% Semantik = cermin endpoint REST (Conversations::ack/read/typing):
%%% cek anggota → tulis MySQL → fan-out event ke anggota LAIN yang
%%% online. Event dikirim LANGSUNG ke socket (tanpa baris
%%% realtime_events): offline tak butuh ketiganya — status baca/ack
%%% dipulihkan dari penanda persisten (last_read, recipients) saat
%%% fetch berikut, typing memang efemeral.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_client_events).

-export([handle/3, parse_frame/2]).

-type action() :: {ack, pos_integer(), pos_integer()}
                | {read, pos_integer(), pos_integer()}
                | {typing, pos_integer()}.

%%%-------------------------------------------------------------------
%%% @doc Tangani satu frame klien. Selalu `ok'; frame rusak/ilegal
%%% diabaikan + log (jangan putuskan koneksi karena satu frame busuk).
%%% @end
%%%-------------------------------------------------------------------
-spec handle(integer(), binary(), map()) -> ok.
handle(UserId, Type, Data) ->
    case parse_frame(Type, Data) of
        {ok, Action} ->
            run(UserId, Action);
        {error, Reason} ->
            error_logger:warning_msg("client event invalid ~p: ~p~n", [Type, Reason]),
            ok
    end.

%%%-------------------------------------------------------------------
%%% @doc Validasi frame murni (di-unit-test; tanpa I/O).
%%% @end
%%%-------------------------------------------------------------------
-spec parse_frame(binary(), map()) -> {ok, action()} | {error, term()}.
parse_frame(<<"message.ack">>, Data) ->
    case {frame_conv(Data), frame_msg(Data)} of
        {C, M} when is_integer(C), is_integer(M) -> {ok, {ack, C, M}};
        _ -> {error, bad_ids}
    end;
parse_frame(<<"message.read">>, Data) ->
    case {frame_conv(Data), frame_msg(Data)} of
        {C, M} when is_integer(C), is_integer(M) -> {ok, {read, C, M}};
        _ -> {error, bad_ids}
    end;
parse_frame(<<"chat.typing">>, Data) ->
    case frame_conv(Data) of
        C when is_integer(C) -> {ok, {typing, C}};
        _ -> {error, bad_conv}
    end;
parse_frame(Type, _Data) ->
    {error, {unknown_type, Type}}.

-spec frame_conv(map()) -> pos_integer() | undefined.
frame_conv(Data) ->
    pos_int(maps:get(<<"conversation_id">>, Data, undefined)).

-spec frame_msg(map()) -> pos_integer() | undefined.
frame_msg(Data) ->
    pos_int(maps:get(<<"message_id">>, Data, undefined)).

-spec pos_int(term()) -> pos_integer() | undefined.
pos_int(N) when is_integer(N), N > 0 -> N;
pos_int(_) -> undefined.

%% ------------------------------------------------------------------ run

-spec run(integer(), action()) -> ok.
run(UserId, {ack, ConvId, MsgId}) ->
    case is_member(ConvId, UserId) of
        true ->
            FullyAcked = ack_up_to(ConvId, UserId, MsgId),
            emit_except(ConvId, UserId, <<"ack">>,
                        #{<<"conversation_id">> => ConvId,
                          <<"user_id">> => UserId,
                          <<"message_id">> => MsgId,
                          <<"fully_acked">> => FullyAcked});
        false ->
            ok
    end;
run(UserId, {read, ConvId, MsgId}) ->
    case is_member(ConvId, UserId) of
        true ->
            mark_read(ConvId, UserId, MsgId),
            emit_except(ConvId, UserId, <<"read">>,
                        #{<<"conversation_id">> => ConvId,
                          <<"user_id">> => UserId,
                          <<"message_id">> => MsgId});
        false ->
            ok
    end;
run(UserId, {typing, ConvId}) ->
    case is_member(ConvId, UserId) of
        true ->
            Name = display_name(UserId),
            emit_except(ConvId, UserId, <<"typing">>,
                        #{<<"conversation_id">> => ConvId,
                          <<"user_id">> => UserId,
                          <<"display_name">> => Name});
        false ->
            ok
    end.

%% ------------------------------------------------------------------ sql
%% Cermin Message_model::ackUpTo + markReadUpTo + Conversation_model.

-spec is_member(integer(), integer()) -> boolean().
is_member(ConvId, UserId) ->
    case nusa_mysql:query(
           <<"SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?">>,
           [ConvId, UserId]) of
        {ok, _, [[1]]} -> true;
        {ok, _, [[<<"1">>]]} -> true;
        _ -> false
    end.

-spec member_ids(integer()) -> [integer()].
member_ids(ConvId) ->
    case nusa_mysql:query(
           <<"SELECT user_id FROM conversation_members WHERE conversation_id = ?">>,
           [ConvId]) of
        {ok, _, Rows} -> [to_int(U) || [U] <- Rows, to_int(U) > 0];
        _ -> []
    end.

-spec display_name(integer()) -> binary().
display_name(UserId) ->
    case nusa_mysql:query(
           <<"SELECT display_name FROM users WHERE id = ?">>, [UserId]) of
        {ok, _, [[Name]]} when is_binary(Name) -> Name;
        {ok, _, [[Name]]} when is_list(Name) -> list_to_binary(Name);
        _ -> <<>>
    end.

%% Cermin Message_model::ackUpTo. Balikan: id pesan yang full-ack.
-spec ack_up_to(integer(), integer(), integer()) -> [integer()].
ack_up_to(ConvId, UserId, MsgId) ->
    Now = nusa_fanout:cutoff_seconds_back(0),
    _ = nusa_mysql:query(
          <<"UPDATE message_recipients SET acked_at = ? WHERE user_id = ? AND acked_at IS NULL "
            "AND message_id IN (SELECT id FROM messages WHERE conversation_id = ? AND id <= ?)">>,
          [Now, UserId, ConvId, MsgId]),
    FullyAcked =
        case nusa_mysql:query(
               <<"SELECT m.id FROM messages m WHERE m.conversation_id = ? AND m.id <= ? "
                 "AND NOT EXISTS (SELECT 1 FROM message_recipients r "
                 "WHERE r.message_id = m.id AND r.acked_at IS NULL)">>,
               [ConvId, MsgId]) of
            {ok, _, Rows} -> [to_int(Id) || [Id] <- Rows];
            _ -> []
        end,
    case FullyAcked of
        [] ->
            ok;
        Ids ->
            Grace = nusa_fanout:cutoff_seconds_back(-86400),
            Ph = string:join(lists:duplicate(length(Ids), "?"), ","),
            Sql = iolist_to_binary(
                    ["UPDATE messages SET expires_at = ? WHERE id IN (", Ph, ") "
                     "AND (expires_at IS NULL OR expires_at > ?)"]),
            _ = nusa_mysql:query(Sql, [Grace | Ids] ++ [Grace]),
            ok
    end,
    FullyAcked.

-spec mark_read(integer(), integer(), integer()) -> ok.
mark_read(ConvId, UserId, MsgId) ->
    %% Atomik (lebih ketat dari baca-dulu PHP: tak ada race antar device).
    _ = nusa_mysql:query(
          <<"UPDATE conversation_members SET last_read_message_id = ? "
            "WHERE conversation_id = ? AND user_id = ? AND last_read_message_id < ?">>,
          [MsgId, ConvId, UserId, MsgId]),
    _ = nusa_mysql:query(
          <<"UPDATE messages SET status = ? WHERE conversation_id = ? AND sender_id <> ? "
            "AND id <= ? AND status <> ?">>,
          [<<"read">>, ConvId, UserId, MsgId, <<"read">>]),
    ok.

%% -------------------------------------------------------------- emit

%% Kirim event ke socket online semua anggota kecuali pengirim.
%% event_id = 0: klien tak memakai kursor event (catch-up via REST).
-spec emit_except(integer(), integer(), binary(), map()) -> ok.
emit_except(ConvId, SenderId, Type, Data) ->
    Payload = jsx:encode(#{<<"type">> => Type, <<"data">> => Data}),
    Ts = nusa_fanout:cutoff_seconds_back(0),
    Envelope = nusa_envelope:encode(0, Payload, Ts),
    Others = [U || U <- member_ids(ConvId), U =/= SenderId],
    lists:foreach(
      fun(UserId) ->
          lists:foreach(fun(Pid) -> Pid ! {deliver, Envelope} end,
                        nusa_registry:members(UserId))
      end, Others),
    ok.

-spec to_int(term()) -> integer().
to_int(N) when is_integer(N) -> N;
to_int(B) when is_binary(B) ->
    try binary_to_integer(B) catch _:_ -> 0 end;
to_int(L) when is_list(L) ->
    try list_to_integer(L) catch _:_ -> 0 end;
to_int(_) -> 0.
