%%%-------------------------------------------------------------------
%%% @doc Cowboy WebSocket handler. Auth via ?token= (JWT HS256 + users
%%% lookup), app ping/pong, server ping every 25s, per-user fan-out
%%% via nusa_registry. Wire-compatible with backend/tools/ws-server.php.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_ws_handler).

-behaviour(cowboy_websocket).

-export([init/2]).
-export([websocket_init/1, websocket_handle/2, websocket_info/2, terminate/3]).

-define(PING_MS, 25000).

-spec init(cowboy_req:req(), map()) -> {cowboy_websocket, cowboy_req:req(), map()}
                                          | {ok, cowboy_req:req(), map()}.
init(Req, State) ->
    Qs = cowboy_req:parse_qs(Req),
    Token = proplists:get_value(<<"token">>, Qs, <<>>),
    case nusa_auth:verify_token(Token) of
        {ok, UserId} ->
            {cowboy_websocket, Req, State#{user_id => UserId}};
        {error, unauthorized} ->
            Req2 = cowboy_req:reply(401, #{}, <<>>, Req),
            {ok, Req2, State};
        {error, unavailable} ->
            Req2 = cowboy_req:reply(503, #{}, <<>>, Req),
            {ok, Req2, State}
    end.

-spec websocket_init(map()) -> {reply, {text, binary()}, map()}.
websocket_init(#{user_id := UserId} = State) ->
    nusa_registry:register(UserId, self()),
    Now = server_time(),
    _ = nusa_mysql:query(
          <<"UPDATE users SET last_seen_at = ? WHERE id = ?">>, [Now, UserId]),
    Connected = jsx:encode(#{<<"type">> => <<"connected">>,
                             <<"data">> => #{<<"user_id">> => UserId,
                                             <<"server_time">> => Now}}),
    erlang:send_after(?PING_MS, self(), send_ping),
    {reply, {text, Connected}, State}.

-spec websocket_handle(term(), map()) -> {reply, {text, binary()}, map()} | {ok, map()}.
websocket_handle({text, Msg}, State) ->
    try jsx:decode(Msg, [return_maps]) of
        #{<<"type">> := <<"ping">>} ->
            Pong = jsx:encode(#{<<"type">> => <<"pong">>,
                                <<"data">> => #{<<"server_time">> => server_time()}}),
            {reply, {text, Pong}, State};
        #{<<"type">> := <<"location.update">>, <<"data">> := Data} ->
            Lat = maps:get(<<"lat">>, Data, undefined),
            Lng = maps:get(<<"lng">>, Data, undefined),
            Radius = maps:get(<<"radius">>, Data, 25),
            #{user_id := UserId} = State,
            error_logger:info_msg("nearby update uid=~p lat=~p lng=~p~n", [UserId, Lat, Lng]),
            % Jangan biarkan crash callee memutus koneksi: jawab error saja.
            Result = try nusa_nearby:update(UserId, Lat, Lng, Radius, self()) of
                R -> R
            catch
                C:R2 ->
                    error_logger:warning_msg("nearby update failed ~p:~p~n", [C, R2]),
                    {error, unavailable}
            end,
            case Result of
                {ok, Users} ->
                    Res = jsx:encode(#{<<"type">> => <<"nearby.result">>,
                                       <<"data">> => #{<<"users">> => Users,
                                                       <<"live">> => true}}),
                    {reply, {text, Res}, State};
                {error, invalid_location} ->
                    Err = jsx:encode(#{<<"type">> => <<"nearby.error">>,
                                       <<"data">> => #{<<"code">> => <<"invalid_location">>,
                                                       <<"message">> => <<"lat/lng tidak valid.">>}}),
                    {reply, {text, Err}, State};
                {error, unavailable} ->
                    Err = jsx:encode(#{<<"type">> => <<"nearby.error">>,
                                       <<"data">> => #{<<"code">> => <<"unavailable">>,
                                                       <<"message">> => <<"Layanan lokasi sibuk, coba lagi.">>}}),
                    {reply, {text, Err}, State}
            end;
        #{<<"type">> := <<"nearby.unsubscribe">>} ->
            nusa_nearby:unsubscribe(self()),
            {ok, State};
        #{<<"type">> := T} ->
            error_logger:info_msg("ws unhandled type=~p~n", [T]),
            {ok, State};
        _ ->
            {ok, State}
    catch C:R ->
        error_logger:warning_msg("ws handle crash ~p:~p~n", [C, R]),
        {ok, State}
    end;
websocket_handle(_Frame, State) ->
    {ok, State}.

-spec websocket_info(term(), map()) ->
    {reply, {text | ping, binary()}, map()} | {ok, map()}.
websocket_info({deliver, Payload}, State) ->
    {reply, {text, Payload}, State};
websocket_info(send_ping, State) ->
    erlang:send_after(?PING_MS, self(), send_ping),
    {reply, {ping, <<>>}, State};
websocket_info(_Info, State) ->
    {ok, State}.

-spec terminate(term(), cowboy_req:req(), map()) -> ok.
terminate(_Reason, _Req, #{user_id := UserId}) ->
    nusa_registry:unregister(UserId, self()),
    nusa_nearby:unsubscribe(self()),
    ok;
terminate(_Reason, _Req, _State) ->
    ok.

server_time() ->
    list_to_binary(calendar:system_time_to_rfc3339(erlang:system_time(second),
                                                  [{offset, "Z"}])).
