%%%-------------------------------------------------------------------
%%% @doc Live "orang terdekat" tanpa lewat PHP.
%%%
%%% Client kirim {"type":"location.update","data":{"lat":..,"lng":..,
%%% "radius":..}} lewat WebSocket; modul ini:
%%%   1. menyimpan lokasi di ETS (sumber live),
%%%   2. persist ke MySQL (satu UPDATE, tanpa beban REST publik),
%%%   3. menjawab daftar awal dari MySQL (mirror User_model::nearby),
%%%   4. push {"type":"nearby.changed"} ke subscriber lain yang
%%%      radiusnya mencakup lokasi baru.
%%%
%%% Entri ETS basi (> 15 menit) diabaikan. Subscriber dimonitor dan
%%% dibersihkan saat socket mati / unsubscribe.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_nearby).

-behaviour(gen_server).

-export([start_link/0, update/5, unsubscribe/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-define(SERVER, ?MODULE).
-define(LOC_TAB, nusa_nearby_loc).
-define(STALE_SEC, 900).
-define(FRESH_CUTOFF_DAYS, 7).
-define(MAX_ROWS, 300).
-define(MAX_RESULTS, 50).
-define(EARTH_KM, 6371.0).

-record(state, {subs = #{}, mons = #{}}).

start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

%% @returns {ok, [UserDto]} | {error, invalid_location}
update(UserId, Lat, Lng, Radius, Pid) when is_integer(UserId), is_pid(Pid) ->
    gen_server:call(?SERVER, {update, UserId, Lat, Lng, Radius, Pid}, 20000).

unsubscribe(Pid) when is_pid(Pid) ->
    gen_server:cast(?SERVER, {unsubscribe, Pid}).

init([]) ->
    _ = ets:new(?LOC_TAB, [set, protected, named_table, {read_concurrency, true}]),
    {ok, #state{}}.

handle_call({update, UserId, Lat, Lng, Radius, Pid}, _From, State) ->
    case valid_coords(Lat, Lng) of
        false ->
            {reply, {error, invalid_location}, State};
        true ->
            R = clamp_radius(Radius),
            Now = erlang:system_time(second),
            ets:insert(?LOC_TAB, {UserId, Lat, Lng, Now}),
            State1 = monitor_sub(Pid, UserId, Lat, Lng, R, State),
            persist_location(UserId, Lat, Lng),
            Users = nearby_list(UserId, Lat, Lng, R),
            notify_subscribers(UserId, Lat, Lng, State1),
            {reply, {ok, Users}, State1}
    end;
handle_call(_Req, _From, State) ->
    {reply, {error, bad_request}, State}.

handle_cast({unsubscribe, Pid}, State) ->
    {noreply, drop_sub(Pid, State)};
handle_cast(_Msg, State) ->
    {noreply, State}.

handle_info({'DOWN', Ref, process, Pid, _}, #state{subs = Subs, mons = Mons} = State) ->
    case maps:get(Pid, Mons, undefined) of
        Ref -> {noreply, State#state{subs = maps:remove(Pid, Subs),
                                    mons = maps:remove(Pid, Mons)}};
        _ -> {noreply, State}
    end;
handle_info(_Info, State) ->
    {noreply, State}.

terminate(_Reason, _State) ->
    ok.

%%-------------------------------------------------------------------
%% internal
%%-------------------------------------------------------------------

valid_coords(Lat, Lng) when is_number(Lat), is_number(Lng) ->
    Lat >= -90 andalso Lat =< 90 andalso Lng >= -180 andalso Lng =< 180;
valid_coords(_, _) ->
    false.

clamp_radius(R) when is_number(R) ->
    Rounded = round(R),
    min(100, max(1, Rounded));
clamp_radius(_) ->
    25.

monitor_sub(Pid, UserId, Lat, Lng, Radius, #state{subs = Subs, mons = Mons} = State) ->
    Mons1 = case maps:is_key(Pid, Mons) of
        true -> Mons;
        false -> maps:put(Pid, erlang:monitor(process, Pid), Mons)
    end,
    State#state{subs = maps:put(Pid, {UserId, Lat, Lng, Radius}, Subs),
                mons = Mons1}.

drop_sub(Pid, #state{subs = Subs, mons = Mons} = State) ->
    case maps:take(Pid, Mons) of
        {Ref, M} -> erlang:demonitor(Ref, [flush]), State#state{subs = maps:remove(Pid, Subs), mons = M};
        error -> State#state{subs = maps:remove(Pid, Subs)}
    end.

persist_location(UserId, Lat, Lng) ->
    Now = server_time(),
    _ = nusa_mysql:query(
          <<"UPDATE users SET last_seen_at = ?, latitude = ?, longitude = ?, location_updated_at = ? WHERE id = ?">>,
          [Now, round3(Lat), round3(Lng), Now, UserId]),
    ok.

round3(X) -> round(X * 1000) / 1000.

%% Daftar awal: mirror PHP User_model::nearby (300 kandidat segar,
%% kecuali diri + blokir, filter haversine di sini, urut jarak).
nearby_list(UserId, Lat, Lng, Radius) ->
    Cutoff = cutoff_iso(?FRESH_CUTOFF_DAYS),
    case nusa_mysql:query(
           <<"SELECT id, username, display_name, avatar_url, bio, gender, last_seen_at, latitude, longitude FROM users WHERE is_active = 1 AND discoverable = 1 AND id <> ? AND latitude IS NOT NULL AND longitude IS NOT NULL AND location_updated_at >= ? LIMIT 300">>,
           [UserId, Cutoff]) of
        {ok, _, Rows} ->
            Hidden = hidden_ids(UserId),
            Cands = lists:filtermap(
                      fun(Row) -> candidate(UserId, Row, Lat, Lng, Radius, Hidden) end,
                      Rows),
            Sorted = lists:sort(fun({_, A}, {_, B}) -> A =< B end, Cands),
            lists:sublist(enrich_all(UserId, Sorted), ?MAX_RESULTS);
        _ ->
            []
    end.

candidate(UserId, [Id, _U, _D, _A, _B, _G, _S, CandLat, CandLng], Lat, Lng, Radius, Hidden) ->
    _ = UserId,
    case lists:member(Id, Hidden) of
        true -> false;
        false ->
            case {to_float(CandLat), to_float(CandLng)} of
                {undefined, _} -> false;
                {_, undefined} -> false;
                {CLat, CLng} ->
                    Dist = haversine(Lat, Lng, CLat, CLng),
                    case Dist =< Radius of
                        true -> {true, {Id, round(Dist * 100) / 100}};
                        false -> false
                    end
            end
    end.

enrich_all(UserId, Pairs) ->
    lists:filtermap(
      fun({Id, Dist}) ->
          case user_dto(Id) of
              undefined -> false;
              Dto ->
                  Friendship = friendship(UserId, Id),
                  {true, (enrich_friend(UserId, Dto, Friendship))#{<<"distance_km">> => Dist}}
          end
      end, Pairs).

user_dto(Id) ->
    case nusa_mysql:query(
           <<"SELECT id, username, display_name, avatar_url, bio, gender, last_seen_at FROM users WHERE id = ? AND is_active = 1">>,
           [Id]) of
        {ok, _, [[Uid, Username, Display, Avatar, Bio, Gender, LastSeen]]} ->
            #{<<"id">> => Uid,
              <<"username">> => str(Username),
              <<"display_name">> => str(or_else(Display, Username)),
              <<"avatar_url">> => str_or_null(Avatar),
              <<"bio">> => str_or_null(Bio),
              <<"gender">> => str_or_null(Gender),
              <<"online">> => is_online(LastSeen),
              <<"last_seen_at">> => str_or_null(LastSeen)};
        _ ->
            undefined
    end.

friendship(A, B) ->
    case nusa_mysql:query(
           <<"SELECT id, requester_id, status FROM friendships WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?) LIMIT 1">>,
           [A, B, B, A]) of
        {ok, _, [[Fid, Requester, Status]]} -> {Fid, Requester, Status};
        _ -> none
    end.

enrich_friend(_Me, Dto, none) ->
    Dto#{<<"friend_status">> => <<"none">>};
enrich_friend(Me, Dto, {Fid, Requester, Status}) ->
    Dto#{<<"friend_status">> => str(Status),
         <<"request_id">> => Fid,
         <<"request_direction">> => case Requester =:= Me of
             true -> <<"outgoing">>;
             false -> <<"incoming">>
         end}.

hidden_ids(UserId) ->
    ByMe = ids_column(nusa_mysql:query(
             <<"SELECT blocked_user_id FROM blocks WHERE user_id = ?">>, [UserId])),
    ByOther = ids_column(nusa_mysql:query(
               <<"SELECT user_id FROM blocks WHERE blocked_user_id = ?">>, [UserId])),
    lists:usort(ByMe ++ ByOther).

ids_column({ok, _, Rows}) -> [Id || [Id] <- Rows];
ids_column(_) -> [].

%% Push delta ke subscriber lain yang radiusnya mencakup titik baru.
notify_subscribers(ChangedId, Lat, Lng, #state{subs = Subs}) ->
    maps:foreach(
      fun(Pid, {SubUid, SubLat, SubLng, SubRadius}) ->
          case SubUid =:= ChangedId of
              true -> ok;
              false ->
                  case haversine(SubLat, SubLng, Lat, Lng) =< SubRadius of
                      true -> push_changed(Pid, SubUid, ChangedId, Lat, Lng);
                      false -> ok
                  end
          end
      end, Subs).

push_changed(Pid, SubUid, ChangedId, Lat, Lng) ->
    case user_dto(ChangedId) of
        undefined -> ok;
        Dto ->
            {SubLat, SubLng} = sub_point(SubUid),
            Dist = case {SubLat, SubLng} of
                {undefined, _} -> haversine(Lat, Lng, Lat, Lng);
                _ -> haversine(SubLat, SubLng, Lat, Lng)
            end,
            Full = (enrich_friend(SubUid, Dto, friendship(SubUid, ChangedId)))#{<<"distance_km">> => round(Dist * 100) / 100},
            Payload = jsx:encode(#{<<"type">> => <<"nearby.changed">>,
                                   <<"data">> => #{<<"user">> => Full,
                                                   <<"event">> => <<"update">>}}),
            Pid ! {deliver, Payload},
            ok
    end.

sub_point(SubUid) ->
    case ets:lookup(?LOC_TAB, SubUid) of
        [{_, La, Ln, Ts}] ->
            case erlang:system_time(second) - Ts > ?STALE_SEC of
                true -> {undefined, undefined};
                false -> {La, Ln}
            end;
        [] -> {undefined, undefined}
    end.

haversine(Lat1, Lng1, Lat2, Lng2) ->
    DLat = deg2rad(Lat2 - Lat1),
    DLng = deg2rad(Lng2 - Lng1),
    A = math:sin(DLat / 2) * math:sin(DLat / 2)
        + math:cos(deg2rad(Lat1)) * math:cos(deg2rad(Lat2))
        * math:sin(DLng / 2) * math:sin(DLng / 2),
    2 * ?EARTH_KM * math:asin(math:sqrt(A)).

deg2rad(D) -> D * math:pi() / 180.

to_float(V) when is_float(V) -> V;
to_float(V) when is_integer(V) -> V * 1.0;
to_float(V) when is_binary(V) ->
    try binary_to_float(V)
    catch _:_ ->
        try binary_to_integer(V) * 1.0
        catch _:_ -> undefined
        end
    end;
to_float(_) -> undefined.

str(V) when is_binary(V) -> V;
str(V) when is_list(V) -> list_to_binary(V);
str(V) when is_integer(V) -> integer_to_binary(V);
str(_) -> <<>>.

str_or_null(null) -> null;
str_or_null(undefined) -> null;
str_or_null(V) -> str(V).

or_else(null, D) -> D;
or_else(undefined, D) -> D;
or_else(<<>>, D) -> D;
or_else(V, _) -> V.

is_online(null) -> false;
is_online(undefined) -> false;
is_online(LastSeen) when is_binary(LastSeen) ->
    try
        Ts = calendar:rfc3339_to_system_time(binary_to_list(LastSeen)),
        (erlang:system_time(second) - Ts) < 90
    catch _:_ -> false
    end;
is_online(_) -> false.

cutoff_iso(Days) ->
    Sec = erlang:system_time(second) - Days * 86400,
    list_to_binary(calendar:system_time_to_rfc3339(Sec, [{offset, "Z"}])).

server_time() ->
    list_to_binary(calendar:system_time_to_rfc3339(erlang:system_time(second),
                                                  [{offset, "Z"}])).
