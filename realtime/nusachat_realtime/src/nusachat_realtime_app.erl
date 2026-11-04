%%%-------------------------------------------------------------------
%% @doc nusachat_realtime public API
%% @end
%%%-------------------------------------------------------------------

-module(nusachat_realtime_app).

-behaviour(application).

-export([start/2, stop/1]).

-spec start(term(), term()) -> {ok, pid()} | {error, term()}.
start(_StartType, _StartArgs) ->
    nusachat_realtime_sup:start_link().

-spec stop(term()) -> ok.
stop(_State) ->
    ok.
