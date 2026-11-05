%%%-------------------------------------------------------------------
%%% @doc Unit test nusa_presence: transisi + payload murni.
%%% Jalur DB/socket-nya diuji ws_smoke.py (gateway + MySQL hidup).
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_presence_tests).

-include_lib("eunit/include/eunit.hrl").

transition_test() ->
    ?assertEqual(online, nusa_presence:transition(0, 1)),
    ?assertEqual(online, nusa_presence:transition(0, 3)),
    ?assertEqual(offline, nusa_presence:transition(1, 0)),
    ?assertEqual(offline, nusa_presence:transition(2, 0)),
    %% Device kedua masuk/keluar bukan transisi presence.
    ?assertEqual(stable, nusa_presence:transition(1, 2)),
    ?assertEqual(stable, nusa_presence:transition(2, 1)),
    ?assertEqual(stable, nusa_presence:transition(1, 1)),
    ?assertEqual(stable, nusa_presence:transition(0, 0)),
    ok.

payload_test() ->
    Now = <<"2026-11-05T00:00:00Z">>,
    ?assertEqual(#{<<"user_id">> => 9,
                   <<"online">> => true,
                   <<"last_seen_at">> => Now},
                 nusa_presence:payload(9, true, Now)),
    ?assertEqual(#{<<"user_id">> => 9,
                   <<"online">> => false,
                   <<"last_seen_at">> => Now},
                 nusa_presence:payload(9, false, Now)),
    ok.
