%%%-------------------------------------------------------------------
%%% @doc Relay UDP voice NVP/1 (lihat docs/VOICE-PROTOCOL.md §3.5).
%%%
%%% Meneruskan datagram antar peserta call TANPA menyentuh isi (buta
%%% media). Sesi = {(call_id,slot) -> endpoint+token}; endpoint dipelajari
%%% dari paket terautentikasi pertama (ikat NAT) dan di-update bila
%%% pengirim pindah alamat (rebinding). Mode echo memantulkan paket ke
%%% pengirim (uji rantai penuh 1 HP).
%%%
%%% Keterbatasan jujur v1: sesi ephemeral (restart relay = media putus,
%%% signaling utuh, user redial); anti-flood = rate-limit per pengirim.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_voice_relay).

-behaviour(gen_server).

-export([start_link/0, port/0, leave/2, purge/1, set_echo/2,
         stats/1]).

%% Derivasi deterministik (dipakai signaling + validasi relay).
-export([call_token/2, room_slot/2]).

%% Pure (diuji tanpa socket).
-export([parse_header/1, targets/3, bucket/3]).

-export([init/1, handle_call/3, handle_cast/2, handle_info/2,
         terminate/2]).

-define(SERVER, ?MODULE).
-define(SESS, voice_sessions).
-define(RATE, voice_rates).
-define(ECHO, voice_echo).
-define(STATS, voice_stats).

-define(MAGIC, 16#4E).
-define(VER, 16#01).
-define(HEADER_LEN, 18).
-define(TAG_LEN, 16).
-define(MAX_DGRAM, 1200).
-define(MAX_SLOT, 255).
-define(MAX_ROOM, 8).
-define(RATE_CAP, 150).
-define(RATE_PER_SEC, 100).
-define(SESSION_TTL_MS, 30000).
-define(SWEEP_MS, 15000).

-define(FLAG_REDUNDANT, 16#01).
-define(FLAG_COMFORT, 16#02).
-define(FLAG_KEEPALIVE, 16#04).

-record(state, {sock :: port()}).

%%% ------------------------------------------------------------ API

-spec start_link() -> {ok, pid()} | {error, term()}.
start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

%% @doc Port UDP relay (info ke klien via `call.prepared').
-spec port() -> integer().
port() ->
    nusa_config:voice_port().

%% @doc Token sesi deterministik: 4 byte pertama
%% HMAC-SHA256(APP_SECRET, "nvp-v1"|call_id:64|user_id:64).
%% Dihitung identik oleh PHP (`Calls::nvp_join'); uji silang di smoke.
-spec call_token(pos_integer(), pos_integer()) -> non_neg_integer().
call_token(CallId, UserId) ->
    Mac = crypto:mac(hmac, sha256, nusa_config:secret(),
                     <<"nvp-v1", CallId:64, UserId:64>>),
    binary:decode_unsigned(binary:part(Mac, 0, 4)).

%% @doc Slot room = indeks user di peserta ORDER BY id ASC (stabil;
%% append-only walau join bersamaan). -1 bila bukan peserta.
-spec room_slot([pos_integer()], pos_integer()) -> integer().
room_slot(UserIds, UserId) ->
    room_slot(UserIds, UserId, 0).
room_slot([], _, _) -> -1;
room_slot([U | _], U, N) -> N;
room_slot([_ | Rest], U, N) -> room_slot(Rest, U, N + 1).

-spec leave(pos_integer(), pos_integer()) -> ok.
leave(CallId, UserId) ->
    gen_server:call(?SERVER, {leave, CallId, UserId}).

-spec purge(pos_integer()) -> ok.
purge(CallId) ->
    gen_server:call(?SERVER, {purge, CallId}).

-spec set_echo(pos_integer(), boolean()) -> ok.
set_echo(CallId, On) ->
    gen_server:call(?SERVER, {echo, CallId, On}).

%% @doc Counter per call: #{packets,bytes,forwarded,dropped}.
-spec stats(pos_integer()) -> map().
stats(CallId) ->
    gen_server:call(?SERVER, {stats, CallId}).

%%% ------------------------------------------------------------ gen_server

-spec init([]) -> {ok, #state{}}.
init([]) ->
    ets:new(?SESS, [named_table, set, private]),
    ets:new(?RATE, [named_table, set, private]),
    ets:new(?ECHO, [named_table, set, private]),
    ets:new(?STATS, [named_table, set, private]),
    Port = nusa_config:voice_port(),
    case gen_udp:open(Port, [binary, {active, true}, {reuseaddr, true}]) of
        {ok, Sock} ->
            error_logger:info_msg("nusa_voice_relay listening on 0.0.0.0:~p~n",
                                  [Port]),
            erlang:send_after(?SWEEP_MS, self(), sweep),
            {ok, #state{sock = Sock}};
        {error, Reason} ->
            {stop, Reason}
    end.

-spec handle_call(term(), term(), #state{}) -> {reply, term(), #state{}}.
handle_call({leave, CallId, UserId}, _From, State) ->
    [ets:delete(?SESS, {CallId, S})
     || {{C, S}, {U, _, _, _, _}} <- ets:tab2list(?SESS),
        C =:= CallId, U =:= UserId],
    {reply, ok, State};
handle_call({purge, CallId}, _From, State) ->
    [ets:delete(?SESS, K) || {K = {C, _}, _} <- ets:tab2list(?SESS),
                              C =:= CallId],
    [ets:delete(?RATE, K) || {K = {C, _}, _} <- ets:tab2list(?RATE),
                              C =:= CallId],
    ets:delete(?ECHO, CallId),
    ets:delete(?STATS, CallId),
    {reply, ok, State};
handle_call({echo, CallId, On}, _From, State) ->
    ets:insert(?ECHO, {CallId, On}),
    {reply, ok, State};
handle_call({stats, CallId}, _From, State) ->
    {reply, read_stats(CallId), State};
handle_call(_Req, _From, State) ->
    {reply, {error, bad_request}, State}.

-spec handle_cast(term(), #state{}) -> {noreply, #state{}}.
handle_cast(_Msg, State) ->
    {noreply, State}.

-spec handle_info(term(), #state{}) -> {noreply, #state{}}.
handle_info({udp, Sock, Ip, InPort, Bin}, #state{sock = Sock} = State) ->
    handle_packet(Sock, Ip, InPort, Bin),
    {noreply, State};
handle_info(sweep, State) ->
    sweep_sessions(),
    erlang:send_after(?SWEEP_MS, self(), sweep),
    {noreply, State};
handle_info(_Info, State) ->
    {noreply, State}.

-spec terminate(term(), #state{}) -> ok.
terminate(_Reason, #state{sock = Sock}) ->
    catch gen_udp:close(Sock),
    ok.

%%% ------------------------------------------------------------ hot path

handle_packet(Sock, Ip, InPort, Bin) ->
    case parse_header(Bin) of
        {error, _} ->
            ok;
        {ok, H} ->
            CallId = maps:get(call_id, H),
            bump(CallId, packets, 1),
            bump(CallId, bytes, byte_size(Bin)),
            route(Sock, Ip, InPort, Bin, H)
    end.

route(Sock, Ip, InPort, Bin, H) ->
    #{call_id := CallId, slot := Slot, token := Token} = H,
    case ets:lookup(?SESS, {CallId, Slot}) of
        [{Key, {UserId, Token, _, _, _}}] ->
            case check_rate(CallId, Slot, now_ms()) of
                allow ->
                    ets:insert(?SESS, {Key, {UserId, Token, Ip, InPort,
                                             now_ms()}}),
                    forward(Sock, CallId, Slot, Bin);
                deny ->
                    bump(CallId, dropped, 1)
            end;
        [{_, _}] ->
            %% Slot dikenal, token salah.
            bump(CallId, dropped, 1);
        [] ->
            %% Paket pertama slot: resolve + validasi via DB, lalu cache.
            case resolve_slot(CallId, Slot) of
                {ok, UserId, Token} ->
                    ets:insert(?SESS, {{CallId, Slot},
                                      {UserId, Token, Ip, InPort, now_ms()}}),
                    route(Sock, Ip, InPort, Bin, H);
                _ ->
                    bump(CallId, dropped, 1)
            end
    end.

%% @doc Slot → user ekspektasi via DB (1 query per slot per call).
%% 1-1: slot 0 = caller, 1 = callee. Room: indeks participants id-ASC.
resolve_slot(CallId, Slot) ->
    case nusa_mysql:query(
           <<"SELECT caller_id, callee_id, conversation_id FROM calls "
             "WHERE id = ?">>,
           [CallId]) of
        {ok, _, [[Caller, Callee, Conv]]} ->
            resolve_user(to_int(Caller), to_int(Callee), Conv, CallId, Slot);
        _ ->
            {error, no_call}
    end.

resolve_user(Caller, _Callee, null, CallId, 0) ->
    {ok, Caller, call_token(CallId, Caller)};
resolve_user(_Caller, Callee, null, CallId, 1) ->
    {ok, Callee, call_token(CallId, Callee)};
resolve_user(_, _, null, _, _) -> {error, bad_slot};
resolve_user(_, _, _Conv, CallId, Slot) ->
    case nusa_mysql:query(
           <<"SELECT user_id FROM call_participants WHERE call_id = ? "
             "ORDER BY id ASC">>,
           [CallId]) of
        {ok, _, Rows} ->
            Users = [to_int(U) || [U] <- Rows],
            case Slot >= 0 andalso Slot < length(Users) of
                true ->
                    UserId = lists:nth(Slot + 1, Users),
                    {ok, UserId, call_token(CallId, UserId)};
                false ->
                    {error, bad_slot}
            end;
        _ ->
            {error, no_call}
    end.

forward(Sock, CallId, Slot, Bin) ->
    Echo = case ets:lookup(?ECHO, CallId) of
               [{_, true}] -> true;
               _ -> false
           end,
    Slots = [S || {{C, S}, _} <- ets:tab2list(?SESS), C =:= CallId],
    lists:foreach(
      fun(Dst) ->
          case ets:lookup(?SESS, {CallId, Dst}) of
              [{_, {_, _, Ip, Port, _}}]
                when Ip =/= undefined, Port =/= undefined ->
                  catch gen_udp:send(Sock, Ip, Port, Bin),
                  bump(CallId, forwarded, 1);
              _ ->
                  bump(CallId, dropped, 1)
          end
      end, targets(Slots, Echo, Slot)).

sweep_sessions() ->
    Cutoff = now_ms() - ?SESSION_TTL_MS,
    [ets:delete(?SESS, K)
     || {K, {_, _, _, _, Seen}} <- ets:tab2list(?SESS), Seen < Cutoff],
    ok.

bump(CallId, Key, N) ->
    try ets:update_counter(?STATS, CallId, {pos(Key), N}) of
        _ -> ok
    catch
        error:badarg ->
            ets:insert(?STATS, {CallId, 0, 0, 0, 0}),
            catch ets:update_counter(?STATS, CallId, {pos(Key), N}),
            ok
    end.

pos(packets) -> 2;
pos(bytes) -> 3;
pos(forwarded) -> 4;
pos(dropped) -> 5.

read_stats(CallId) ->
    case ets:lookup(?STATS, CallId) of
        [{_, P, B, F, D}] ->
            #{packets => P, bytes => B, forwarded => F, dropped => D};
        [] ->
            #{packets => 0, bytes => 0, forwarded => 0, dropped => 0}
    end.

now_ms() ->
    erlang:monotonic_time(millisecond).

to_int(N) when is_integer(N) -> N;
to_int(B) when is_binary(B) ->
    try binary_to_integer(B) catch _:_ -> 0 end;
to_int(L) when is_list(L) ->
    try list_to_integer(L) catch _:_ -> 0 end;
to_int(_) -> 0.

%%% ------------------------------------------------------------ pure

%% @doc Parse + validasi header NVP/1. Keepalive (flag) boleh tanpa payload;
%% media wajib ≥16 byte tag.
-spec parse_header(binary()) ->
    {ok, #{call_id := pos_integer(), slot := 0..255, flags := 0..255,
            token := non_neg_integer(), seq := non_neg_integer(),
            ts := non_neg_integer(), payload := binary()}}
    | {error, term()}.
parse_header(<<?MAGIC:8, ?VER:8, CallId:32, Slot:8, Flags:8, Token:32,
               Seq:16, Ts:32, Rest/binary>> = Bin)
  when byte_size(Bin) =< ?MAX_DGRAM ->
    KeepAlive = (Flags band ?FLAG_KEEPALIVE) =/= 0,
    case {KeepAlive, byte_size(Rest)} of
        {true, 0} ->
            {ok, hdr(CallId, Slot, Flags, Token, Seq, Ts, <<>>)};
        {true, _} ->
            {error, keepalive_with_payload};
        {false, N} when N >= ?TAG_LEN ->
            {ok, hdr(CallId, Slot, Flags, Token, Seq, Ts, Rest)};
        {false, _} ->
            {error, short_payload}
    end;
parse_header(Bin) when is_binary(Bin), byte_size(Bin) > ?MAX_DGRAM ->
    {error, too_big};
parse_header(_) ->
    {error, bad_header}.

hdr(CallId, Slot, Flags, Token, Seq, Ts, Payload) ->
    #{call_id => CallId, slot => Slot, flags => Flags, token => Token,
      seq => Seq, ts => Ts, payload => Payload}.

%% @doc Slot tujuan: echo → pengirim saja; normal → semua kecuali pengirim.
-spec targets([0..255], boolean(), 0..255) -> [0..255].
targets(_Slots, true, From) -> [From];
targets(Slots, false, From) -> [S || S <- Slots, S =/= From].

%% @doc Token bucket per pengirim (ETS, single-writer). Kapasitas 150,
%% isi 100/detik — voice 50 pps + redundansi + kontrol masih longgar.
-spec check_rate(pos_integer(), 0..255, integer()) -> allow | deny.
check_rate(CallId, Slot, Now) ->
    Key = {CallId, Slot},
    {Tokens, Last} = case ets:lookup(?RATE, Key) of
                         [{_, T, L}] -> {T, L};
                         [] -> {?RATE_CAP, Now}
                     end,
    {Verdict, NTokens} = bucket(Tokens, Last, Now),
    ets:insert(?RATE, {Key, NTokens, Now}),
    Verdict.

%% @doc Inti token bucket murni: {keputusan, sisa token}.
-spec bucket(integer(), integer(), integer()) -> {allow | deny, integer()}.
bucket(Tokens, Last, Now) ->
    Refilled = min(?RATE_CAP, Tokens + (Now - Last) * ?RATE_PER_SEC div 1000),
    case Refilled >= 1 of
        true -> {allow, Refilled - 1};
        false -> {deny, Refilled}
    end.
