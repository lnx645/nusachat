%%%-------------------------------------------------------------------
%%% @doc Registry user_id => [ws pid]. Monitors sockets, cleans on DOWN.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_registry).

-behaviour(gen_server).

-export([start_link/0, register/2, unregister/2, members/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-define(SERVER, ?MODULE).

-spec start_link() -> {ok, pid()} | {error, term()}.
start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

%%%-------------------------------------------------------------------
%%% @doc Daftarkan socket `Pid' sebagai milik `UserId' (async).
%%% @end
%%%-------------------------------------------------------------------
-spec register(integer(), pid()) -> ok.
register(UserId, Pid) ->
    gen_server:cast(?SERVER, {register, UserId, Pid}).

%%%-------------------------------------------------------------------
%%% @doc Lepaskan socket dari user (async; DOWN monitor membersihkan
%%% sisanya bila terminate tak terpanggil).
%%% @end
%%%-------------------------------------------------------------------
-spec unregister(integer(), pid()) -> ok.
unregister(UserId, Pid) ->
    gen_server:cast(?SERVER, {unregister, UserId, Pid}).

%%%-------------------------------------------------------------------
%%% @doc Daftar socket hidup milik user (sync, untuk fan-out).
%%% @end
%%%-------------------------------------------------------------------
-spec members(integer()) -> [pid()].
members(UserId) ->
    gen_server:call(?SERVER, {members, UserId}).

-spec init([]) -> {ok, map()}.
init([]) ->
    Tab = ets:new(?MODULE, [set, protected, {read_concurrency, true}]),
    %% Registry mulai kosong = tak ada yang online; selaraskan kolom
    %% `is_online' agar bendera basi (crash tanpa terminate) tak gantung.
    nusa_presence:reset_all(),
    {ok, #{tab => Tab, mons => #{}}}.

-spec handle_call(term(), term(), map()) -> {reply, term(), map()}.
handle_call({members, UserId}, _From, #{tab := Tab} = State) ->
    Pids = case ets:lookup(Tab, UserId) of
        [{_, Ps}] -> Ps;
        [] -> []
    end,
    {reply, Pids, State};
handle_call(_Req, _From, State) ->
    {reply, {error, bad_request}, State}.

-spec handle_cast(term(), map()) -> {noreply, map()}.
handle_cast({register, UserId, Pid}, #{tab := Tab, mons := Mons} = State) ->
    Before = socket_count(Tab, UserId),
    Mons2 = case maps:is_key(Pid, Mons) of
        true -> Mons;
        false ->
            Ref = erlang:monitor(process, Pid),
            maps:put(Pid, {Ref, UserId}, Mons)
    end,
    Pids = case ets:lookup(Tab, UserId) of
        [{_, Ps}] -> lists:usort([Pid | Ps]);
        [] -> [Pid]
    end,
    ets:insert(Tab, {UserId, Pids}),
    %% Socket pertama = transisi offline -> online (async, tak blokir).
    case nusa_presence:transition(Before, length(Pids)) of
        online -> nusa_presence:online(UserId);
        _ -> ok
    end,
    {noreply, State#{mons => Mons2}};
handle_cast({unregister, UserId, Pid}, #{tab := Tab, mons := Mons} = State) ->
    {Before, After} = remove_pid(Tab, UserId, Pid),
    case nusa_presence:transition(Before, After) of
        offline -> nusa_presence:offline(UserId);
        _ -> ok
    end,
    Mons2 = case lists:any(fun({_, Pids2}) -> lists:member(Pid, Pids2) end,
                           ets:tab2list(Tab)) of
        true -> Mons;
        false ->
            case maps:take(Pid, Mons) of
                {{Ref, _}, M} -> erlang:demonitor(Ref, [flush]), M;
                error -> Mons
            end
    end,
    {noreply, State#{mons => Mons2}};
handle_cast(_Msg, State) ->
    {noreply, State}.

-spec handle_info(term(), map()) -> {noreply, map()}.
handle_info({'DOWN', Ref, process, Pid, _}, #{tab := Tab, mons := Mons} = State) ->
    case maps:get(Pid, Mons, undefined) of
        {Ref, UserId} ->
            {Before, After} = remove_pid(Tab, UserId, Pid),
            case nusa_presence:transition(Before, After) of
                offline -> nusa_presence:offline(UserId);
                _ -> ok
            end,
            {noreply, State#{mons => maps:remove(Pid, Mons)}};
        _ ->
            {noreply, State}
    end;
handle_info(_Info, State) ->
    {noreply, State}.

-spec terminate(term(), map()) -> ok.
terminate(_Reason, _State) ->
    ok.

%% ---------------------------------------------------------- helpers

-spec socket_count(ets:tab(), integer()) -> non_neg_integer().
socket_count(Tab, UserId) ->
    case ets:lookup(Tab, UserId) of
        [{_, Ps}] -> length(Ps);
        [] -> 0
    end.

%% Hapus Pid dari set user; kembalikan {sebelum, sesudah}.
-spec remove_pid(ets:tab(), integer(), pid()) ->
    {non_neg_integer(), non_neg_integer()}.
remove_pid(Tab, UserId, Pid) ->
    Before = socket_count(Tab, UserId),
    case ets:lookup(Tab, UserId) of
        [{_, Ps}] ->
            case lists:delete(Pid, Ps) of
                [] -> ets:delete(Tab, UserId);
                Rest -> ets:insert(Tab, {UserId, Rest})
            end;
        [] ->
            ok
    end,
    {Before, socket_count(Tab, UserId)}.
