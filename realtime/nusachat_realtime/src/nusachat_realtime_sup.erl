%%%-------------------------------------------------------------------
%% @doc nusachat_realtime top level supervisor.
%% @end
%%%-------------------------------------------------------------------

-module(nusachat_realtime_sup).

-behaviour(supervisor).

-export([start_link/0]).

-export([init/1]).

-define(SERVER, ?MODULE).

start_link() ->
    supervisor:start_link({local, ?SERVER}, ?MODULE, []).

%% sup_flags() = #{strategy => strategy(),         % optional
%%                 intensity => non_neg_integer(), % optional
%%                 period => pos_integer()}        % optional
%% child_spec() = #{id => child_id(),       % mandatory
%%                  start => mfargs(),      % mandatory
%%                  restart => restart(),   % optional
%%                  shutdown => shutdown(), % optional
%%                  type => worker(),       % optional
%%                  modules => modules()}   % optional
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
        #{id => nusa_poller,
          start => {nusa_poller, start_link, []},
          restart => permanent,
          type => worker},
        #{id => nusa_gateway,
          start => {nusa_gateway, start_link, []},
          restart => permanent,
          type => worker}
    ],
    {ok, {SupFlags, ChildSpecs}}.

%% internal functions
