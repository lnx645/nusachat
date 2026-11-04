%%%-------------------------------------------------------------------
%%% @doc Owns the Cowboy clear listener so it sits under supervision.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_gateway).

-behaviour(gen_server).

-export([start_link/0]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-define(SERVER, ?MODULE).

-spec start_link() -> {ok, pid()} | {error, term()}.
start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

-spec init([]) -> {ok, map()}.
init([]) ->
    Port = nusa_config:port(),
    Dispatch = cowboy_router:compile(
                 [{'_', [{"/internal/notify", nusa_notify_handler, #{}},
                         {'_', nusa_ws_handler, #{}}]}]),
    {ok, _} = cowboy:start_clear(nusa_http, [{port, Port}],
                                 #{env => #{dispatch => Dispatch}}),
    error_logger:info_msg("nusa_gateway listening on 0.0.0.0:~p~n", [Port]),
    {ok, #{port => Port}}.

-spec handle_call(term(), term(), map()) -> {reply, term(), map()}.
handle_call(_Req, _From, State) ->
    {reply, {error, bad_request}, State}.

-spec handle_cast(term(), map()) -> {noreply, map()}.
handle_cast(_Msg, State) ->
    {noreply, State}.

-spec handle_info(term(), map()) -> {noreply, map()}.
handle_info(_Info, State) ->
    {noreply, State}.

-spec terminate(term(), map()) -> ok.
terminate(_Reason, _State) ->
    catch cowboy:stop_listener(nusa_http),
    ok.
