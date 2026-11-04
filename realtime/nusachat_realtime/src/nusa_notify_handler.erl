%%%-------------------------------------------------------------------
%%% @doc Internal push endpoint: PHP POSTs freshly written outbox rows
%%% here so delivery is instant (no polling). Shared secret via
%%% header x-ws-secret (WS_SECRET, same as backend/.env).
%%% Body: {"events": [{"id":N,"user_id":N,"payload":"{...}","created_at":"..."}]}
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_notify_handler).

-behaviour(cowboy_handler).

-export([init/2]).

init(Req0, State) ->
    case cowboy_req:method(Req0) of
        <<"POST">> ->
            handle_post(Req0, State);
        _ ->
            Req = cowboy_req:reply(405, #{}, <<>>, Req0),
            {ok, Req, State}
    end.

handle_post(Req0, State) ->
    Secret = list_to_binary(nusa_config:get("WS_SECRET", "dev-ws-secret")),
    case cowboy_req:header(<<"x-ws-secret">>, Req0, <<>>) of
        Secret ->
            {ok, Body, Req} = cowboy_req:read_body(Req0),
            Reply = try jsx:decode(Body, [return_maps]) of
                #{<<"events">> := Events} when is_list(Events) ->
                    nusa_poller:notify(Events),
                    200;
                _ ->
                    400
            catch
                _:_ -> 400
            end,
            {ok, cowboy_req:reply(Reply, #{}, <<>>, Req), State};
        _ ->
            Req = cowboy_req:reply(403, #{}, <<>>, Req0),
            {ok, Req, State}
    end.
