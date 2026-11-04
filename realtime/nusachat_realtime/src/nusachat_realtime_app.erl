%%%-------------------------------------------------------------------
%% @doc nusachat_realtime public API
%% @end
%%%-------------------------------------------------------------------

-module(nusachat_realtime_app).

-behaviour(application).

-export([start/2, stop/1]).

start(_StartType, _StartArgs) ->
    nusachat_realtime_sup:start_link().

stop(_State) ->
    ok.

%% internal functions
