%%%-------------------------------------------------------------------
%%% @doc Single MySQL connection owner (mysql-otp 1.8). All queries go
%%% through this gen_server so the socket is never shared. Reconnects
%%% on failure, retries transient errors.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_mysql).

-behaviour(gen_server).

-export([start_link/0, query/2]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-define(SERVER, ?MODULE).
-define(CONN, nusa_mysql_conn).

-spec start_link() -> {ok, pid()} | {error, term()}.
start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

%%%-------------------------------------------------------------------
%%% @doc Query lewat satu-satunya koneksi (sync, timeout 15 dtk).
%%% Gagal → retry 4x dengan jeda, lalu `{error, unavailable}'.
%%% @end
%%%-------------------------------------------------------------------
-spec query(iodata(), list()) -> term().
query(Sql, Params) ->
    gen_server:call(?SERVER, {query, Sql, Params}, 15000).

-spec init([]) -> {ok, map()}.
init([]) ->
    {ok, connect(#{})} .

-spec connect(map()) -> map().
connect(State) ->
    Opts = nusa_config:db_opts(),
    Args = [{name, {local, ?CONN}},
            {host, maps:get(host, Opts)},
            {port, maps:get(port, Opts)},
            {user, maps:get(user, Opts)},
            {password, maps:get(password, Opts)},
            {database, maps:get(database, Opts)},
            {query_timeout, 10000},
            {keep_alive, true}],
    case mysql:start_link(Args) of
        {ok, Conn} ->
            error_logger:info_msg("nusa_mysql connected (~p)~n", [Conn]),
            State#{conn => Conn};
        {error, Reason} ->
            error_logger:error_msg("nusa_mysql connect failed: ~p, retrying~n", [Reason]),
            timer:sleep(2000),
            connect(State)
    end.

-spec handle_call(term(), term(), map()) -> {reply, term(), map()}.
handle_call({query, Sql, Params}, _From, State) ->
    {Reply, State2} = run_query(Sql, Params, State, 4),
    {reply, Reply, State2};
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
    catch mysql:stop(?CONN),
    ok.

run_query(_Sql, _Params, State, 0) ->
    {{error, unavailable}, State};
run_query(Sql, Params, State, Attempts) ->
    Conn = maps:get(conn, State, undefined),
    Alive = is_pid(Conn) andalso is_process_alive(Conn),
    State1 = case Alive of
        true -> State;
        false -> connect(State)
    end,
    case mysql:query(?CONN, Sql, Params) of
        {error, Reason} ->
            error_logger:warning_msg("nusa_mysql query failed (~p left): ~p~n",
                                     [Attempts, Reason]),
            timer:sleep(150 * (5 - Attempts)),
            run_query(Sql, Params, State1, Attempts - 1);
        Result ->
            {Result, State1}
    end.
