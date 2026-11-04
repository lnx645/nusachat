%%%-------------------------------------------------------------------
%% @doc nusachat_realtime top level supervisor.
%% @end
%%%-------------------------------------------------------------------

-module(nusachat_realtime_sup).

-behaviour(supervisor).

-export([start_link/0]).

-export([init/1]).

-define(SERVER, ?MODULE).

-spec start_link() -> {ok, pid()} | {error, term()}.
start_link() ->
    supervisor:start_link({local, ?SERVER}, ?MODULE, []).

%% sup_flags() = #{strategy => strategy(),         % optional
%%                 intensity => non_neg_integer(), % optional
%%                 period => pos_integer()}        % optional
%% Urutan start = urutan dependensi: mysql dulu (auth + fanout +
%% nearby butuh DB), registry sebelum gateway (socket register saat
%% connect), gateway terakhir (buka port hanya bila semua siap).
%% one_for_one: satu anak crash tidak menjatuhkan yang lain.
-spec init([]) -> {ok, {supervisor:sup_flags(), [supervisor:child_spec()]}}.
init([]) ->
    SupFlags = #{strategy => one_for_one,
                 intensity => 5,
                 period => 60},
    ChildSpecs = [
        #{id => nusa_mysql,
          start => {nusa_mysql, start_link, []},
          restart => permanent,
          type => worker},
        #{id => nusa_registry,
          start => {nusa_registry, start_link, []},
          restart => permanent,
          type => worker},
        #{id => nusa_nearby,
          start => {nusa_nearby, start_link, []},
          restart => permanent,
          type => worker},
        #{id => nusa_fanout,
          start => {nusa_fanout, start_link, []},
          restart => permanent,
          type => worker},
        #{id => nusa_voice_relay,
          start => {nusa_voice_relay, start_link, []},
          restart => permanent,
          type => worker},
        #{id => nusa_gateway,
          start => {nusa_gateway, start_link, []},
          restart => permanent,
          type => worker}
    ],
    {ok, {SupFlags, ChildSpecs}}.

%% internal functions
